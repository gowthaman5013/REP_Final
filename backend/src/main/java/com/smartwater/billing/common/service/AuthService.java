package com.smartwater.billing.common.service;

import com.smartwater.billing.common.config.JwtUtils;
import com.smartwater.billing.common.dto.AuthDTOs.*;
import com.smartwater.billing.resident.dto.OccupancyDTOs.RegisterResidentRequest;
import com.smartwater.billing.resident.dto.OccupancyDTOs.SubmitOccupancyRequest;
import com.smartwater.billing.common.entity.*;
import com.smartwater.billing.owner.entity.*;
import com.smartwater.billing.resident.entity.*;
import com.smartwater.billing.resident.entity.ApartmentOccupancy.OccupancyStatus;
import com.smartwater.billing.common.exception.BadRequestException;
import com.smartwater.billing.common.exception.ResourceNotFoundException;
import com.smartwater.billing.common.repository.*;
import com.smartwater.billing.owner.repository.*;
import com.smartwater.billing.resident.repository.*;
import com.smartwater.billing.common.config.HouseholdProperties;
import com.smartwater.billing.resident.service.OccupancyService;
import com.smartwater.billing.common.util.TokenGenerator;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final BuildingOwnerRepository ownerRepository;
    private final BuildingRepository buildingRepository;
    private final ResidentRepository residentRepository;
    private final ResidentInvitationRepository invitationRepository;
    private final ApartmentRepository apartmentRepository;
    private final ApartmentOccupancyRepository occupancyRepository;
    private final OccupancyRequestRepository occupancyRequestRepository;
    private final HouseholdSizeHistoryRepository historyRepository;
    private final HouseholdProperties householdProperties;
    private final WaterMeterRepository waterMeterRepository;
    private final OccupancyService occupancyService;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;

    public AuthService(
            AuthenticationManager authenticationManager,
            UserRepository userRepository,
            BuildingOwnerRepository ownerRepository,
            BuildingRepository buildingRepository,
            ResidentRepository residentRepository,
            ResidentInvitationRepository invitationRepository,
            ApartmentRepository apartmentRepository,
            ApartmentOccupancyRepository occupancyRepository,
            OccupancyRequestRepository occupancyRequestRepository,
            HouseholdSizeHistoryRepository historyRepository,
            HouseholdProperties householdProperties,
            WaterMeterRepository waterMeterRepository,
            @Lazy OccupancyService occupancyService,
            PasswordEncoder passwordEncoder,
            JwtUtils jwtUtils
    ) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.ownerRepository = ownerRepository;
        this.buildingRepository = buildingRepository;
        this.residentRepository = residentRepository;
        this.invitationRepository = invitationRepository;
        this.apartmentRepository = apartmentRepository;
        this.occupancyRepository = occupancyRepository;
        this.occupancyRequestRepository = occupancyRequestRepository;
        this.historyRepository = historyRepository;
        this.householdProperties = householdProperties;
        this.waterMeterRepository = waterMeterRepository;
        this.occupancyService = occupancyService;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtils = jwtUtils;
    }

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail().trim().toLowerCase())
                .orElseThrow(() -> new BadRequestException("Invalid email or password"));

        if (request.getSelectedRole() != null && !request.getSelectedRole().trim().isEmpty()) {
            if (!user.getRole().name().equalsIgnoreCase(request.getSelectedRole().trim())) {
                throw new BadRequestException("Role mismatch: This account is registered as " 
                    + user.getRole().name().replace("_", " ") + ". Please select the correct role tab.");
            }
        }

        // 1. If user account status is explicitly REJECTED
        if (user.getStatus() == User.UserStatus.REJECTED) {
            throw new BadRequestException("No account exists");
        }

        // 2. Specific checks for RESIDENT role
        if (user.getRole() == User.Role.RESIDENT) {
            Resident resident = residentRepository.findByUser(user)
                    .or(() -> residentRepository.findByUserIdOrEmail(user.getUserId(), user.getEmail()))
                    .or(() -> residentRepository.findByEmailIgnoreCase(user.getEmail()))
                    .orElse(null);

            if (resident != null) {
                // If apartment was marked REJECTED
                if ("REJECTED".equalsIgnoreCase(resident.getApartmentNumber())) {
                    if (user.getStatus() != User.UserStatus.REJECTED) {
                        user.setStatus(User.UserStatus.REJECTED);
                        userRepository.save(user);
                    }
                    throw new BadRequestException("No account exists");
                }

                // Check active occupancy
                boolean hasActiveOccupancy = occupancyRepository.findFirstByResidentResidentIdAndStatus(
                        resident.getResidentId(), ApartmentOccupancy.OccupancyStatus.ACTIVE).isPresent();

                List<OccupancyRequest> requests = occupancyRequestRepository.findAllByResidentId(resident.getResidentId());
                boolean hasApprovedRequest = requests.stream().anyMatch(r -> r.getStatus() == OccupancyRequest.RequestStatus.APPROVED);
                boolean hasPendingRequest = requests.stream().anyMatch(r -> r.getStatus() == OccupancyRequest.RequestStatus.PENDING);
                boolean hasRejectedRequest = requests.stream().anyMatch(r -> r.getStatus() == OccupancyRequest.RequestStatus.REJECTED);

                boolean isApproved = hasActiveOccupancy || hasApprovedRequest;

                // Case: Rejected by owner
                if (!isApproved && hasRejectedRequest && !hasPendingRequest) {
                    if (user.getStatus() != User.UserStatus.REJECTED) {
                        user.setStatus(User.UserStatus.REJECTED);
                        userRepository.save(user);
                    }
                    throw new BadRequestException("No account exists");
                }

                // Case: Pending owner approval
                if (!isApproved) {
                    throw new BadRequestException("Your account is not approved yet");
                }
            } else if (user.getStatus() == User.UserStatus.PENDING) {
                throw new BadRequestException("Your account is not approved yet");
            }
        }

        // 3. Specific checks for BUILDING_OWNER role
        if (user.getRole() == User.Role.BUILDING_OWNER) {
            BuildingOwner owner = ownerRepository.findByUser(user)
                    .orElseThrow(() -> new ResourceNotFoundException("Owner profile not found"));
            if (owner.getApprovalStatus() == BuildingOwner.ApprovalStatus.REJECTED || user.getStatus() == User.UserStatus.REJECTED) {
                throw new BadRequestException("No account exists");
            }
            if (owner.getApprovalStatus() == BuildingOwner.ApprovalStatus.PENDING || user.getStatus() == User.UserStatus.PENDING) {
                throw new BadRequestException("Your account is not approved yet");
            }
        }

        // 4. General fallback status checks
        if (user.getStatus() == User.UserStatus.REJECTED) {
            throw new BadRequestException("No account exists");
        }
        if (user.getStatus() == User.UserStatus.PENDING) {
            throw new BadRequestException("Your account is not approved yet");
        }
        if (user.getStatus() == User.UserStatus.INACTIVE) {
            throw new BadRequestException("No account exists");
        }

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail().trim().toLowerCase(), request.getPassword())
        );

        SecurityContextHolder.getContext().setAuthentication(authentication);
        String jwt = jwtUtils.generateJwtToken(authentication);

        String phone = "N/A";
        if (user.getRole() == User.Role.BUILDING_OWNER) {
            BuildingOwner owner = ownerRepository.findByUser(user)
                    .or(() -> ownerRepository.findByUserIdOrEmail(user.getUserId(), user.getEmail()))
                    .orElse(null);
            if (owner != null && owner.getPhone() != null && !owner.getPhone().trim().isEmpty()) {
                phone = owner.getPhone();
            }
        } else if (user.getRole() == User.Role.RESIDENT) {
            Resident resident = residentRepository.findByUser(user)
                    .or(() -> residentRepository.findByUserIdOrEmail(user.getUserId(), user.getEmail()))
                    .or(() -> residentRepository.findByEmailIgnoreCase(user.getEmail()))
                    .orElse(null);
            if (resident != null && resident.getPhone() != null && !resident.getPhone().trim().isEmpty()) {
                phone = resident.getPhone();
            }
        }

        return LoginResponse.builder()
                .token(jwt)
                .userId(user.getUserId())
                .name(user.getName())
                .email(user.getEmail())
                .phone(phone)
                .role(user.getRole().name())
                .status(user.getStatus().name())
                .build();
    }

    @Transactional
    public MessageResponse registerOwner(RegisterOwnerRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        Optional<User> existingUserOpt = userRepository.findByEmail(email);
        User user;
        BuildingOwner owner;
        if (existingUserOpt.isPresent()) {
            User existingUser = existingUserOpt.get();
            BuildingOwner existingOwner = ownerRepository.findByUser(existingUser)
                    .or(() -> ownerRepository.findByUserIdOrEmail(existingUser.getUserId(), email))
                    .orElse(null);
            boolean isRejected = existingUser.getStatus() == User.UserStatus.REJECTED ||
                    (existingOwner != null && existingOwner.getApprovalStatus() == BuildingOwner.ApprovalStatus.REJECTED);
            if (!isRejected) {
                throw new BadRequestException("Email is already registered!");
            }
            existingUser.setName(request.getName().trim());
            existingUser.setPassword(passwordEncoder.encode(request.getPassword()));
            existingUser.setRole(User.Role.BUILDING_OWNER);
            existingUser.setStatus(User.UserStatus.PENDING);
            user = userRepository.save(existingUser);

            if (existingOwner != null) {
                existingOwner.setName(request.getName().trim());
                existingOwner.setPhone(request.getPhone().trim());
                existingOwner.setApprovalStatus(BuildingOwner.ApprovalStatus.PENDING);
                owner = ownerRepository.save(existingOwner);
            } else {
                owner = BuildingOwner.builder()
                        .user(user)
                        .name(request.getName().trim())
                        .phone(request.getPhone().trim())
                        .approvalStatus(BuildingOwner.ApprovalStatus.PENDING)
                        .build();
                owner = ownerRepository.save(owner);
            }
        } else {
            // Save User
            user = User.builder()
                    .name(request.getName().trim())
                    .email(email)
                    .password(passwordEncoder.encode(request.getPassword()))
                    .role(User.Role.BUILDING_OWNER)
                    .status(User.UserStatus.PENDING)
                    .build();
            user = userRepository.save(user);

            // Save Building Owner
            owner = BuildingOwner.builder()
                    .user(user)
                    .name(request.getName().trim())
                    .phone(request.getPhone().trim())
                    .approvalStatus(BuildingOwner.ApprovalStatus.PENDING)
                    .build();
            owner = ownerRepository.save(owner);
        }

        // Save Initial Building
        Building building = Building.builder()
                .owner(owner)
                .buildingName(request.getBuildingName().trim())
                .buildingNumber(request.getBuildingNumber().trim())
                .totalFlats(request.getTotalFlats() != null ? request.getTotalFlats() : 10)
                .location(request.getLocation().trim())
                .build();
        buildingRepository.save(building);

        return new MessageResponse("Owner registration successful! Account status is PENDING approval by Super Admin.");
    }

    @Transactional
    public LoginResponse registerResident(RegisterResidentRequest request) {
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new BadRequestException("Full name is required.");
        }
        if (request.getEmail() == null || request.getEmail().trim().isEmpty()) {
            throw new BadRequestException("Email address is required.");
        }
        String email = request.getEmail().trim().toLowerCase();
        Optional<User> existingUserOpt = userRepository.findByEmail(email);
        User user;
        if (existingUserOpt.isPresent()) {
            User existingUser = existingUserOpt.get();
            boolean isRejectedOrVacated = false;
            if (existingUser.getStatus() == User.UserStatus.REJECTED) {
                isRejectedOrVacated = true;
            } else if (existingUser.getRole() == User.Role.RESIDENT) {
                Resident existingRes = residentRepository.findByUser(existingUser)
                        .or(() -> residentRepository.findByEmailIgnoreCase(email))
                        .orElse(null);
                if (existingRes != null && ("REJECTED".equalsIgnoreCase(existingRes.getApartmentNumber())
                        || "Vacated".equalsIgnoreCase(existingRes.getApartmentNumber())
                        || existingRes.getAccountStatus() == Resident.AccountStatus.INACTIVE)) {
                    isRejectedOrVacated = true;
                }
            }

            if (!isRejectedOrVacated) {
                throw new BadRequestException("An account with email '" + email + "' is already registered! Please log in.");
            }

            // Reset and reuse existing user as a fresh pending resident
            existingUser.setName(request.getName().trim());
            existingUser.setPassword(passwordEncoder.encode(request.getPassword().trim()));
            existingUser.setRole(User.Role.RESIDENT);
            existingUser.setStatus(User.UserStatus.PENDING);
            user = userRepository.save(existingUser);
        } else {
            user = null; // will be created below after input validation
        }

        if (request.getPassword() == null || request.getPassword().trim().length() < 6) {
            throw new BadRequestException("Password must be at least 6 characters long.");
        }
        if (request.getConfirmPassword() != null && !request.getPassword().equals(request.getConfirmPassword())) {
            throw new BadRequestException("Passwords do not match.");
        }

        // 1. Mandatory Building validation
        if (request.getBuildingId() == null) {
            throw new BadRequestException("Building selection is required.");
        }
        Building building = buildingRepository.findById(request.getBuildingId())
                .orElseThrow(() -> new BadRequestException("Selected building not found."));
        if (building.getOwner() == null || building.getOwner().getApprovalStatus() != BuildingOwner.ApprovalStatus.APPROVED) {
            throw new BadRequestException("Cannot register in this building: The property building owner has not been approved by the Super Admin.");
        }

        // 2. Mandatory Apartment Unit validation
        if (request.getApartmentId() == null) {
            throw new BadRequestException("Apartment unit selection is required.");
        }
        Apartment apartment = apartmentRepository.findById(request.getApartmentId())
                .orElseThrow(() -> new BadRequestException("Selected apartment unit not found."));

        if (apartment.getBuilding() == null || !apartment.getBuilding().getBuildingId().equals(building.getBuildingId())) {
            throw new BadRequestException("Selected apartment unit does not belong to the selected building.");
        }

        // Check if apartment is already actively occupied
        Optional<ApartmentOccupancy> occupied = occupancyRepository.findFirstByApartmentApartmentIdAndStatus(apartment.getApartmentId(), OccupancyStatus.ACTIVE);
        if (occupied.isPresent()) {
            throw new BadRequestException("This apartment unit (" + apartment.getApartmentName() + ") is already occupied.");
        }

        // Check if apartment already has an active pending request
        Optional<OccupancyRequest> pendingReq = occupancyRequestRepository.findActiveRequestByApartmentId(apartment.getApartmentId(), OccupancyRequest.RequestStatus.PENDING);
        if (pendingReq.isPresent()) {
            throw new BadRequestException("This apartment unit already has a pending occupancy request under review.");
        }

        // Check if apartment has an active unexpired invitation
        List<ResidentInvitation> pendingInvites = invitationRepository.findByBuildingBuildingIdAndStatus(
                building.getBuildingId(),
                ResidentInvitation.InvitationStatus.PENDING
        );
        LocalDateTime now = LocalDateTime.now();
        boolean hasPendingInvite = pendingInvites.stream().anyMatch(inv ->
                ((inv.getApartment() != null && inv.getApartment().getApartmentId().equals(apartment.getApartmentId()))
                        || (inv.getApartmentNumber() != null && inv.getApartmentNumber().equalsIgnoreCase(apartment.getRoomNumber())))
                        && (inv.getExpiresAt() == null || inv.getExpiresAt().isAfter(now))
        );
        if (hasPendingInvite) {
            throw new BadRequestException("This apartment unit currently has a pending invitation.");
        }

        // 3. Mandatory Household Details for Water Diagnostics validation
        if (request.getHouseholdSize() == null) {
            throw new BadRequestException("Total people (household size) is required.");
        }
        if (request.getHouseholdSize() < 1 || request.getHouseholdSize() > 15) {
            throw new BadRequestException("Total people must be between 1 and 15.");
        }
        if (request.getChildrenCount() == null) {
            throw new BadRequestException("Children count (under 12) is required.");
        }
        if (request.getChildrenCount() < 0) {
            throw new BadRequestException("Children count cannot be negative.");
        }
        if (request.getChildrenCount() > request.getHouseholdSize()) {
            throw new BadRequestException("Children count cannot exceed total people.");
        }

        if (user == null) {
            // Save User with explicit RESIDENT role only
            user = User.builder()
                    .name(request.getName().trim())
                    .email(email)
                    .password(passwordEncoder.encode(request.getPassword().trim()))
                    .role(User.Role.RESIDENT)
                    .status(User.UserStatus.PENDING)
                    .build();
            user = userRepository.save(user);
        }

        // Check if pre-existing resident row exists (e.g. from invitation or previous rejected record)
        Resident resident = residentRepository.findByEmailIgnoreCase(email).orElse(null);
        if (resident == null) {
            resident = Resident.builder()
                    .user(user)
                    .building(building)
                    .name(request.getName().trim())
                    .email(email)
                    .phone(request.getPhone() != null ? request.getPhone().trim() : "")
                    .apartmentNumber("PENDING")
                    .invitationStatus(Resident.InvitationStatus.ACCEPTED)
                    .accountStatus(Resident.AccountStatus.INACTIVE)
                    .build();
            resident = residentRepository.save(resident);
        } else {
            resident.setUser(user);
            resident.setBuilding(building);
            resident.setName(request.getName().trim());
            if (request.getPhone() != null && !request.getPhone().trim().isEmpty()) {
                resident.setPhone(request.getPhone().trim());
            }
            resident.setApartmentNumber("PENDING");
            resident.setInvitationStatus(Resident.InvitationStatus.ACCEPTED);
            resident.setAccountStatus(Resident.AccountStatus.INACTIVE);
            resident = residentRepository.save(resident);
        }

        // Clean up previous REJECTED occupancy requests for this resident so the new registration is clean
        List<OccupancyRequest> oldRejected = occupancyRequestRepository.findAllByResidentId(resident.getResidentId()).stream()
                .filter(r -> r.getStatus() == OccupancyRequest.RequestStatus.REJECTED)
                .collect(Collectors.toList());
        if (!oldRejected.isEmpty()) {
            occupancyRequestRepository.deleteAll(oldRejected);
        }

        // Automatically submit occupancy request with household diagnostic details
        occupancyService.submitOccupancyRequest(email, new SubmitOccupancyRequest(
                apartment.getApartmentId(),
                "Initial registration occupancy request",
                request.getHouseholdSize(),
                request.getChildrenCount()
        ));

        return LoginResponse.builder()
                .token(null)
                .userId(user.getUserId())
                .name(user.getName())
                .email(user.getEmail())
                .phone(resident.getPhone())
                .role(user.getRole().name())
                .status(user.getStatus().name())
                .build();
    }

    @Transactional
    public MessageResponse acceptInvitation(SetPasswordRequest request) {
        if (request.getPassword() == null || request.getPassword().trim().length() < 6) {
            throw new BadRequestException("Password must be at least 6 characters long.");
        }

        ResidentInvitation invitation = null;
        Resident resident = null;

        if (request.getToken() != null && !request.getToken().trim().isEmpty()) {
            String rawToken = request.getToken().trim();
            String tokenHash = TokenGenerator.hashToken(rawToken);

            invitation = invitationRepository.findByTokenHash(tokenHash).orElse(null);
            if (invitation != null) {
                resident = invitation.getResident();
            } else {
                resident = residentRepository.findByInvitationToken(rawToken)
                        .orElse(null);
            }
        }

        if (resident == null && request.getEmail() != null && !request.getEmail().trim().isEmpty()) {
            String searchEmail = request.getEmail().trim().toLowerCase();
            resident = residentRepository.findByEmailIgnoreCase(searchEmail)
                    .or(() -> residentRepository.findByUserEmail(searchEmail))
                    .orElse(null);
        }

        if (resident == null) {
            throw new ResourceNotFoundException("Invalid or expired invitation token.");
        }

        if (invitation != null && invitation.getStatus() == ResidentInvitation.InvitationStatus.ACCEPTED) {
            throw new BadRequestException("This invitation has already been accepted! Please log in on the Login page.");
        }
        if (resident.getInvitationStatus() == Resident.InvitationStatus.ACCEPTED && resident.getUser() != null) {
            throw new BadRequestException("This resident account (" + resident.getEmail() + ") has already been activated! Please log in on the Login page.");
        }

        LocalDateTime expiresAt = invitation != null ? invitation.getExpiresAt() : resident.getExpiresAt();
        if (expiresAt != null && LocalDateTime.now().isAfter(expiresAt)) {
            throw new BadRequestException("This invitation token has expired. Please request a new invitation from your Building Owner.");
        }

        // Create or activate User entity
        User user = resident.getUser();
        if (user == null) {
            user = userRepository.findByEmail(resident.getEmail().toLowerCase()).orElse(null);
        }

        if (user == null) {
            user = User.builder()
                    .name(resident.getName())
                    .email(resident.getEmail().trim().toLowerCase())
                    .password(passwordEncoder.encode(request.getPassword().trim()))
                    .role(User.Role.RESIDENT)
                    .status(User.UserStatus.ACTIVE)
                    .build();
            userRepository.save(user);
        } else {
            user.setPassword(passwordEncoder.encode(request.getPassword().trim()));
            user.setStatus(User.UserStatus.ACTIVE);
            user.setRole(User.Role.RESIDENT);
            userRepository.save(user);
        }

        LocalDateTime now = LocalDateTime.now();

        // Update Resident entity
        resident.setUser(user);
        resident.setInvitationStatus(Resident.InvitationStatus.ACCEPTED);
        resident.setAccountStatus(Resident.AccountStatus.ACTIVE);
        resident.setAcceptedAt(now);
        resident = residentRepository.save(resident);

        // Update ResidentInvitation entity
        if (invitation != null) {
            invitation.setStatus(ResidentInvitation.InvitationStatus.ACCEPTED);
            invitation.setAcceptedAt(now);
            invitationRepository.save(invitation);
        }

        // Establish Apartment & Active Occupancy for invited unit
        Building building = resident.getBuilding();
        String aptNumber = resident.getApartmentNumber();
        if (building != null && aptNumber != null && !aptNumber.isEmpty() && !aptNumber.startsWith("FORMER") && !aptNumber.equals("PENDING")) {
            Apartment apt = null;
            if (invitation != null && invitation.getApartment() != null) {
                apt = invitation.getApartment();
            } else {
                apt = apartmentRepository.findByBuildingBuildingIdAndRoomNumber(building.getBuildingId(), aptNumber).orElse(null);
                if (apt == null) {
                    int floor = 1;
                    try {
                        if (aptNumber.length() >= 3) {
                            floor = Integer.parseInt(aptNumber.substring(0, 1));
                        }
                    } catch (Exception ignored) {}
                    apt = Apartment.builder()
                            .building(building)
                            .floorNumber(floor)
                            .apartmentName("Flat " + aptNumber)
                            .roomNumber(aptNumber)
                            .unitType("2BHK")
                            .build();
                    apt = apartmentRepository.save(apt);
                }
            }

            Integer hhSize = request.getHouseholdSize();
            Integer chCount = request.getChildrenCount() != null ? request.getChildrenCount() : 0;
            String hhSource = "RESIDENT_DECLARED";
            boolean hhConfirmed = true;

            if (hhSize == null || hhSize < 1) {
                if (invitation != null && invitation.getHouseholdSize() != null && invitation.getHouseholdSize() > 0) {
                    hhSize = invitation.getHouseholdSize();
                    chCount = invitation.getChildrenCount() != null ? invitation.getChildrenCount() : 0;
                    hhSource = "OWNER_ENTERED";
                    hhConfirmed = true;
                } else {
                    hhSize = householdProperties.getDefaultSizeForUnitType(apt.getUnitType());
                    chCount = 0;
                    hhSource = "DEFAULT_ESTIMATE";
                    hhConfirmed = false;
                }
            }

            Optional<ApartmentOccupancy> existingOcc = occupancyRepository.findFirstByApartmentApartmentIdAndStatus(apt.getApartmentId(), OccupancyStatus.ACTIVE);
            ApartmentOccupancy occ;
            if (existingOcc.isEmpty()) {
                occ = ApartmentOccupancy.builder()
                        .apartment(apt)
                        .resident(resident)
                        .startDate(now)
                        .status(OccupancyStatus.ACTIVE)
                        .householdSize(hhSize)
                        .childrenCount(chCount)
                        .householdSizeSource(hhSource)
                        .householdSizeConfirmed(hhConfirmed)
                        .build();
                occ = occupancyRepository.save(occ);
            } else {
                occ = existingOcc.get();
                occ.setHouseholdSize(hhSize);
                occ.setChildrenCount(chCount);
                occ.setHouseholdSizeSource(hhSource);
                occ.setHouseholdSizeConfirmed(hhConfirmed);
                occ = occupancyRepository.save(occ);
            }

            // Record initial HouseholdSizeHistory row
            boolean sanityWarning = hhSize > householdProperties.getLimits().getSanityWarningThreshold();
            HouseholdSizeHistory history = HouseholdSizeHistory.builder()
                    .occupancy(occ)
                    .apartment(apt)
                    .householdSize(hhSize)
                    .childrenCount(chCount)
                    .effectiveFrom(now.toLocalDate())
                    .effectiveTo(null)
                    .changedByUser(user)
                    .changedByRole("RESIDENT")
                    .reason("MOVE_IN")
                    .note("Move-in via invitation accept")
                    .sanityWarning(sanityWarning)
                    .status("ACTIVE")
                    .createdAt(now)
                    .build();
            historyRepository.save(history);

            // Link WaterMeter
            WaterMeter meter = waterMeterRepository.findByResidentResidentId(resident.getResidentId()).orElse(null);
            if (meter == null) {
                String mNum = "WM-" + building.getBuildingNumber() + "-" + aptNumber;
                meter = waterMeterRepository.findByMeterNumber(mNum).orElse(null);
                if (meter == null) {
                    meter = WaterMeter.builder()
                            .building(building)
                            .apartment(apt)
                            .resident(resident)
                            .meterNumber(mNum)
                            .installationDate(LocalDate.now())
                            .status(WaterMeter.MeterStatus.ACTIVE)
                            .build();
                } else {
                    meter.setResident(resident);
                    meter.setApartment(apt);
                    meter.setStatus(WaterMeter.MeterStatus.ACTIVE);
                }
                waterMeterRepository.save(meter);
            } else if (meter.getApartment() == null) {
                meter.setApartment(apt);
                waterMeterRepository.save(meter);
            }
        }

        return new MessageResponse("Account for " + resident.getName() + " activated successfully! You can now log in.");
    }
}
