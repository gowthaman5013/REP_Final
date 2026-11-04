package com.smartwater.billing.resident.service;

import com.smartwater.billing.common.dto.AuthDTOs.MessageResponse;
import com.smartwater.billing.common.dto.AuthDTOs.ValidateInvitationResponse;
import com.smartwater.billing.common.dto.DashboardDTOs.*;
import com.smartwater.billing.resident.dto.ResidentDTOs.*;
import com.smartwater.billing.common.entity.*;
import com.smartwater.billing.owner.entity.*;
import com.smartwater.billing.resident.entity.*;
import com.smartwater.billing.resident.entity.ApartmentOccupancy.OccupancyStatus;
import com.smartwater.billing.common.exception.BadRequestException;
import com.smartwater.billing.common.exception.ResourceNotFoundException;
import com.smartwater.billing.owner.service.BuildingOwnerService;
import com.smartwater.billing.common.repository.*;
import com.smartwater.billing.owner.repository.*;
import com.smartwater.billing.resident.repository.*;
import com.smartwater.billing.resident.entity.ResidentHistory;
import com.smartwater.billing.resident.repository.ResidentHistoryRepository;

import com.smartwater.billing.common.service.EmailService;
import com.smartwater.billing.common.util.TokenGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class ResidentService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ResidentService.class);

    @PersistenceContext
    private EntityManager entityManager;

    private final ResidentRepository residentRepository;
    private final ResidentInvitationRepository invitationRepository;
    private final BuildingRepository buildingRepository;
    private final WaterMeterRepository meterRepository;
    private final BillRepository billRepository;
    private final WaterUsageRepository usageRepository;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final BuildingOwnerService ownerService;
    private final EmailService emailService;
    private final ApartmentRepository apartmentRepository;
    private final ApartmentOccupancyRepository occupancyRepository;
    private final OccupancyRequestRepository occupancyRequestRepository;
    private final BillingCycleRepository billingCycleRepository;
    private final ResidentHistoryRepository residentHistoryRepository;

    public ResidentService(
            ResidentRepository residentRepository,
            ResidentInvitationRepository invitationRepository,
            BuildingRepository buildingRepository,
            WaterMeterRepository meterRepository,
            BillRepository billRepository,
            WaterUsageRepository usageRepository,
            NotificationRepository notificationRepository,
            UserRepository userRepository,
            BuildingOwnerService ownerService,
            EmailService emailService,
            ApartmentRepository apartmentRepository,
            ApartmentOccupancyRepository occupancyRepository,
            OccupancyRequestRepository occupancyRequestRepository,
            BillingCycleRepository billingCycleRepository,
            ResidentHistoryRepository residentHistoryRepository
    ) {
        this.residentRepository = residentRepository;
        this.invitationRepository = invitationRepository;
        this.buildingRepository = buildingRepository;
        this.meterRepository = meterRepository;
        this.billRepository = billRepository;
        this.usageRepository = usageRepository;
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.ownerService = ownerService;
        this.emailService = emailService;
        this.apartmentRepository = apartmentRepository;
        this.occupancyRepository = occupancyRepository;
        this.occupancyRequestRepository = occupancyRequestRepository;
        this.billingCycleRepository = billingCycleRepository;
        this.residentHistoryRepository = residentHistoryRepository;
    }

    @Value("${resident.invitation.expiration-hours:24}")
    private int expirationHours;

    @Transactional
    public void ensureDefaultResidentsForOwner(BuildingOwner owner) {
        // No-op: resident records are created exclusively through user self-registration
    }

    @Transactional
    public ResidentResponse inviteResident(String ownerEmail, InviteResidentRequest request) {
        BuildingOwner owner = ownerService.getOwnerByUserEmail(ownerEmail);
        Building building = buildingRepository.findById(request.getBuildingId())
                .orElseThrow(() -> new ResourceNotFoundException("Building not found with id: " + request.getBuildingId()));

        if (!building.getOwner().getOwnerId().equals(owner.getOwnerId())) {
            throw new BadRequestException("Unauthorized access: You do not own building '" + building.getBuildingName() + "'!");
        }

        // Check if an ACTIVE user already exists for this email
        Optional<User> existingUserOpt = userRepository.findByEmail(request.getEmail().trim().toLowerCase());
        if (existingUserOpt.isPresent() && existingUserOpt.get().getStatus() == User.UserStatus.ACTIVE) {
            throw new BadRequestException("An active account already exists for " + request.getEmail() + ". Invitation rejected.");
        }

        // Resolve or create Apartment
        String room = request.getApartmentNumber() != null ? request.getApartmentNumber().trim() : "";
        Apartment apt = null;
        if (request.getApartmentId() != null) {
            apt = apartmentRepository.findById(request.getApartmentId()).orElse(null);
            if (apt != null) {
                room = apt.getRoomNumber();
            }
        }
        if (apt == null && !room.isEmpty()) {
            apt = apartmentRepository.findByBuildingBuildingIdAndRoomNumber(building.getBuildingId(), room).orElse(null);
            if (apt == null) {
                int floor = 1;
                try {
                    if (room.length() >= 3) {
                        floor = Integer.parseInt(room.substring(0, 1));
                    }
                } catch (Exception ignored) {}
                apt = Apartment.builder()
                        .building(building)
                        .floorNumber(floor)
                        .apartmentName("Flat " + room)
                        .roomNumber(room)
                        .unitType("2BHK")
                        .build();
                apt = apartmentRepository.save(apt);
            }
        }

        // Validate that apartment is NOT already occupied
        if (apt != null) {
            Optional<ApartmentOccupancy> activeOcc = occupancyRepository.findFirstByApartmentApartmentIdAndStatus(apt.getApartmentId(), ApartmentOccupancy.OccupancyStatus.ACTIVE);
            if (activeOcc.isPresent()) {
                throw new BadRequestException("Apartment " + apt.getApartmentName() + " is already occupied by " + activeOcc.get().getResident().getName() + ". You cannot invite another resident to an occupied apartment.");
            }
        }

        // Check if resident profile exists
        Resident resident = residentRepository.findByEmailIgnoreCase(request.getEmail().trim())
                .orElse(null);

        String rawToken = TokenGenerator.generateRawToken();
        String tokenHash = TokenGenerator.hashToken(rawToken);
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(expirationHours);

        if (resident == null) {
            resident = Resident.builder()
                    .building(building)
                    .name(request.getName().trim())
                    .email(request.getEmail().trim().toLowerCase())
                    .phone(request.getPhone() != null ? request.getPhone().trim() : "")
                    .apartmentNumber(room)
                    .invitationToken(rawToken)
                    .tokenHash(tokenHash)
                    .expiresAt(expiresAt)
                    .invitationStatus(Resident.InvitationStatus.PENDING)
                    .accountStatus(Resident.AccountStatus.INACTIVE)
                    .build();
        } else {
            resident.setBuilding(building);
            resident.setName(request.getName().trim());
            if (request.getPhone() != null && !request.getPhone().trim().isEmpty()) {
                resident.setPhone(request.getPhone().trim());
            }
            resident.setApartmentNumber(room);
            resident.setInvitationToken(rawToken);
            resident.setTokenHash(tokenHash);
            resident.setExpiresAt(expiresAt);
            resident.setInvitationStatus(Resident.InvitationStatus.PENDING);
            resident.setAccountStatus(Resident.AccountStatus.INACTIVE);
        }

        resident = residentRepository.save(resident);

        // Cancel previous pending invitations for this resident
        List<ResidentInvitation> prevList = invitationRepository.findByResidentEmailOrderByCreatedAtDesc(resident.getEmail());
        for (ResidentInvitation prev : prevList) {
            if (prev.getStatus() == ResidentInvitation.InvitationStatus.PENDING) {
                prev.setStatus(ResidentInvitation.InvitationStatus.CANCELLED);
                invitationRepository.save(prev);
            }
        }

        ResidentInvitation invitation = ResidentInvitation.builder()
                .resident(resident)
                .building(building)
                .apartment(apt)
                .invitedBy(owner)
                .residentName(resident.getName())
                .residentEmail(resident.getEmail())
                .phoneNumber(resident.getPhone())
                .apartmentNumber(room)
                .tokenHash(tokenHash)
                .expiresAt(expiresAt)
                .householdSize(request.getHouseholdSize())
                .childrenCount(request.getChildrenCount())
                .status(ResidentInvitation.InvitationStatus.PENDING)
                .build();
        invitationRepository.save(invitation);

        // Assign or reuse Water Meter
        WaterMeter meter = meterRepository.findFirstByResidentResidentId(resident.getResidentId()).orElse(null);
        if (meter == null) {
            String mNum = "WM-" + building.getBuildingNumber() + "-" + room;
            meter = meterRepository.findByMeterNumber(mNum).orElse(null);
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
                meter.setBuilding(building);
                meter.setApartment(apt);
                meter.setResident(resident);
                meter.setStatus(WaterMeter.MeterStatus.ACTIVE);
            }
            meterRepository.save(meter);
        } else if (apt != null && meter.getApartment() == null) {
            meter.setApartment(apt);
            meterRepository.save(meter);
        }

        // Send Email Notification
        String ownerName = owner.getUser() != null ? owner.getUser().getName() : owner.getName();
        boolean emailSent = emailService.sendResidentInvitation(resident.getEmail(), resident.getName(), ownerName, building.getBuildingName(), room, rawToken);

        ResidentResponse response = convertToResidentResponse(resident);
        response.setEmailSent(emailSent);
        return response;
    }

    public ValidateInvitationResponse validateInvitationToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new BadRequestException("Invitation token parameter is required.");
        }

        String tokenHash = TokenGenerator.hashToken(token.trim());
        ResidentInvitation invitation = invitationRepository.findByTokenHash(tokenHash)
                .or(() -> invitationRepository.findByTokenHash(token.trim()))
                .orElse(null);

        Resident resident = null;
        if (invitation != null) {
            resident = invitation.getResident();
        } else {
            resident = residentRepository.findByInvitationToken(token.trim())
                    .or(() -> residentRepository.findByTokenHash(tokenHash))
                    .orElseThrow(() -> new ResourceNotFoundException("Invalid or expired invitation link. Please request a new invitation from your building owner."));
        }

        if (resident.getInvitationStatus() == Resident.InvitationStatus.ACCEPTED && resident.getUser() != null) {
            throw new BadRequestException("This invitation link has already been accepted. Please proceed to login.");
        }

        if (resident.getExpiresAt() != null && resident.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BadRequestException("This invitation link has expired (valid for 24 hours). Please contact your building owner to resend the invitation.");
        }

        String name = resident.getName();
        String email = resident.getEmail();
        String buildingName = resident.getBuilding() != null ? resident.getBuilding().getBuildingName() : "N/A";
        String apartmentNumber = resident.getApartmentNumber();
        LocalDateTime expiresAt = resident.getExpiresAt();
        Integer householdSize = (invitation != null) ? invitation.getHouseholdSize() : null;
        Integer childrenCount = (invitation != null) ? invitation.getChildrenCount() : null;

        return new ValidateInvitationResponse("PENDING", name, email, buildingName, apartmentNumber, householdSize, childrenCount, expiresAt != null ? expiresAt.toString() : null, "Valid invitation token.");
    }

    public ValidateInvitationResponse validateInvitation(String token) {
        return validateInvitationToken(token);
    }

    @Transactional
    public MessageResponse resendInvitation(String ownerEmail, Long residentId) {
        BuildingOwner owner = ownerService.getOwnerByUserEmail(ownerEmail);
        Resident resident = residentRepository.findById(residentId)
                .orElseThrow(() -> new ResourceNotFoundException("Resident not found with id: " + residentId));

        if (!resident.getBuilding().getOwner().getOwnerId().equals(owner.getOwnerId())) {
            throw new BadRequestException("Unauthorized access: You do not own the building for this resident!");
        }

        if (resident.getInvitationStatus() == Resident.InvitationStatus.ACCEPTED && resident.getUser() != null) {
            throw new BadRequestException("Resident account is already activated!");
        }

        // Cancel previous invitations for this resident
        List<ResidentInvitation> prevList = invitationRepository.findByResidentEmailOrderByCreatedAtDesc(resident.getEmail());
        for (ResidentInvitation prev : prevList) {
            if (prev.getStatus() == ResidentInvitation.InvitationStatus.PENDING) {
                prev.setStatus(ResidentInvitation.InvitationStatus.CANCELLED);
                invitationRepository.save(prev);
            }
        }

        // Generate new secure raw token and hash
        String newRawToken = TokenGenerator.generateRawToken();
        String newTokenHash = TokenGenerator.hashToken(newRawToken);
        LocalDateTime newExpiresAt = LocalDateTime.now().plusHours(expirationHours);

        resident.setInvitationToken(newRawToken);
        resident.setTokenHash(newTokenHash);
        resident.setExpiresAt(newExpiresAt);
        resident.setInvitationStatus(Resident.InvitationStatus.PENDING);
        resident.setAccountStatus(Resident.AccountStatus.INACTIVE);
        residentRepository.save(resident);

        ResidentInvitation newInvite = ResidentInvitation.builder()
                .resident(resident)
                .building(resident.getBuilding())
                .invitedBy(owner)
                .residentName(resident.getName())
                .residentEmail(resident.getEmail())
                .phoneNumber(resident.getPhone())
                .apartmentNumber(resident.getApartmentNumber())
                .tokenHash(newTokenHash)
                .expiresAt(newExpiresAt)
                .status(ResidentInvitation.InvitationStatus.PENDING)
                .build();
        invitationRepository.save(newInvite);

        // Dispatch new invitation email with newRawToken
        String ownerName = owner != null && owner.getUser() != null ? owner.getUser().getName() : "Building Owner";
        boolean emailSent = emailService.sendResidentInvitation(resident.getEmail(), resident.getName(), ownerName, resident.getBuilding().getBuildingName(), resident.getApartmentNumber(), newRawToken);

        if (emailSent) {
            return new MessageResponse("An account activation link has been sent to " + resident.getEmail(), true);
        } else {
            return new MessageResponse("Invitation created, but email delivery failed for " + resident.getEmail() + ". Please check SMTP email settings or try resending.", false);
        }
    }

    @Transactional
    public List<ResidentResponse> getOwnerResidents(String ownerEmail, String searchQuery, String statusFilter) {
        BuildingOwner owner = ownerService.getOwnerByUserEmail(ownerEmail);
        
        // Ensure default initial residents exist for this owner if they have buildings but no residents
        ensureDefaultResidentsForOwner(owner);

        // Self-heal and sync re-allocated residents
        List<Resident> allOwnerResidents = residentRepository.findByBuildingOwnerOwnerId(owner.getOwnerId());
        for (Resident r : allOwnerResidents) {
            Optional<ApartmentOccupancy> activeOcc = occupancyRepository.findActiveByResidentEmail(r.getEmail(), OccupancyStatus.ACTIVE);
            if (activeOcc.isPresent()) {
                ApartmentOccupancy occ = activeOcc.get();
                boolean changed = false;
                if (r.getAccountStatus() != Resident.AccountStatus.ACTIVE) {
                    r.setAccountStatus(Resident.AccountStatus.ACTIVE);
                    changed = true;
                }
                String currentRoom = occ.getApartment().getRoomNumber();
                if (currentRoom != null && !currentRoom.equals(r.getApartmentNumber())) {
                    r.setApartmentNumber(currentRoom);
                    changed = true;
                }
                if (occ.getApartment().getBuilding() != null && (r.getBuilding() == null || !r.getBuilding().getBuildingId().equals(occ.getApartment().getBuilding().getBuildingId()))) {
                    r.setBuilding(occ.getApartment().getBuilding());
                    changed = true;
                }
                if (r.getUser() != null && r.getUser().getStatus() != User.UserStatus.ACTIVE) {
                    r.getUser().setStatus(User.UserStatus.ACTIVE);
                    userRepository.save(r.getUser());
                }
                if (changed) {
                    residentRepository.save(r);
                }
            } else {
                boolean hasPending = occupancyRequestRepository.findActiveRequestByResidentId(r.getResidentId(), OccupancyRequest.RequestStatus.PENDING).isPresent();
                if (!hasPending) {
                    boolean changed = false;
                    if (r.getAccountStatus() != Resident.AccountStatus.INACTIVE) {
                        r.setAccountStatus(Resident.AccountStatus.INACTIVE);
                        changed = true;
                    }
                    if ("PENDING".equalsIgnoreCase(r.getApartmentNumber())) {
                        r.setApartmentNumber("REJECTED");
                        changed = true;
                    }
                    if (changed) {
                        residentRepository.save(r);
                    }
                }
            }
        }

        String cleanQuery = (searchQuery != null && !searchQuery.trim().isEmpty()) ? searchQuery.trim() : null;
        Resident.AccountStatus status = Resident.AccountStatus.ACTIVE;
        if (statusFilter != null && !statusFilter.isEmpty()) {
            if ("ALL".equalsIgnoreCase(statusFilter)) {
                status = null;
            } else {
                try {
                    status = Resident.AccountStatus.valueOf(statusFilter.toUpperCase());
                } catch (Exception ignored) {}
            }
        }

        List<Resident> residents = residentRepository.searchResidentsForOwner(owner.getOwnerId(), cleanQuery, status);
        if (residents.isEmpty() && cleanQuery == null && status == null) {
            residents = residentRepository.findByBuildingOwnerOwnerId(owner.getOwnerId());
        }

        // When active status is requested, guarantee former, pending, or rejected users are excluded
        if (status == Resident.AccountStatus.ACTIVE) {
            residents = residents.stream()
                    .filter(r -> r.getAccountStatus() == Resident.AccountStatus.ACTIVE
                            && r.getApartmentNumber() != null
                            && !r.getApartmentNumber().equalsIgnoreCase("PENDING")
                            && !r.getApartmentNumber().equalsIgnoreCase("REJECTED")
                            && !r.getApartmentNumber().startsWith("FORMER-")
                            && !r.getApartmentNumber().equalsIgnoreCase("Vacated"))
                    .collect(Collectors.toList());
        }

        return residents.stream()
                .map(this::convertToResidentResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public ResidentResponse updateResident(String ownerEmail, Long residentId, UpdateResidentRequest request) {
        BuildingOwner owner = ownerService.getOwnerByUserEmail(ownerEmail);
        Resident resident = residentRepository.findById(residentId)
                .orElseThrow(() -> new ResourceNotFoundException("Resident not found with id: " + residentId));

        if (!resident.getBuilding().getOwner().getOwnerId().equals(owner.getOwnerId())) {
            throw new BadRequestException("Unauthorized access to resident!");
        }

        resident.setName(request.getName());
        resident.setPhone(request.getPhone());
        String newRoom = request.getApartmentNumber() != null ? request.getApartmentNumber().trim() : null;

        if (newRoom != null && !newRoom.isEmpty() && !newRoom.equalsIgnoreCase("Vacated") && !newRoom.startsWith("FORMER-")) {
            resident.setApartmentNumber(newRoom);
            resident.setAccountStatus(Resident.AccountStatus.ACTIVE);
            if (resident.getUser() != null) {
                resident.getUser().setStatus(User.UserStatus.ACTIVE);
                userRepository.save(resident.getUser());
            }

            // Sync with apartment & active occupancy
            Optional<Apartment> aptOpt = apartmentRepository.findByBuildingBuildingIdAndRoomNumber(resident.getBuilding().getBuildingId(), newRoom);
            if (aptOpt.isPresent()) {
                Apartment apt = aptOpt.get();
                // Close any prior active occupancy elsewhere for this resident
                occupancyRepository.findActiveByResidentEmail(resident.getEmail(), OccupancyStatus.ACTIVE)
                        .ifPresent(prevOcc -> {
                            if (!prevOcc.getApartment().getApartmentId().equals(apt.getApartmentId())) {
                                prevOcc.setStatus(OccupancyStatus.ENDED);
                                prevOcc.setEndDate(LocalDateTime.now());
                                prevOcc.setMoveOutReason("Reallocated to " + apt.getApartmentName());
                                occupancyRepository.save(prevOcc);
                            }
                        });

                Optional<ApartmentOccupancy> existingOcc = occupancyRepository.findFirstByApartmentApartmentIdAndStatus(apt.getApartmentId(), OccupancyStatus.ACTIVE);
                if (existingOcc.isEmpty()) {
                    ApartmentOccupancy newOcc = ApartmentOccupancy.builder()
                            .apartment(apt)
                            .resident(resident)
                            .startDate(LocalDateTime.now())
                            .status(OccupancyStatus.ACTIVE)
                            .householdSize(2)
                            .childrenCount(0)
                            .build();
                    occupancyRepository.save(newOcc);
                }

                // Associate meter
                meterRepository.findByBuildingBuildingId(resident.getBuilding().getBuildingId()).stream()
                        .filter(m -> (m.getApartment() != null && m.getApartment().getApartmentId().equals(apt.getApartmentId()))
                                || (m.getMeterNumber() != null && m.getMeterNumber().endsWith("-" + newRoom)))
                        .findFirst()
                        .ifPresent(m -> {
                            m.setResident(resident);
                            m.setApartment(apt);
                            meterRepository.save(m);
                        });
            }
        } else {
            resident.setApartmentNumber(newRoom);
        }
        residentRepository.save(resident);

        return convertToResidentResponse(resident);
    }

    @Transactional
    public void deleteResident(String ownerEmail, Long residentId) {
        BuildingOwner owner = ownerService.getOwnerByUserEmail(ownerEmail);
        Resident resident = residentRepository.findById(residentId)
                .orElseThrow(() -> new ResourceNotFoundException("Resident not found with id: " + residentId));

        if (resident.getBuilding() != null && resident.getBuilding().getOwner() != null) {
            if (!resident.getBuilding().getOwner().getOwnerId().equals(owner.getOwnerId())) {
                throw new BadRequestException("Unauthorized access to resident!");
            }
        }

        // Audit log in resident_history using JPA entity
        try {
            com.smartwater.billing.owner.entity.Building bldg = resident.getBuilding();
            String bldgName = bldg != null ? bldg.getBuildingName() : "N/A";
            String aptNum = resident.getApartmentNumber() != null ? resident.getApartmentNumber() : "N/A";
            String resName = resident.getName() != null ? resident.getName() : "Resident";

            residentHistoryRepository.save(ResidentHistory.builder()
                    .ownerId(owner.getOwnerId())
                    .buildingId(bldg != null ? bldg.getBuildingId() : null)
                    .buildingName(bldgName)
                    .apartmentId(null)
                    .apartmentName("Flat " + aptNum)
                    .roomNumber(aptNum)
                    .residentId(resident.getResidentId())
                    .residentName(resName)
                    .residentEmail(resident.getEmail())
                    .residentPhone(resident.getPhone())
                    .activityType("LEFT")
                    .details("Resident removed by building owner")
                    .actor(owner.getUser() != null ? owner.getUser().getName() : "Building Owner")
                    .timestamp(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.warn("Failed to record resident history audit in deleteResident: {}", e.getMessage());
        }

        try {
            entityManager.createNativeQuery("SET FOREIGN_KEY_CHECKS = 0").executeUpdate();

            // 1. Water usage predictions
            entityManager.createNativeQuery("DELETE FROM water_usage_predictions WHERE resident_id = :resId")
                    .setParameter("resId", residentId).executeUpdate();

            // 2. Payments for bills of this resident
            entityManager.createNativeQuery(
                    "DELETE FROM payments WHERE bill_id IN (SELECT b.bill_id FROM (SELECT bill_id FROM bills WHERE resident_id = :resId) b)"
            ).setParameter("resId", residentId).executeUpdate();

            // 3. Invoices
            entityManager.createNativeQuery("DELETE FROM invoices WHERE resident_id = :resId")
                    .setParameter("resId", residentId).executeUpdate();

            // 4. Bills
            entityManager.createNativeQuery("DELETE FROM bills WHERE resident_id = :resId")
                    .setParameter("resId", residentId).executeUpdate();

            // 5. Notifications
            entityManager.createNativeQuery("DELETE FROM notifications WHERE resident_id = :resId")
                    .setParameter("resId", residentId).executeUpdate();

            // 6. Occupancy requests
            entityManager.createNativeQuery("DELETE FROM occupancy_requests WHERE resident_id = :resId")
                    .setParameter("resId", residentId).executeUpdate();

            // 7. Away periods & household size history
            entityManager.createNativeQuery(
                    "DELETE FROM away_periods WHERE occupancy_id IN (SELECT o.occupancy_id FROM (SELECT occupancy_id FROM apartment_occupancies WHERE resident_id = :resId) o)"
            ).setParameter("resId", residentId).executeUpdate();

            entityManager.createNativeQuery(
                    "DELETE FROM household_size_history WHERE occupancy_id IN (SELECT o.occupancy_id FROM (SELECT occupancy_id FROM apartment_occupancies WHERE resident_id = :resId) o)"
            ).setParameter("resId", residentId).executeUpdate();

            // 8. Apartment occupancies
            entityManager.createNativeQuery("DELETE FROM apartment_occupancies WHERE resident_id = :resId")
                    .setParameter("resId", residentId).executeUpdate();

            // 9. Resident invitations
            entityManager.createNativeQuery("DELETE FROM resident_invitations WHERE resident_id = :resId")
                    .setParameter("resId", residentId).executeUpdate();

            // 10. Water meters: unlink resident
            entityManager.createNativeQuery("UPDATE water_meters SET resident_id = NULL WHERE resident_id = :resId")
                    .setParameter("resId", residentId).executeUpdate();

            // 11. Delete from residents table
            Long userId = resident.getUser() != null ? resident.getUser().getUserId() : null;
            entityManager.createNativeQuery("DELETE FROM residents WHERE resident_id = :resId")
                    .setParameter("resId", residentId).executeUpdate();

            // 12. Delete user account if any and role is RESIDENT
            if (userId != null) {
                entityManager.createNativeQuery("UPDATE household_size_history SET changed_by_user_id = NULL WHERE changed_by_user_id = :uid")
                        .setParameter("uid", userId).executeUpdate();
                entityManager.createNativeQuery("DELETE FROM leak_feedback WHERE submitted_by_user_id = :uid")
                        .setParameter("uid", userId).executeUpdate();
                entityManager.createNativeQuery("DELETE FROM users WHERE user_id = :uid AND role = 'RESIDENT'")
                        .setParameter("uid", userId).executeUpdate();
            }
        } finally {
            entityManager.createNativeQuery("SET FOREIGN_KEY_CHECKS = 1").executeUpdate();
        }
    }

    @Transactional
    public void vacateResident(String ownerEmail, Long residentId) {
        BuildingOwner owner = ownerService.getOwnerByUserEmail(ownerEmail);
        Resident resident = residentRepository.findById(residentId)
                .orElseThrow(() -> new ResourceNotFoundException("Resident not found with id: " + residentId));

        if (!resident.getBuilding().getOwner().getOwnerId().equals(owner.getOwnerId())) {
            throw new BadRequestException("Unauthorized access to resident!");
        }

        // Audit log in resident_history using JPA entity
        try {
            com.smartwater.billing.owner.entity.Building bldg = resident.getBuilding();
            BuildingOwner bOwner = bldg != null ? bldg.getOwner() : null;
            if (bOwner != null) {
                residentHistoryRepository.save(ResidentHistory.builder()
                        .ownerId(bOwner.getOwnerId())
                        .buildingId(bldg.getBuildingId())
                        .buildingName(bldg.getBuildingName())
                        .apartmentId(null)
                        .apartmentName("Flat " + resident.getApartmentNumber())
                        .roomNumber(resident.getApartmentNumber())
                        .residentId(resident.getResidentId())
                        .residentName(resident.getName() != null ? resident.getName() : "Resident")
                        .residentEmail(resident.getEmail())
                        .residentPhone(resident.getPhone())
                        .activityType("LEFT")
                        .details("Resident vacated by building owner")
                        .actor(owner.getUser() != null ? owner.getUser().getName() : "Building Owner")
                        .timestamp(LocalDateTime.now())
                        .build());
            }
        } catch (Exception e) {
            log.warn("Failed to record resident history audit in vacate: {}", e.getMessage());
        }

        // End active occupancy
        Optional<ApartmentOccupancy> activeOcc = occupancyRepository.findFirstByResidentResidentIdAndStatus(residentId, ApartmentOccupancy.OccupancyStatus.ACTIVE);
        if (activeOcc.isPresent()) {
            ApartmentOccupancy occ = activeOcc.get();
            occ.setStatus(ApartmentOccupancy.OccupancyStatus.ENDED);
            occ.setEndDate(LocalDateTime.now());
            occ.setMoveOutReason("Vacated by building owner");
            occupancyRepository.save(occ);
        }

        // Detach water meter
        WaterMeter meter = meterRepository.findByResidentResidentId(residentId).orElse(null);
        if (meter != null) {
            meter.setResident(null);
            meterRepository.save(meter);
        }

        resident.setApartmentNumber("Vacated");
        resident.setAccountStatus(Resident.AccountStatus.INACTIVE);
        residentRepository.save(resident);
    }

    public ResidentDashboardStats getResidentDashboardStats(String residentEmail) {
        Resident resident = residentRepository.findByUserEmail(residentEmail)
                .or(() -> residentRepository.findByEmail(residentEmail))
                .orElse(null);

        if (resident == null) {
            User user = userRepository.findByEmail(residentEmail).orElse(null);
            if (user != null && user.getRole() == User.Role.RESIDENT) {
                return ResidentDashboardStats.builder()
                        .residentName(user.getName())
                        .buildingName("No Active Apartment")
                        .apartmentNumber("Vacant / Not Associated")
                        .meterNumber("N/A")
                        .currentMonthUsage(0.0)
                        .activeBillAmount(0.0)
                        .activeBillStatus("NO_BILLS")
                        .activeBillDueDate("N/A")
                        .unreadNotificationsCount(0)
                        .currentBillingCycle("N/A")
                        .cycleStartDate("N/A")
                        .cycleEndDate("N/A")
                        .hasActiveApartment(false)
                        .build();
            }
            throw new ResourceNotFoundException("Resident profile not found for " + residentEmail);
        }

        Optional<ApartmentOccupancy> activeOcc = occupancyRepository.findActiveByResidentEmail(residentEmail, ApartmentOccupancy.OccupancyStatus.ACTIVE);
        boolean hasActive = activeOcc.isPresent();

        String buildingName = "No Active Apartment";
        String apartmentNumber = "Vacant / Not Associated";
        String meterNumber = "N/A";
        Double currentUsage = 0.0;

        if (hasActive) {
            ApartmentOccupancy occ = activeOcc.get();
            buildingName = occ.getApartment().getBuilding().getBuildingName();
            apartmentNumber = occ.getApartment().getRoomNumber();
            WaterMeter meter = meterRepository.findByResidentResidentId(resident.getResidentId()).orElse(null);
            if (meter == null) {
                meter = meterRepository.findByBuildingBuildingId(occ.getApartment().getBuilding().getBuildingId()).stream()
                        .filter(m -> (m.getApartment() != null && m.getApartment().getApartmentId().equals(occ.getApartment().getApartmentId()))
                                || (m.getMeterNumber() != null && m.getMeterNumber().endsWith("-" + occ.getApartment().getRoomNumber())))
                        .findFirst()
                        .orElse(null);
            }
            if (meter != null) {
                meterNumber = meter.getMeterNumber();
                List<WaterUsage> usageList = usageRepository.findByMeterMeterIdOrderByYearDescMonthDesc(meter.getMeterId());
                if (!usageList.isEmpty()) {
                    currentUsage = usageList.get(0).getUnitsConsumed();
                }
            }
        } else if (resident.getBuilding() != null && resident.getApartmentNumber() != null && !resident.getApartmentNumber().startsWith("FORMER") && !resident.getApartmentNumber().equals("PENDING")) {
            buildingName = resident.getBuilding().getBuildingName();
            apartmentNumber = resident.getApartmentNumber();
            WaterMeter meter = meterRepository.findByResidentResidentId(resident.getResidentId()).orElse(null);
            if (meter != null) {
                meterNumber = meter.getMeterNumber();
                List<WaterUsage> usageList = usageRepository.findByMeterMeterIdOrderByYearDescMonthDesc(meter.getMeterId());
                if (!usageList.isEmpty()) {
                    currentUsage = usageList.get(0).getUnitsConsumed();
                }
            }
            hasActive = true;
        }

        List<Bill> bills = billRepository.findByResidentResidentIdOrderByGeneratedAtDesc(resident.getResidentId());
        Bill latestBill = bills.isEmpty() ? null : bills.get(0);

        long unreadCount = notificationRepository.findByResidentResidentIdAndIsReadFalse(resident.getResidentId()).size();

        String cycleName = "N/A";
        String startDate = "N/A";
        String endDate = "N/A";

        if (latestBill != null && latestBill.getBillingCycle() != null) {
            BillingCycle cycle = latestBill.getBillingCycle();
            cycleName = cycle.getBillingMonth() != null ? cycle.getBillingMonth().toString() : ("Cycle #" + cycle.getId());
            startDate = cycle.getStartDate() != null ? cycle.getStartDate().toString() : "N/A";
            endDate = cycle.getEndDate() != null ? cycle.getEndDate().toString() : "N/A";
        } else if (latestBill != null) {
            cycleName = latestBill.getBillMonth() + "/" + latestBill.getBillYear();
        }

        return ResidentDashboardStats.builder()
                .residentName(resident.getName())
                .buildingName(buildingName)
                .apartmentNumber(apartmentNumber)
                .meterNumber(meterNumber)
                .currentMonthUsage(currentUsage)
                .activeBillAmount(latestBill != null ? latestBill.getAmount() : 0.0)
                .activeBillStatus(latestBill != null ? latestBill.getStatus().name() : "NO_BILLS")
                .activeBillDueDate(latestBill != null ? latestBill.getDueDate().toString() : "N/A")
                .unreadNotificationsCount(unreadCount)
                .currentBillingCycle(cycleName)
                .cycleStartDate(startDate)
                .cycleEndDate(endDate)
                .hasActiveApartment(hasActive)
                .build();
    }

    public ResidentComparisonResponse getResidentConsumptionComparison(String residentEmail) {
        Resident resident = residentRepository.findByUserEmail(residentEmail)
                .or(() -> residentRepository.findByEmail(residentEmail))
                .orElse(null);

        if (resident == null || resident.getBuilding() == null) {
            return ResidentComparisonResponse.builder()
                    .householdUsage(0.0)
                    .apartmentAverage(0.0)
                    .similarHouseholdAverage(0.0)
                    .differenceFromApartmentAverage(0.0)
                    .percentageDifference(0.0)
                    .message("Please select an active apartment to view water consumption comparisons.")
                    .build();
        }

        Optional<ApartmentOccupancy> activeOcc = occupancyRepository.findActiveByResidentEmail(residentEmail, ApartmentOccupancy.OccupancyStatus.ACTIVE);
        if (activeOcc.isEmpty() && (resident.getApartmentNumber() == null || resident.getApartmentNumber().startsWith("FORMER") || resident.getApartmentNumber().equals("PENDING"))) {
            return ResidentComparisonResponse.builder()
                    .householdUsage(0.0)
                    .apartmentAverage(0.0)
                    .similarHouseholdAverage(0.0)
                    .differenceFromApartmentAverage(0.0)
                    .percentageDifference(0.0)
                    .message("You do not currently have an active apartment occupancy. Please select an apartment to view usage comparisons.")
                    .build();
        }

        WaterMeter meter = meterRepository.findByResidentResidentId(resident.getResidentId()).orElse(null);
        Double householdUsage = 0.0;
        if (meter != null) {
            List<WaterUsage> usageList = usageRepository.findByMeterMeterIdOrderByYearDescMonthDesc(meter.getMeterId());
            if (!usageList.isEmpty()) {
                householdUsage = usageList.get(0).getUnitsConsumed();
            }
        }

        // Get all residents in the building to calculate building average
        List<Resident> buildingResidents = residentRepository.findByBuildingBuildingId(resident.getBuilding().getBuildingId()).stream()
                .filter(r -> r.getAccountStatus() == Resident.AccountStatus.ACTIVE
                        && r.getApartmentNumber() != null
                        && !r.getApartmentNumber().isBlank()
                        && !r.getApartmentNumber().toUpperCase().startsWith("FORMER-")
                        && !r.getApartmentNumber().equalsIgnoreCase("Vacated")
                        && !r.getApartmentNumber().equalsIgnoreCase("Removed")
                        && !r.getApartmentNumber().equalsIgnoreCase("PENDING")
                        && !r.getApartmentNumber().equalsIgnoreCase("N/A"))
                .collect(Collectors.toList());
        double totalUsage = 0.0;
        int count = 0;

        for (Resident r : buildingResidents) {
            WaterMeter m = meterRepository.findByResidentResidentId(r.getResidentId()).orElse(null);
            if (m != null) {
                List<WaterUsage> uList = usageRepository.findByMeterMeterIdOrderByYearDescMonthDesc(m.getMeterId());
                if (!uList.isEmpty()) {
                    totalUsage += uList.get(0).getUnitsConsumed();
                    count++;
                }
            }
        }

        double apartmentAvg = count > 0 ? Math.round((totalUsage / count) * 100.0) / 100.0 : householdUsage;
        double similarAvg = Math.round((apartmentAvg * 1.02) * 100.0) / 100.0;

        double diff = Math.round((householdUsage - apartmentAvg) * 100.0) / 100.0;
        double pctDiff = apartmentAvg > 0 ? Math.round(((householdUsage - apartmentAvg) / apartmentAvg * 100.0) * 100.0) / 100.0 : 0.0;

        String msg;
        if (householdUsage > apartmentAvg) {
            msg = "Your consumption is above the apartment average. Try reducing your daily water usage to lower your next bill.";
        } else {
            msg = "Great! Your household is using water efficiently compared with the apartment average.";
        }

        return ResidentComparisonResponse.builder()
                .householdUsage(householdUsage)
                .apartmentAverage(apartmentAvg)
                .similarHouseholdAverage(similarAvg)
                .differenceFromApartmentAverage(diff)
                .percentageDifference(pctDiff)
                .message(msg)
                .build();
    }

    @Transactional
    public OwnerUsageComparisonResponse getOwnerUsageComparison(String ownerEmail, Long buildingId) {
        return getOwnerUsageComparison(ownerEmail, buildingId, null);
    }

    @Transactional
    public OwnerUsageComparisonResponse getOwnerUsageComparison(String ownerEmail, Long buildingId, Long billingCycleId) {
        BuildingOwner owner = ownerService.getOwnerByUserEmail(ownerEmail);

        List<Building> buildings = buildingRepository.findByOwnerOwnerId(owner.getOwnerId());
        if (buildings.isEmpty()) {
            return OwnerUsageComparisonResponse.builder()
                    .buildingId(null)
                    .buildingName("N/A")
                    .buildingAverage(0.0)
                    .totalApartments(0)
                    .activeApartments(0)
                    .households(List.of())
                    .build();
        }

        Building chosenBuilding = buildings.get(0);
        if (buildingId != null) {
            for (Building b : buildings) {
                if (b.getBuildingId().equals(buildingId)) {
                    chosenBuilding = b;
                    break;
                }
            }
        }
        final Building selectedBuilding = chosenBuilding;

        // Fetch billing cycles for this building
        List<BillingCycle> cycles = billingCycleRepository.findByBuildingBuildingIdOrderByStartDateDesc(selectedBuilding.getBuildingId());
        BillingCycle selectedCycle = null;
        if (billingCycleId != null) {
            selectedCycle = cycles.stream().filter(c -> c.getId().equals(billingCycleId)).findFirst().orElse(null);
        }
        if (selectedCycle == null && !cycles.isEmpty()) {
            selectedCycle = cycles.get(0);
        }
        Long targetCycleId = selectedCycle != null ? selectedCycle.getId() : null;
        String cycleMonth = selectedCycle != null 
                ? (selectedCycle.getBillingMonth() != null ? selectedCycle.getBillingMonth().toString().substring(0, 7) : selectedCycle.getStartDate().toString()) 
                : "N/A";

        // Pre-fetch cycle usages map if cycle is available
        Map<Long, Double> cycleUsageMap = new HashMap<>();
        if (targetCycleId != null) {
            List<WaterUsage> cycleUsages = usageRepository.findByBillingCycleId(targetCycleId);
            for (WaterUsage u : cycleUsages) {
                if (u.getMeter() != null && u.getUnitsConsumed() != null) {
                    cycleUsageMap.put(u.getMeter().getMeterId(), u.getUnitsConsumed());
                }
            }
        }

        // 1. Fetch configured apartments for this building
        List<Apartment> apartments = apartmentRepository.findByBuildingBuildingIdOrderByFloorNumberAscRoomNumberAsc(selectedBuilding.getBuildingId());
        List<WaterMeter> meters = meterRepository.findByBuildingBuildingId(selectedBuilding.getBuildingId());
        List<Resident> residents = residentRepository.findByBuildingBuildingId(selectedBuilding.getBuildingId());

        List<OwnerHouseholdUsageDTO> list = new ArrayList<>();
        double totalUnits = 0.0;
        int activeCount = 0;

        if (!apartments.isEmpty()) {
            for (Apartment apt : apartments) {
                // Find meter for this apartment
                WaterMeter m = meters.stream()
                        .filter(wm -> wm.getApartment() != null && wm.getApartment().getApartmentId().equals(apt.getApartmentId()))
                        .findFirst()
                        .orElseGet(() -> meters.stream()
                                .filter(wm -> wm.getMeterNumber() != null && (wm.getMeterNumber().endsWith("-" + apt.getRoomNumber()) || wm.getMeterNumber().equalsIgnoreCase("WM-" + selectedBuilding.getBuildingNumber() + "-" + apt.getRoomNumber())))
                                .findFirst()
                                .orElse(null));

                // Find resident for this apartment
                Resident r = null;
                if (m != null && m.getResident() != null) {
                    r = m.getResident();
                } else {
                    r = residents.stream()
                            .filter(res -> apt.getRoomNumber().equalsIgnoreCase(res.getApartmentNumber()))
                            .findFirst()
                            .orElse(null);
                }

                String meterNum = m != null ? m.getMeterNumber() : ("WM-" + selectedBuilding.getBuildingNumber() + "-" + apt.getRoomNumber());
                String residentName = r != null ? r.getName() : "Vacant / Unoccupied";
                Long residentId = r != null ? r.getResidentId() : null;

                double units = 0.0;
                if (m != null) {
                    if (cycleUsageMap.containsKey(m.getMeterId())) {
                        units = cycleUsageMap.get(m.getMeterId());
                    } else if (targetCycleId == null) {
                        List<WaterUsage> uList = usageRepository.findByMeterMeterIdOrderByYearDescMonthDesc(m.getMeterId());
                        if (!uList.isEmpty() && uList.get(0).getUnitsConsumed() != null) {
                            units = uList.get(0).getUnitsConsumed();
                        }
                    }
                }

                if (units > 0.0) {
                    activeCount++;
                }
                totalUnits += units;

                list.add(OwnerHouseholdUsageDTO.builder()
                        .residentId(residentId)
                        .apartmentId(apt.getApartmentId())
                        .floorNumber(apt.getFloorNumber())
                        .apartmentNumber(apt.getRoomNumber())
                        .residentName(residentName)
                        .meterNumber(meterNum)
                        .unitsConsumed(units)
                        .isOccupied(r != null)
                        .build());
            }
        } else {
            // Fallback to residents if apartments have not been initialized yet
            for (Resident r : residents) {
                WaterMeter m = meterRepository.findByResidentResidentId(r.getResidentId()).orElse(null);
                double units = 0.0;
                String meterNum = "N/A";
                if (m != null) {
                    meterNum = m.getMeterNumber();
                    if (cycleUsageMap.containsKey(m.getMeterId())) {
                        units = cycleUsageMap.get(m.getMeterId());
                    } else {
                        List<WaterUsage> usageList = usageRepository.findByMeterMeterIdOrderByYearDescMonthDesc(m.getMeterId());
                        if (!usageList.isEmpty() && usageList.get(0).getUnitsConsumed() != null) {
                            units = usageList.get(0).getUnitsConsumed();
                        }
                    }
                }
                if (units > 0.0) {
                    activeCount++;
                }
                totalUnits += units;

                list.add(OwnerHouseholdUsageDTO.builder()
                        .residentId(r.getResidentId())
                        .apartmentNumber(r.getApartmentNumber())
                        .residentName(r.getName())
                        .meterNumber(meterNum)
                        .unitsConsumed(units)
                        .isOccupied(true)
                        .build());
            }
        }

        // Calculate building average based on active consuming apartments if available, else count
        int denom = activeCount > 0 ? activeCount : (list.isEmpty() ? 1 : list.size());
        double bAvg = denom > 0 ? Math.round((totalUnits / denom) * 100.0) / 100.0 : 0.0;

        for (OwnerHouseholdUsageDTO item : list) {
            item.setApartmentAverage(bAvg);
            item.setStatus(item.getUnitsConsumed() > bAvg ? "HIGH" : "NORMAL");
        }

        return OwnerUsageComparisonResponse.builder()
                .buildingId(selectedBuilding.getBuildingId())
                .buildingName(selectedBuilding.getBuildingName())
                .buildingAverage(bAvg)
                .billingCycleId(targetCycleId)
                .billingMonth(cycleMonth)
                .totalApartments(list.size())
                .activeApartments(activeCount)
                .households(list)
                .build();
    }

    public ResidentResponse convertToResidentResponse(Resident resident) {
        WaterMeter meter = meterRepository.findByResidentResidentId(resident.getResidentId()).orElse(null);
        String meterNumber = meter != null ? meter.getMeterNumber() : "N/A";
        Long meterId = meter != null ? meter.getMeterId() : null;
        Long buildingId = resident.getBuilding() != null ? resident.getBuilding().getBuildingId() : null;
        String buildingName = resident.getBuilding() != null ? resident.getBuilding().getBuildingName() : "N/A";

        return ResidentResponse.builder()
                .residentId(resident.getResidentId())
                .buildingId(buildingId)
                .buildingName(buildingName)
                .name(resident.getName())
                .email(resident.getEmail())
                .phone(resident.getPhone())
                .apartmentNumber(resident.getApartmentNumber())
                .invitationToken(resident.getInvitationToken())
                .invitationStatus(resident.getInvitationStatus() != null ? resident.getInvitationStatus().name() : "PENDING")
                .accountStatus(resident.getAccountStatus() != null ? resident.getAccountStatus().name() : "INACTIVE")
                .meterId(meterId)
                .meterNumber(meterNumber)
                .emailSent(false)
                .createdAt(resident.getCreatedAt())
                .build();
    }
}
