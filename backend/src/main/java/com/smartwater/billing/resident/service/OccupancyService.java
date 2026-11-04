package com.smartwater.billing.resident.service;

import com.smartwater.billing.owner.entity.Apartment;
import com.smartwater.billing.owner.entity.Building;
import com.smartwater.billing.owner.entity.BuildingOwner;
import com.smartwater.billing.resident.entity.ApartmentOccupancy;
import com.smartwater.billing.resident.entity.ApartmentOccupancy.OccupancyStatus;
import com.smartwater.billing.resident.entity.Notification;
import com.smartwater.billing.resident.entity.Resident;
import com.smartwater.billing.resident.entity.ResidentInvitation;
import com.smartwater.billing.common.entity.Bill;
import com.smartwater.billing.common.entity.User;
import com.smartwater.billing.common.entity.WaterMeter;
import com.smartwater.billing.common.exception.BadRequestException;
import com.smartwater.billing.common.exception.ResourceNotFoundException;
import com.smartwater.billing.common.repository.BillRepository;
import com.smartwater.billing.owner.repository.ApartmentRepository;
import com.smartwater.billing.resident.repository.ApartmentOccupancyRepository;
import com.smartwater.billing.resident.repository.NotificationRepository;
import com.smartwater.billing.resident.repository.ResidentInvitationRepository;
import com.smartwater.billing.resident.repository.ResidentRepository;
import com.smartwater.billing.common.repository.UserRepository;
import com.smartwater.billing.common.repository.WaterMeterRepository;
import com.smartwater.billing.resident.dto.OccupancyDTOs.*;
import com.smartwater.billing.owner.repository.BuildingOwnerRepository;
import com.smartwater.billing.resident.entity.OccupancyRequest;
import com.smartwater.billing.resident.entity.OccupancyRequest.RequestStatus;
import com.smartwater.billing.resident.repository.OccupancyRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import com.smartwater.billing.common.config.HouseholdProperties;
import com.smartwater.billing.resident.entity.HouseholdSizeHistory;
import com.smartwater.billing.resident.repository.HouseholdSizeHistoryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@Service
public class OccupancyService {

    @PersistenceContext
    private EntityManager entityManager;

    private final ApartmentOccupancyRepository occupancyRepository;
    private final ApartmentRepository apartmentRepository;
    private final ResidentRepository residentRepository;
    private final ResidentInvitationRepository invitationRepository;
    private final WaterMeterRepository waterMeterRepository;
    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;
    private final BuildingOwnerRepository ownerRepository;
    private final OccupancyRequestRepository occupancyRequestRepository;
    private final BillRepository billRepository;
    private final HouseholdSizeHistoryRepository historyRepository;
    private final HouseholdProperties householdProperties;

    public OccupancyService(
            ApartmentOccupancyRepository occupancyRepository,
            ApartmentRepository apartmentRepository,
            ResidentRepository residentRepository,
            ResidentInvitationRepository invitationRepository,
            WaterMeterRepository waterMeterRepository,
            UserRepository userRepository,
            NotificationRepository notificationRepository,
            BuildingOwnerRepository ownerRepository,
            OccupancyRequestRepository occupancyRequestRepository,
            BillRepository billRepository,
            HouseholdSizeHistoryRepository historyRepository,
            HouseholdProperties householdProperties
    ) {
        this.occupancyRepository = occupancyRepository;
        this.apartmentRepository = apartmentRepository;
        this.residentRepository = residentRepository;
        this.invitationRepository = invitationRepository;
        this.waterMeterRepository = waterMeterRepository;
        this.userRepository = userRepository;
        this.notificationRepository = notificationRepository;
        this.ownerRepository = ownerRepository;
        this.occupancyRequestRepository = occupancyRequestRepository;
        this.billRepository = billRepository;
        this.historyRepository = historyRepository;
        this.householdProperties = householdProperties;
    }

    private User validateResidentUser(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + email));
        if (user.getRole() != User.Role.RESIDENT && user.getRole() != User.Role.SUPER_ADMIN) {
            throw new BadRequestException("Access denied: Only residents may claim apartment occupancy.");
        }
        return user;
    }

    private Resident getOrCreateResidentProfile(User user) {
        return getOrCreateResidentProfile(user, null);
    }

    private Resident getOrCreateResidentProfile(User user, Building building) {
        Resident resident = residentRepository.findByUser(user)
                .or(() -> residentRepository.findByUserEmail(user.getEmail()))
                .or(() -> residentRepository.findByEmailIgnoreCase(user.getEmail()))
                .orElse(null);

        if (resident == null) {
            resident = Resident.builder()
                    .user(user)
                    .name(user.getName() != null ? user.getName() : "Resident")
                    .email(user.getEmail())
                    .phone(user.getEmail())
                    .apartmentNumber("PENDING")
                    .building(building)
                    .invitationStatus(Resident.InvitationStatus.ACCEPTED)
                    .accountStatus(Resident.AccountStatus.ACTIVE)
                    .createdAt(LocalDateTime.now())
                    .build();
            resident = residentRepository.save(resident);
        } else {
            boolean updated = false;
            if (resident.getUser() == null) {
                resident.setUser(user);
                resident.setAccountStatus(Resident.AccountStatus.ACTIVE);
                updated = true;
            }
            if (resident.getBuilding() == null && building != null) {
                resident.setBuilding(building);
                updated = true;
            }
            if (updated) {
                resident = residentRepository.save(resident);
            }
        }
        return resident;
    }

    @Transactional
    public OccupancyResponse claimApartment(String residentEmail, Long apartmentId) {
        User user = validateResidentUser(residentEmail);
        Resident resident = getOrCreateResidentProfile(user);

        // Check if resident already has an active occupancy
        Optional<ApartmentOccupancy> existingActive = occupancyRepository.findActiveByResidentEmail(user.getEmail(), OccupancyStatus.ACTIVE);
        if (existingActive.isPresent()) {
            ApartmentOccupancy curr = existingActive.get();
            throw new BadRequestException("You are already actively occupying " + curr.getApartment().getApartmentName() 
                    + " in " + curr.getApartment().getBuilding().getBuildingName() 
                    + ". Please complete the Leave Apartment / Move-Out process before claiming a new unit.");
        }

        // Fetch apartment
        Apartment apartment = apartmentRepository.findById(apartmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Apartment unit not found with id: " + apartmentId));

        Building building = apartment.getBuilding();

        // Concurrency safeguard: ensure apartment is not occupied
        Optional<ApartmentOccupancy> activeOcc = occupancyRepository.findFirstByApartmentApartmentIdAndStatus(apartmentId, OccupancyStatus.ACTIVE);
        if (activeOcc.isPresent()) {
            throw new BadRequestException("This apartment (" + apartment.getApartmentName() + ") is already occupied by another resident.");
        }

        // Check if unit is locked under a valid pending invitation for another email
        LocalDateTime now = LocalDateTime.now();
        List<ResidentInvitation> pendingInvites = invitationRepository.findByBuildingBuildingIdAndStatus(building.getBuildingId(), ResidentInvitation.InvitationStatus.PENDING);
        for (ResidentInvitation inv : pendingInvites) {
            boolean matchesUnit = (inv.getApartment() != null && inv.getApartment().getApartmentId().equals(apartmentId))
                    || (inv.getApartmentNumber() != null && inv.getApartmentNumber().equalsIgnoreCase(apartment.getRoomNumber()));
            if (matchesUnit && inv.getExpiresAt() != null && inv.getExpiresAt().isAfter(now)) {
                if (!inv.getResidentEmail().equalsIgnoreCase(user.getEmail())) {
                    throw new BadRequestException("This apartment is currently reserved under a pending owner invitation for another tenant.");
                } else {
                    // Mark matching invitation as accepted
                    inv.setStatus(ResidentInvitation.InvitationStatus.ACCEPTED);
                    inv.setAcceptedAt(now);
                    invitationRepository.save(inv);
                }
            }
        }

        // Establish active occupancy
        ApartmentOccupancy occupancy = ApartmentOccupancy.builder()
                .apartment(apartment)
                .resident(resident)
                .startDate(now)
                .status(OccupancyStatus.ACTIVE)
                .build();
        occupancy = occupancyRepository.save(occupancy);

        // Update Resident profile
        resident.setBuilding(building);
        resident.setApartmentNumber(apartment.getRoomNumber());
        resident.setAccountStatus(Resident.AccountStatus.ACTIVE);
        if (resident.getUser() != null) {
            resident.getUser().setStatus(User.UserStatus.ACTIVE);
            userRepository.save(resident.getUser());
        }
        resident = residentRepository.save(resident);

        // Audit log in resident_history
        try {
            BuildingOwner bOwner = building.getOwner();
            if (bOwner != null) {
                entityManager.createNativeQuery(
                        "INSERT INTO resident_history (owner_id, building_id, building_name, apartment_id, apartment_name, room_number, resident_id, resident_name, resident_email, resident_phone, activity_type, details, actor, timestamp) " +
                        "VALUES (:oId, :bId, :bName, :aId, :aName, :room, :resId, :resName, :resEmail, :resPhone, 'JOINED', :details, :actor, NOW())"
                )
                .setParameter("oId", bOwner.getOwnerId())
                .setParameter("bId", building.getBuildingId())
                .setParameter("bName", building.getBuildingName())
                .setParameter("aId", apartment.getApartmentId())
                .setParameter("aName", apartment.getApartmentName())
                .setParameter("room", apartment.getRoomNumber())
                .setParameter("resId", resident.getResidentId())
                .setParameter("resName", resident.getName())
                .setParameter("resEmail", resident.getEmail())
                .setParameter("resPhone", resident.getPhone())
                .setParameter("details", "Resident claimed and occupied " + apartment.getApartmentName())
                .setParameter("actor", resident.getName())
                .executeUpdate();
            }
        } catch (Exception ignored) {}

        // Associate physical WaterMeter with the resident and apartment
        String mNum = "WM-" + building.getBuildingNumber() + "-" + apartment.getRoomNumber();
        WaterMeter meter = waterMeterRepository.findByBuildingBuildingId(building.getBuildingId()).stream()
                .filter(m -> (m.getApartment() != null && m.getApartment().getApartmentId().equals(apartment.getApartmentId()))
                        || (m.getMeterNumber() != null && m.getMeterNumber().equalsIgnoreCase(mNum)))
                .findFirst()
                .orElse(null);

        if (meter == null) {
            meter = WaterMeter.builder()
                    .building(building)
                    .apartment(apartment)
                    .resident(resident)
                    .meterNumber(mNum)
                    .installationDate(LocalDate.now())
                    .status(WaterMeter.MeterStatus.ACTIVE)
                    .build();
            waterMeterRepository.save(meter);
        } else {
            meter.setBuilding(building);
            meter.setApartment(apartment);
            meter.setResident(resident);
            meter.setStatus(WaterMeter.MeterStatus.ACTIVE);
            waterMeterRepository.save(meter);
        }

        // Create Welcome In-App Notification
        Notification notif = Notification.builder()
                .resident(resident)
                .title("Welcome to Your New Home!")
                .message("You are now registered as active occupant of " + apartment.getApartmentName() + " (" + building.getBuildingName() + "). Water meter: " + meter.getMeterNumber() + ".")
                .build();
        notificationRepository.save(notif);

        return convertToOccupancyResponse(occupancy, meter != null ? meter.getMeterNumber() : "N/A");
    }

    @Transactional
    public MoveOutResponse moveOut(String residentEmail, MoveOutRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.getConfirm())) {
            throw new BadRequestException("You must explicitly confirm the move-out request.");
        }

        User user = validateResidentUser(residentEmail);
        Optional<ApartmentOccupancy> activeOpt = occupancyRepository.findActiveByResidentEmail(user.getEmail(), OccupancyStatus.ACTIVE);

        Resident resident;
        Apartment apartment;
        Building building;
        LocalDateTime moveOutTime = LocalDateTime.now();

        if (activeOpt.isPresent()) {
            ApartmentOccupancy occupancy = activeOpt.get();
            resident = occupancy.getResident();
            apartment = occupancy.getApartment();
            building = apartment.getBuilding();
        } else {
            // Check legacy resident profile
            resident = residentRepository.findByEmailIgnoreCase(user.getEmail())
                    .orElseThrow(() -> new BadRequestException("No active apartment occupancy found to move out from."));

            if (resident.getBuilding() == null || resident.getApartmentNumber() == null || resident.getApartmentNumber().startsWith("FORMER-")) {
                throw new BadRequestException("No active apartment occupancy found to move out from.");
            }

            building = resident.getBuilding();
            String roomNum = resident.getApartmentNumber();

            // Find or create physical apartment unit so vacancy is properly recorded
            apartment = apartmentRepository.findByBuildingBuildingIdAndRoomNumber(building.getBuildingId(), roomNum)
                    .orElseGet(() -> {
                        Apartment newApt = Apartment.builder()
                                .building(building)
                                .floorNumber(1)
                                .apartmentName(roomNum)
                                .roomNumber(roomNum)
                                .unitType("2BHK")
                                .build();
                        return apartmentRepository.save(newApt);
                    });
        }

        // GUARD: Resident cannot leave the apartment if they have any pending / unpaid bills
        List<Bill> pendingBills = billRepository.findByResidentResidentIdOrderByGeneratedAtDesc(resident.getResidentId()).stream()
                .filter(b -> b.getStatus() != Bill.BillStatus.PAID)
                .collect(Collectors.toList());

        if (!pendingBills.isEmpty()) {
            double totalDue = pendingBills.stream().mapToDouble(b -> b.getAmount() != null ? b.getAmount() : 0.0).sum();
            throw new BadRequestException("Cannot leave apartment: You have " + pendingBills.size() + " unpaid water bill(s) totaling $"
                    + String.format("%.2f", totalDue) + ". All pending bills must be cleared before moving out.");
        }

        // 1. End Occupancy
        if (activeOpt.isPresent()) {
            ApartmentOccupancy occupancy = activeOpt.get();
            occupancy.setStatus(OccupancyStatus.ENDED);
            occupancy.setEndDate(moveOutTime);
            occupancy.setMoveOutReason(request.getReason() != null ? request.getReason().trim() : "Resident voluntary move-out");
            occupancyRepository.save(occupancy);
        } else {
            // Create ended occupancy record for audit trail
            ApartmentOccupancy endedRecord = ApartmentOccupancy.builder()
                    .apartment(apartment)
                    .resident(resident)
                    .startDate(resident.getCreatedAt() != null ? resident.getCreatedAt() : moveOutTime.minusMonths(1))
                    .endDate(moveOutTime)
                    .status(OccupancyStatus.ENDED)
                    .moveOutReason(request.getReason() != null ? request.getReason().trim() : "Resident voluntary move-out")
                    .build();
            occupancyRepository.save(endedRecord);
        }


        // 2. Detach resident from Water Meter fixture, but preserve meter in building & apartment
        WaterMeter meter = waterMeterRepository.findByResidentResidentId(resident.getResidentId()).orElse(null);
        if (meter != null) {
            meter.setResident(null);
            waterMeterRepository.save(meter);
        }

        // 3. Update resident profile active apartment reference to reflect no active occupancy
        resident.setApartmentNumber("FORMER-" + apartment.getRoomNumber());
        residentRepository.save(resident);

        // Audit log in resident_history
        try {
            BuildingOwner bOwner = building.getOwner();
            if (bOwner != null) {
                String reason = request.getReason() != null ? request.getReason().trim() : "Resident voluntary move-out";
                entityManager.createNativeQuery(
                        "INSERT INTO resident_history (owner_id, building_id, building_name, apartment_id, apartment_name, room_number, resident_id, resident_name, resident_email, resident_phone, activity_type, details, actor, timestamp) " +
                        "VALUES (:oId, :bId, :bName, :aId, :aName, :room, :resId, :resName, :resEmail, :resPhone, 'LEFT', :details, :actor, NOW())"
                )
                .setParameter("oId", bOwner.getOwnerId())
                .setParameter("bId", building.getBuildingId())
                .setParameter("bName", building.getBuildingName())
                .setParameter("aId", apartment.getApartmentId())
                .setParameter("aName", apartment.getApartmentName())
                .setParameter("room", apartment.getRoomNumber())
                .setParameter("resId", resident.getResidentId())
                .setParameter("resName", resident.getName())
                .setParameter("resEmail", resident.getEmail())
                .setParameter("resPhone", resident.getPhone())
                .setParameter("details", "Voluntary Move-Out: " + reason)
                .setParameter("actor", resident.getName() + " (Resident)")
                .executeUpdate();
            }
        } catch (Exception ignored) {}

        // 4. Send Notification
        Notification notif = Notification.builder()
                .resident(resident)
                .title("Move-Out Confirmation")
                .message("You have successfully moved out of " + apartment.getApartmentName() + " (" + building.getBuildingName() + "). Historical invoices, readings, and billing records remain saved in your account.")
                .build();
        notificationRepository.save(notif);

        return new MoveOutResponse(
                true,
                "Move-out completed successfully. Apartment " + apartment.getApartmentName() + " is now vacant.",
                building.getBuildingName(),
                apartment.getApartmentName(),
                apartment.getRoomNumber(),
                moveOutTime
        );
    }

    @Transactional(readOnly = true)
    public OccupancyResponse getCurrentOccupancy(String residentEmail) {
        validateResidentUser(residentEmail);
        ApartmentOccupancy occupancy = occupancyRepository.findActiveByResidentEmail(residentEmail, OccupancyStatus.ACTIVE)
                .orElse(null);

        if (occupancy != null) {
            WaterMeter meter = waterMeterRepository.findByResidentResidentId(occupancy.getResident().getResidentId()).orElse(null);
            return convertToOccupancyResponse(occupancy, meter != null ? meter.getMeterNumber() : "N/A");
        }

        // Check legacy resident profile
        Resident resident = residentRepository.findByEmailIgnoreCase(residentEmail).orElse(null);
        if (resident != null && resident.getBuilding() != null && resident.getApartmentNumber() != null 
                && !resident.getApartmentNumber().startsWith("FORMER-")
                && !resident.getApartmentNumber().equalsIgnoreCase("PENDING")) {
            Building b = resident.getBuilding();
            BuildingOwner owner = b.getOwner();
            String ownerName = owner != null && owner.getUser() != null ? owner.getUser().getName() : (owner != null ? owner.getName() : "Building Owner");
            WaterMeter meter = waterMeterRepository.findByResidentResidentId(resident.getResidentId()).orElse(null);

            return OccupancyResponse.builder()
                    .apartmentName(resident.getApartmentNumber())
                    .roomNumber(resident.getApartmentNumber())
                    .floorNumber(1)
                    .unitType("2BHK")
                    .buildingId(b.getBuildingId())
                    .buildingName(b.getBuildingName())
                    .buildingNumber(b.getBuildingNumber())
                    .buildingLocation(b.getLocation())
                    .ownerName(ownerName)
                    .meterNumber(meter != null ? meter.getMeterNumber() : "N/A")
                    .startDate(resident.getCreatedAt())
                    .status("ACTIVE")
                    .build();
        }

        return null;
    }

    @Transactional(readOnly = true)
    public List<OccupancyResponse> getOccupancyHistory(String residentEmail) {
        validateResidentUser(residentEmail);
        List<ApartmentOccupancy> occupancies = occupancyRepository.findAllByResidentEmail(residentEmail);

        return occupancies.stream()
                .map(occ -> {
                    String mNum = "WM-" + occ.getApartment().getBuilding().getBuildingNumber() + "-" + occ.getApartment().getRoomNumber();
                    return convertToOccupancyResponse(occ, mNum);
                })
                .collect(Collectors.toList());
    }

    private OccupancyResponse convertToOccupancyResponse(ApartmentOccupancy occ, String meterNumber) {
        Apartment apt = occ.getApartment();
        Building b = apt.getBuilding();
        BuildingOwner owner = b.getOwner();
        String ownerName = owner != null && owner.getUser() != null ? owner.getUser().getName() : (owner != null ? owner.getName() : "Building Owner");

        return OccupancyResponse.builder()
                .occupancyId(occ.getOccupancyId())
                .apartmentId(apt.getApartmentId())
                .apartmentName(apt.getApartmentName())
                .roomNumber(apt.getRoomNumber())
                .floorNumber(apt.getFloorNumber())
                .unitType(apt.getUnitType())
                .buildingId(b.getBuildingId())
                .buildingName(b.getBuildingName())
                .buildingNumber(b.getBuildingNumber())
                .buildingLocation(b.getLocation())
                .ownerName(ownerName)
                .meterNumber(meterNumber)
                .startDate(occ.getStartDate())
                .endDate(occ.getEndDate())
                .status(occ.getStatus().name())
                .notes(occ.getMoveOutReason())
                .build();
    }

    @Transactional
    public OccupancyRequestResponse submitOccupancyRequest(String residentEmail, SubmitOccupancyRequest req) {
        User user = validateResidentUser(residentEmail);

        // 1. Check target apartment first
        Apartment apt = apartmentRepository.findById(req.getApartmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Apartment unit not found with id: " + req.getApartmentId()));

        Building building = apt.getBuilding();
        if (building == null || building.getOwner() == null || building.getOwner().getApprovalStatus() != BuildingOwner.ApprovalStatus.APPROVED) {
            throw new BadRequestException("Cannot request apartment occupancy: The property building owner has not been approved by the Super Admin.");
        }

        // 2. Resolve or create resident profile with apartment building context
        Resident resident = getOrCreateResidentProfile(user, apt.getBuilding());

        // 3. Check if resident already has an active occupancy
        Optional<ApartmentOccupancy> existingActive = occupancyRepository.findActiveByResidentEmail(user.getEmail(), OccupancyStatus.ACTIVE);
        if (existingActive.isPresent()) {
            throw new BadRequestException("You are already actively occupying an apartment. Please complete move-out before requesting a new unit.");
        }

        // 4. Check if resident already has an active pending request
        Optional<OccupancyRequest> existingReq = occupancyRequestRepository.findActiveRequestByResidentId(resident.getResidentId(), RequestStatus.PENDING);
        if (existingReq.isPresent()) {
            OccupancyRequest prev = existingReq.get();
            throw new BadRequestException("You already have a pending occupancy request for " + prev.getApartment().getApartmentName()
                    + " (" + prev.getApartment().getBuilding().getBuildingName() + "). Please wait for owner review or cancel your current request.");
        }

        // Check if apartment is currently occupied
        Optional<ApartmentOccupancy> occupied = occupancyRepository.findFirstByApartmentApartmentIdAndStatus(apt.getApartmentId(), OccupancyStatus.ACTIVE);
        if (occupied.isPresent()) {
            throw new BadRequestException("This apartment unit (" + apt.getApartmentName() + ") is already occupied.");
        }

        // Check if another resident has an active pending request on this unit
        Optional<OccupancyRequest> aptPending = occupancyRequestRepository.findActiveRequestByApartmentId(apt.getApartmentId(), RequestStatus.PENDING);
        if (aptPending.isPresent()) {
            throw new BadRequestException("This apartment unit already has a pending occupancy request under review.");
        }

        OccupancyRequest request = OccupancyRequest.builder()
                .apartment(apt)
                .resident(resident)
                .status(RequestStatus.PENDING)
                .requestDate(LocalDateTime.now())
                .notes(req.getNotes() != null ? req.getNotes().trim() : null)
                .householdSize(req.getHouseholdSize())
                .childrenCount(req.getChildrenCount())
                .build();

        request = occupancyRequestRepository.save(request);

        // Send confirmation notification to resident
        Notification notif = Notification.builder()
                .resident(resident)
                .title("Occupancy Request Submitted")
                .message("Your request to occupy " + apt.getApartmentName() + " (" + apt.getBuilding().getBuildingName() + ") has been sent to the building owner for review.")
                .build();
        notificationRepository.save(notif);

        return convertToOccupancyRequestResponse(request);
    }

    @Transactional
    public OccupancyRequestResponse cancelOccupancyRequest(String residentEmail, Long requestId) {
        User user = validateResidentUser(residentEmail);
        Resident resident = getOrCreateResidentProfile(user);

        OccupancyRequest request = occupancyRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Occupancy request not found with id: " + requestId));

        if (!request.getResident().getResidentId().equals(resident.getResidentId())) {
            throw new BadRequestException("Unauthorized: This request does not belong to your account.");
        }

        if (request.getStatus() != RequestStatus.PENDING) {
            throw new BadRequestException("Only pending requests can be cancelled. Current status is " + request.getStatus() + ".");
        }

        request.setStatus(RequestStatus.CANCELLED);
        request.setReviewedAt(LocalDateTime.now());
        request.setNotes("Cancelled by resident");
        request = occupancyRequestRepository.save(request);

        return convertToOccupancyRequestResponse(request);
    }

    @Transactional(readOnly = true)
    public OccupancyRequestResponse getResidentPendingRequest(String residentEmail) {
        User user = validateResidentUser(residentEmail);
        Resident resident = residentRepository.findByEmailIgnoreCase(user.getEmail()).orElse(null);
        if (resident == null) return null;

        return occupancyRequestRepository.findActiveRequestByResidentId(resident.getResidentId(), RequestStatus.PENDING)
                .map(this::convertToOccupancyRequestResponse)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<OccupancyRequestResponse> getAllResidentRequests(String residentEmail) {
        User user = validateResidentUser(residentEmail);
        Resident resident = residentRepository.findByEmailIgnoreCase(user.getEmail()).orElse(null);
        if (resident == null) return Collections.emptyList();

        return occupancyRequestRepository.findAllByResidentId(resident.getResidentId()).stream()
                .map(this::convertToOccupancyRequestResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<OccupancyRequestResponse> getOwnerOccupancyRequests(String ownerEmail, RequestStatus status) {
        User user = userRepository.findByEmail(ownerEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + ownerEmail));

        List<OccupancyRequest> requests;
        if (user.getRole() == User.Role.SUPER_ADMIN) {
            requests = (status != null)
                    ? occupancyRequestRepository.findAll().stream().filter(r -> r.getStatus() == status).collect(Collectors.toList())
                    : occupancyRequestRepository.findAll();
        } else {
            BuildingOwner owner = ownerRepository.findByUser(user)
                    .orElseThrow(() -> new ResourceNotFoundException("Owner profile not found"));
            requests = (status != null)
                    ? occupancyRequestRepository.findAllByOwnerIdAndStatus(owner.getOwnerId(), status)
                    : occupancyRequestRepository.findAllByOwnerId(owner.getOwnerId());
        }

        return requests.stream()
                .map(this::convertToOccupancyRequestResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public OccupancyRequestResponse reviewOccupancyRequest(String ownerEmail, Long requestId, ReviewOccupancyRequest reviewReq) {
        if (reviewReq == null || reviewReq.getApproved() == null) {
            throw new BadRequestException("Review decision (approved true/false) is required.");
        }

        User ownerUser = userRepository.findByEmail(ownerEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + ownerEmail));

        OccupancyRequest request = occupancyRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Occupancy request not found with id: " + requestId));

        Apartment apartment = request.getApartment();
        Building building = apartment.getBuilding();

        // 1. Verify owner authorization
        if (ownerUser.getRole() != User.Role.SUPER_ADMIN) {
            BuildingOwner owner = ownerRepository.findByUser(ownerUser)
                    .orElseThrow(() -> new ResourceNotFoundException("Owner profile not found"));
            if (!building.getOwner().getOwnerId().equals(owner.getOwnerId())) {
                throw new BadRequestException("Unauthorized access: You do not own building '" + building.getBuildingName() + "'!");
            }
        }

        // 2. Verify request is still pending
        if (request.getStatus() != RequestStatus.PENDING) {
            throw new BadRequestException("This request has already been processed (Current status: " + request.getStatus() + ").");
        }

        Resident resident = request.getResident();
        LocalDateTime now = LocalDateTime.now();

        if (Boolean.TRUE.equals(reviewReq.getApproved())) {
            // 3. Verify resident is still eligible (not already in active occupancy elsewhere)
            Optional<ApartmentOccupancy> existingActive = occupancyRepository.findActiveByResidentEmail(resident.getEmail(), OccupancyStatus.ACTIVE);
            if (existingActive.isPresent()) {
                throw new BadRequestException("Resident already occupies another unit (" + existingActive.get().getApartment().getApartmentName() + "). Cannot approve.");
            }

            // 4. Verify apartment unit is still vacant
            Optional<ApartmentOccupancy> activeOcc = occupancyRepository.findFirstByApartmentApartmentIdAndStatus(apartment.getApartmentId(), OccupancyStatus.ACTIVE);
            if (activeOcc.isPresent()) {
                throw new BadRequestException("This apartment unit (" + apartment.getApartmentName() + ") is no longer vacant.");
            }

            // 5. Establish active occupancy
            Integer hhSize = request.getHouseholdSize();
            Integer chCount = request.getChildrenCount() != null ? request.getChildrenCount() : 0;
            String hhSource = "RESIDENT_DECLARED";
            boolean hhConfirmed = true;

            if (hhSize == null || hhSize < 1) {
                hhSize = householdProperties.getDefaultSizeForUnitType(apartment.getUnitType());
                chCount = 0;
                hhSource = "DEFAULT_ESTIMATE";
                hhConfirmed = false;
            }

            ApartmentOccupancy occupancy = ApartmentOccupancy.builder()
                    .apartment(apartment)
                    .resident(resident)
                    .startDate(now)
                    .status(OccupancyStatus.ACTIVE)
                    .householdSize(hhSize)
                    .childrenCount(chCount)
                    .householdSizeSource(hhSource)
                    .householdSizeConfirmed(hhConfirmed)
                    .build();
            occupancy = occupancyRepository.save(occupancy);

            // Record initial HouseholdSizeHistory row
            boolean sanityWarning = hhSize > householdProperties.getLimits().getSanityWarningThreshold();
            HouseholdSizeHistory history = HouseholdSizeHistory.builder()
                    .occupancy(occupancy)
                    .apartment(apartment)
                    .householdSize(hhSize)
                    .childrenCount(chCount)
                    .effectiveFrom(now.toLocalDate())
                    .effectiveTo(null)
                    .changedByUser(ownerUser)
                    .changedByRole("BUILDING_OWNER")
                    .reason("MOVE_IN")
                    .note("Move-in via approved occupancy request")
                    .sanityWarning(sanityWarning)
                    .status("ACTIVE")
                    .createdAt(now)
                    .build();
            historyRepository.save(history);

            // 6. Connect resident to building and room
            resident.setBuilding(building);
            resident.setApartmentNumber(apartment.getRoomNumber());
            resident.setAccountStatus(Resident.AccountStatus.ACTIVE);
            if (resident.getUser() != null) {
                resident.getUser().setStatus(User.UserStatus.ACTIVE);
                userRepository.save(resident.getUser());
            }
            residentRepository.save(resident);

            // Audit log in resident_history
            try {
                BuildingOwner bOwner = building.getOwner();
                if (bOwner != null) {
                    entityManager.createNativeQuery(
                            "INSERT INTO resident_history (owner_id, building_id, building_name, apartment_id, apartment_name, room_number, resident_id, resident_name, resident_email, resident_phone, activity_type, details, actor, timestamp) " +
                            "VALUES (:oId, :bId, :bName, :aId, :aName, :room, :resId, :resName, :resEmail, :resPhone, 'JOINED', :details, :actor, NOW())"
                    )
                    .setParameter("oId", bOwner.getOwnerId())
                    .setParameter("bId", building.getBuildingId())
                    .setParameter("bName", building.getBuildingName())
                    .setParameter("aId", apartment.getApartmentId())
                    .setParameter("aName", apartment.getApartmentName())
                    .setParameter("room", apartment.getRoomNumber())
                    .setParameter("resId", resident.getResidentId())
                    .setParameter("resName", resident.getName())
                    .setParameter("resEmail", resident.getEmail())
                    .setParameter("resPhone", resident.getPhone())
                    .setParameter("details", "Occupancy request approved. Moved into " + apartment.getApartmentName())
                    .setParameter("actor", ownerUser.getName())
                    .executeUpdate();
                }
            } catch (Exception ignored) {}

            // 7. Connect physical water meter
            String mNum = "WM-" + building.getBuildingNumber() + "-" + apartment.getRoomNumber();
            WaterMeter meter = waterMeterRepository.findByBuildingBuildingId(building.getBuildingId()).stream()
                    .filter(m -> (m.getApartment() != null && m.getApartment().getApartmentId().equals(apartment.getApartmentId()))
                            || (m.getMeterNumber() != null && m.getMeterNumber().equalsIgnoreCase(mNum)))
                    .findFirst()
                    .orElse(null);

            if (meter == null) {
                meter = WaterMeter.builder()
                        .building(building)
                        .apartment(apartment)
                        .resident(resident)
                        .meterNumber(mNum)
                        .installationDate(LocalDate.now())
                        .status(WaterMeter.MeterStatus.ACTIVE)
                        .build();
                waterMeterRepository.save(meter);
            } else {
                meter.setBuilding(building);
                meter.setApartment(apartment);
                meter.setResident(resident);
                meter.setStatus(WaterMeter.MeterStatus.ACTIVE);
                waterMeterRepository.save(meter);
            }

            // 8. Update request status
            request.setStatus(RequestStatus.APPROVED);
            request.setReviewedAt(now);
            request.setReviewedBy(ownerUser.getName());
            request = occupancyRequestRepository.save(request);

            // 9. Reject any other competing pending requests for this same unit
            List<OccupancyRequest> otherRequests = occupancyRequestRepository.findAll().stream()
                    .filter(r -> r.getStatus() == RequestStatus.PENDING && r.getApartment().getApartmentId().equals(apartment.getApartmentId()) && !r.getRequestId().equals(requestId))
                    .collect(Collectors.toList());
            for (OccupancyRequest other : otherRequests) {
                other.setStatus(RequestStatus.REJECTED);
                other.setReviewedAt(now);
                other.setReviewedBy(ownerUser.getName());
                other.setRejectionReason("Apartment occupied by another approved resident.");
                occupancyRequestRepository.save(other);

                Resident otherRes = other.getResident();
                if (otherRes != null) {
                    otherRes.setApartmentNumber("REJECTED");
                    otherRes.setAccountStatus(Resident.AccountStatus.INACTIVE);
                    if (otherRes.getUser() != null) {
                        otherRes.getUser().setStatus(User.UserStatus.REJECTED);
                        userRepository.save(otherRes.getUser());
                    }
                    residentRepository.save(otherRes);
                }
            }

            // 10. Send resident approval notification
            Notification notif = Notification.builder()
                    .resident(resident)
                    .title("Occupancy Request Approved!")
                    .message("Congratulations! Your occupancy request for " + apartment.getApartmentName() + " in " + building.getBuildingName() + " has been approved! Water Meter: " + meter.getMeterNumber())
                    .build();
            notificationRepository.save(notif);

        } else {
            // Rejection flow
            String reason = reviewReq.getReason() != null && !reviewReq.getReason().trim().isEmpty()
                    ? reviewReq.getReason().trim()
                    : "Request declined by building owner.";

            request.setStatus(RequestStatus.REJECTED);
            request.setReviewedAt(now);
            request.setReviewedBy(ownerUser.getName());
            request.setRejectionReason(reason);
            request = occupancyRequestRepository.save(request);

            if (resident != null) {
                resident.setApartmentNumber("REJECTED");
                resident.setAccountStatus(Resident.AccountStatus.INACTIVE);
                if (resident.getUser() != null) {
                    resident.getUser().setStatus(User.UserStatus.REJECTED);
                    userRepository.save(resident.getUser());
                }
                residentRepository.save(resident);
            }

            // Send resident rejection notification
            Notification notif = Notification.builder()
                    .resident(resident)
                    .title("Occupancy Request Declined")
                    .message("Your occupancy request for " + apartment.getApartmentName() + " in " + building.getBuildingName() + " was declined. Reason: " + reason)
                    .build();
            notificationRepository.save(notif);
        }

        return convertToOccupancyRequestResponse(request);
    }

    private OccupancyRequestResponse convertToOccupancyRequestResponse(OccupancyRequest req) {
        Apartment apt = req.getApartment();
        Building bldg = apt.getBuilding();
        BuildingOwner owner = bldg.getOwner();
        String ownerName = owner != null && owner.getUser() != null ? owner.getUser().getName() : (owner != null ? owner.getName() : "Owner");
        String ownerPhone = owner != null ? owner.getPhone() : "";
        Resident res = req.getResident();

        WaterMeter meter = waterMeterRepository.findByBuildingBuildingId(bldg.getBuildingId()).stream()
                .filter(m -> (m.getApartment() != null && m.getApartment().getApartmentId().equals(apt.getApartmentId()))
                        || (m.getMeterNumber() != null && m.getMeterNumber().endsWith("-" + apt.getRoomNumber())))
                .findFirst()
                .orElse(null);

        OccupancyRequestResponse resp = new OccupancyRequestResponse();
        resp.setRequestId(req.getRequestId());
        resp.setApartmentId(apt.getApartmentId());
        resp.setApartmentName(apt.getApartmentName());
        resp.setRoomNumber(apt.getRoomNumber());
        resp.setFloorNumber(apt.getFloorNumber());
        resp.setUnitType(apt.getUnitType());
        resp.setBuildingId(bldg.getBuildingId());
        resp.setBuildingName(bldg.getBuildingName());
        resp.setBuildingNumber(bldg.getBuildingNumber());
        resp.setBuildingLocation(bldg.getLocation());
        resp.setOwnerId(owner != null ? owner.getOwnerId() : null);
        resp.setOwnerName(ownerName);
        resp.setOwnerPhone(ownerPhone);
        resp.setResidentId(res.getResidentId());
        resp.setResidentName(res.getName());
        resp.setResidentEmail(res.getEmail());
        resp.setResidentPhone(res.getPhone());
        resp.setMeterNumber(meter != null ? meter.getMeterNumber() : "N/A");
        resp.setStatus(req.getStatus().name());
        resp.setRequestDate(req.getRequestDate());
        resp.setReviewedAt(req.getReviewedAt());
        resp.setReviewedBy(req.getReviewedBy());
        resp.setRejectionReason(req.getRejectionReason());
        resp.setNotes(req.getNotes());
        resp.setHouseholdSize(req.getHouseholdSize());
        resp.setChildrenCount(req.getChildrenCount());
        return resp;
    }
}
