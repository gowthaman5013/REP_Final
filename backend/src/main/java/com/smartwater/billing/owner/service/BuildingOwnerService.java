package com.smartwater.billing.owner.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.smartwater.billing.owner.dto.BuildingDTOs.*;
import com.smartwater.billing.common.dto.DashboardDTOs.OwnerDashboardStats;
import com.smartwater.billing.owner.entity.Apartment;
import com.smartwater.billing.owner.entity.Building;
import com.smartwater.billing.owner.entity.BuildingOwner;
import com.smartwater.billing.owner.entity.OwnerHistory;
import com.smartwater.billing.owner.repository.OwnerHistoryRepository;
import com.smartwater.billing.resident.entity.ResidentHistory;
import com.smartwater.billing.resident.repository.ResidentHistoryRepository;

import com.smartwater.billing.common.entity.User;
import com.smartwater.billing.common.entity.WaterMeter;
import com.smartwater.billing.common.exception.BadRequestException;
import com.smartwater.billing.common.exception.ResourceNotFoundException;
import com.smartwater.billing.common.repository.*;
import com.smartwater.billing.owner.repository.*;
import com.smartwater.billing.resident.entity.ApartmentOccupancy;
import com.smartwater.billing.resident.entity.ApartmentOccupancy.OccupancyStatus;
import com.smartwater.billing.resident.entity.OccupancyRequest;
import com.smartwater.billing.resident.entity.Resident;
import com.smartwater.billing.resident.entity.ResidentInvitation;
import com.smartwater.billing.resident.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.smartwater.billing.common.dto.DashboardDTOs.ResidentHistoryItemDTO;

@Service
public class BuildingOwnerService {

    private static final Logger log = LoggerFactory.getLogger(BuildingOwnerService.class);

    @PersistenceContext
    private EntityManager entityManager;

    private final BuildingOwnerRepository ownerRepository;
    private final BuildingRepository buildingRepository;
    private final ResidentRepository residentRepository;
    private final BillRepository billRepository;
    private final UserRepository userRepository;
    private final ApartmentRepository apartmentRepository;
    private final ApartmentOccupancyRepository occupancyRepository;
    private final OccupancyRequestRepository occupancyRequestRepository;
    private final WaterMeterRepository meterRepository;
    private final ResidentInvitationRepository invitationRepository;
    private final OwnerHistoryRepository ownerHistoryRepository;
    private final ResidentHistoryRepository residentHistoryRepository;

    public BuildingOwnerService(
            BuildingOwnerRepository ownerRepository,
            BuildingRepository buildingRepository,
            ResidentRepository residentRepository,
            BillRepository billRepository,
            UserRepository userRepository,
            ApartmentRepository apartmentRepository,
            ApartmentOccupancyRepository occupancyRepository,
            OccupancyRequestRepository occupancyRequestRepository,
            WaterMeterRepository meterRepository,
            ResidentInvitationRepository invitationRepository,
            OwnerHistoryRepository ownerHistoryRepository,
            ResidentHistoryRepository residentHistoryRepository
    ) {
        this.ownerRepository = ownerRepository;
        this.buildingRepository = buildingRepository;
        this.residentRepository = residentRepository;
        this.billRepository = billRepository;
        this.userRepository = userRepository;
        this.apartmentRepository = apartmentRepository;
        this.occupancyRepository = occupancyRepository;
        this.occupancyRequestRepository = occupancyRequestRepository;
        this.meterRepository = meterRepository;
        this.invitationRepository = invitationRepository;
        this.ownerHistoryRepository = ownerHistoryRepository;
        this.residentHistoryRepository = residentHistoryRepository;
    }

    public BuildingOwner getOwnerByUserEmail(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + email));
        return ownerRepository.findByUser(user)
                .orElseThrow(() -> new ResourceNotFoundException("Owner profile not found"));
    }

    public OwnerDashboardStats getOwnerDashboardStats(String email) {
        BuildingOwner owner = getOwnerByUserEmail(email);
        List<Building> buildings = buildingRepository.findByOwner(owner);

        // Self-heal / reconcile residents with their actual occupancy status
        try {
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
        } catch (Exception e) {
            log.warn("Error reconciling residents in dashboard stats: {}", e.getMessage());
        }

        long totalResidents = residentRepository.findByBuildingOwnerOwnerId(owner.getOwnerId()).stream()
                .filter(r -> r.getAccountStatus() == Resident.AccountStatus.ACTIVE
                        && r.getApartmentNumber() != null
                        && !r.getApartmentNumber().equalsIgnoreCase("PENDING")
                        && !r.getApartmentNumber().equalsIgnoreCase("REJECTED")
                        && !r.getApartmentNumber().startsWith("FORMER-")
                        && !r.getApartmentNumber().equalsIgnoreCase("Vacated"))
                .count();

        // 1. Count pending resident onboarding / occupancy requests
        long pendingOccupancyRequests = 0;
        try {
            pendingOccupancyRequests = occupancyRequestRepository
                    .findAllByOwnerIdAndStatus(owner.getOwnerId(), OccupancyRequest.RequestStatus.PENDING)
                    .size();
        } catch (Exception e) {
            log.warn("Error counting pending occupancy requests: {}", e.getMessage());
        }

        // 2. Count pending invitations from resident_invitations table
        long pendingOwnerInvitations = 0;
        try {
            pendingOwnerInvitations = invitationRepository.findAll().stream()
                    .filter(inv -> inv.getBuilding() != null && inv.getBuilding().getOwner() != null
                            && inv.getBuilding().getOwner().getOwnerId().equals(owner.getOwnerId())
                            && inv.getStatus() == ResidentInvitation.InvitationStatus.PENDING)
                    .count();
        } catch (Exception ignored) {}

        // Total pending requests requiring owner review/action
        long totalPending = pendingOccupancyRequests + pendingOwnerInvitations;

        long totalBills = billRepository.findByOwnerId(owner.getOwnerId()).size();
        Double totalRev = billRepository.getTotalRevenueByOwner(owner.getOwnerId());
        double totalRevenue = totalRev != null ? totalRev : 0.0;

        return OwnerDashboardStats.builder()
                .totalBuildings(buildings.size())
                .totalResidents(totalResidents)
                .pendingInvitations(totalPending)
                .pendingRequests(totalPending)
                .totalBillsGenerated(totalBills)
                .totalRevenue(totalRevenue)
                .build();
    }

    public List<BuildingResponse> getOwnerBuildings(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + email));
        if (user.getRole() == User.Role.SUPER_ADMIN) {
            return buildingRepository.findAll().stream()
                    .map(this::convertToBuildingResponse)
                    .collect(Collectors.toList());
        }
        BuildingOwner owner = ownerRepository.findByUser(user)
                .orElseThrow(() -> new ResourceNotFoundException("Owner profile not found"));
        return buildingRepository.findByOwner(owner).stream()
                .map(this::convertToBuildingResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public BuildingResponse createBuilding(String email, CreateBuildingRequest request) {
        BuildingOwner owner = getOwnerByUserEmail(email);
        if (owner.getApprovalStatus() != BuildingOwner.ApprovalStatus.APPROVED) {
            throw new BadRequestException("You cannot create buildings until your owner account has been approved by the Super Admin.");
        }
        Building building = Building.builder()
                .owner(owner)
                .buildingName(request.getBuildingName())
                .buildingNumber(request.getBuildingNumber())
                .totalFlats(request.getTotalFlats())
                .location(request.getLocation())
                .build();
        buildingRepository.save(building);

        // Auto-create planned apartments: 5 rooms per floor starting from 101, then 201, etc.
        int total = request.getTotalFlats() != null ? request.getTotalFlats() : 0;
        int flatsPerFloor = 5;
        for (int floor = 1; (floor - 1) * flatsPerFloor < total; floor++) {
            for (int roomIdx = 1; roomIdx <= flatsPerFloor; roomIdx++) {
                int currentUnitCount = (floor - 1) * flatsPerFloor + roomIdx;
                if (currentUnitCount > total) {
                    break;
                }
                String room = (floor * 100 + roomIdx) + "";
                Apartment apt = Apartment.builder()
                        .building(building)
                        .floorNumber(floor)
                        .apartmentName("Flat " + room)
                        .roomNumber(room)
                        .unitType("2BHK")
                        .build();
                apt = apartmentRepository.save(apt);

                String mNum = "WM-" + building.getBuildingNumber() + "-" + room;
                if (meterRepository.findByMeterNumber(mNum).isEmpty()) {
                    WaterMeter meter = WaterMeter.builder()
                            .building(building)
                            .apartment(apt)
                            .meterNumber(mNum)
                            .installationDate(LocalDate.now())
                            .status(WaterMeter.MeterStatus.ACTIVE)
                            .build();
                    meterRepository.save(meter);
                }
            }
        }

        return convertToBuildingResponse(building);
    }

    public void deleteBuildingCascade(Long buildingId) {
        // 0. Water usage predictions
        entityManager.createNativeQuery(
                "DELETE FROM water_usage_predictions WHERE apartment_id IN (SELECT a.apartment_id FROM (SELECT apartment_id FROM apartments WHERE building_id = :bldgId) a) " +
                "OR resident_id IN (SELECT r.resident_id FROM (SELECT resident_id FROM residents WHERE building_id = :bldgId) r)"
        ).setParameter("bldgId", buildingId).executeUpdate();

        // 1. Away periods & household size history
        entityManager.createNativeQuery(
                "DELETE FROM away_periods WHERE apartment_id IN (SELECT a.apartment_id FROM (SELECT apartment_id FROM apartments WHERE building_id = :bldgId) a) " +
                "OR occupancy_id IN (SELECT o.occupancy_id FROM (SELECT occupancy_id FROM apartment_occupancies WHERE resident_id IN (SELECT r.resident_id FROM (SELECT resident_id FROM residents WHERE building_id = :bldgId) r)) o)"
        ).setParameter("bldgId", buildingId).executeUpdate();

        entityManager.createNativeQuery(
                "DELETE FROM household_size_history WHERE apartment_id IN (SELECT a.apartment_id FROM (SELECT apartment_id FROM apartments WHERE building_id = :bldgId) a) " +
                "OR occupancy_id IN (SELECT o.occupancy_id FROM (SELECT occupancy_id FROM apartment_occupancies WHERE resident_id IN (SELECT r.resident_id FROM (SELECT resident_id FROM residents WHERE building_id = :bldgId) r)) o)"
        ).setParameter("bldgId", buildingId).executeUpdate();

        // 2. Leak feedback and incidents
        entityManager.createNativeQuery(
                "DELETE FROM leak_feedback WHERE apartment_id IN (SELECT a.apartment_id FROM (SELECT apartment_id FROM apartments WHERE building_id = :bldgId) a) " +
                "OR usage_id IN (SELECT u.usage_id FROM (SELECT usage_id FROM water_usage WHERE meter_id IN (SELECT meter_id FROM water_meters WHERE building_id = :bldgId)) u)"
        ).setParameter("bldgId", buildingId).executeUpdate();

        entityManager.createNativeQuery(
                "DELETE FROM leak_incidents WHERE apartment_id IN (SELECT a.apartment_id FROM (SELECT apartment_id FROM apartments WHERE building_id = :bldgId) a)"
        ).setParameter("bldgId", buildingId).executeUpdate();

        // 3. Occupancy requests & resident invitations
        entityManager.createNativeQuery(
                "DELETE FROM occupancy_requests WHERE apartment_id IN (SELECT a.apartment_id FROM (SELECT apartment_id FROM apartments WHERE building_id = :bldgId) a) " +
                "OR resident_id IN (SELECT r.resident_id FROM (SELECT resident_id FROM residents WHERE building_id = :bldgId) r)"
        ).setParameter("bldgId", buildingId).executeUpdate();

        entityManager.createNativeQuery(
                "DELETE FROM resident_invitations WHERE building_id = :bldgId OR apartment_id IN (SELECT a.apartment_id FROM (SELECT apartment_id FROM apartments WHERE building_id = :bldgId) a) " +
                "OR resident_id IN (SELECT r.resident_id FROM (SELECT resident_id FROM residents WHERE building_id = :bldgId) r)"
        ).setParameter("bldgId", buildingId).executeUpdate();

        // 4. Payments (must be deleted before bills)
        entityManager.createNativeQuery(
                "DELETE FROM payments WHERE bill_id IN (" +
                "  SELECT b.bill_id FROM (SELECT bill_id FROM bills WHERE billing_cycle_id IN (SELECT id FROM billing_cycles WHERE building_id = :bldgId) " +
                "  OR resident_id IN (SELECT resident_id FROM residents WHERE building_id = :bldgId)) b)"
        ).setParameter("bldgId", buildingId).executeUpdate();

        // 5. Invoices
        entityManager.createNativeQuery(
                "DELETE FROM invoices WHERE billing_cycle_id IN (SELECT b.id FROM (SELECT id FROM billing_cycles WHERE building_id = :bldgId) b) " +
                "OR resident_id IN (SELECT r.resident_id FROM (SELECT resident_id FROM residents WHERE building_id = :bldgId) r)"
        ).setParameter("bldgId", buildingId).executeUpdate();

        // 6. Bills (must be deleted before water_usage)
        entityManager.createNativeQuery(
                "DELETE FROM bills WHERE billing_cycle_id IN (SELECT b.id FROM (SELECT id FROM billing_cycles WHERE building_id = :bldgId) b) " +
                "OR resident_id IN (SELECT r.resident_id FROM (SELECT resident_id FROM residents WHERE building_id = :bldgId) r)"
        ).setParameter("bldgId", buildingId).executeUpdate();

        // 7. Water usage (must be deleted after bills, before water_meters)
        entityManager.createNativeQuery(
                "DELETE FROM water_usage WHERE meter_id IN (SELECT m.meter_id FROM (SELECT meter_id FROM water_meters WHERE building_id = :bldgId) m)"
        ).setParameter("bldgId", buildingId).executeUpdate();

        // 8. Notifications
        entityManager.createNativeQuery(
                "DELETE FROM notifications WHERE resident_id IN (SELECT r.resident_id FROM (SELECT resident_id FROM residents WHERE building_id = :bldgId) r)"
        ).setParameter("bldgId", buildingId).executeUpdate();

        // 9. Water meters: unlink residents first then delete
        entityManager.createNativeQuery("UPDATE water_meters SET resident_id = NULL WHERE building_id = :bldgId").setParameter("bldgId", buildingId).executeUpdate();
        entityManager.createNativeQuery("DELETE FROM water_meters WHERE building_id = :bldgId").setParameter("bldgId", buildingId).executeUpdate();

        // 10. Apartment occupancies
        entityManager.createNativeQuery(
                "DELETE FROM apartment_occupancies WHERE apartment_id IN (SELECT a.apartment_id FROM (SELECT apartment_id FROM apartments WHERE building_id = :bldgId) a) " +
                "OR resident_id IN (SELECT r.resident_id FROM (SELECT resident_id FROM residents WHERE building_id = :bldgId) r)"
        ).setParameter("bldgId", buildingId).executeUpdate();

        // 11. Tariff plans, water purchases, billing cycles
        entityManager.createNativeQuery("DELETE FROM tariff_plans WHERE building_id = :bldgId").setParameter("bldgId", buildingId).executeUpdate();
        entityManager.createNativeQuery("DELETE FROM water_purchases WHERE building_id = :bldgId").setParameter("bldgId", buildingId).executeUpdate();
        entityManager.createNativeQuery("DELETE FROM billing_cycles WHERE building_id = :bldgId").setParameter("bldgId", buildingId).executeUpdate();

        // 12. Resident users and residents
        @SuppressWarnings("unchecked")
        List<Object> userIds = entityManager.createNativeQuery(
                "SELECT user_id FROM residents WHERE building_id = :bldgId AND user_id IS NOT NULL"
        ).setParameter("bldgId", buildingId).getResultList();

        entityManager.createNativeQuery("DELETE FROM residents WHERE building_id = :bldgId").setParameter("bldgId", buildingId).executeUpdate();

        for (Object uid : userIds) {
            if (uid != null) {
                Long u = ((Number) uid).longValue();
                entityManager.createNativeQuery("UPDATE household_size_history SET changed_by_user_id = NULL WHERE changed_by_user_id = :uid").setParameter("uid", u).executeUpdate();
                entityManager.createNativeQuery("DELETE FROM leak_feedback WHERE submitted_by_user_id = :uid").setParameter("uid", u).executeUpdate();
                entityManager.createNativeQuery("DELETE FROM users WHERE user_id = :uid AND role = 'RESIDENT'").setParameter("uid", u).executeUpdate();
            }
        }

        // 13. Apartments
        entityManager.createNativeQuery("DELETE FROM apartments WHERE building_id = :bldgId").setParameter("bldgId", buildingId).executeUpdate();

        // 14. Building
        entityManager.createNativeQuery("DELETE FROM buildings WHERE building_id = :bldgId").setParameter("bldgId", buildingId).executeUpdate();
    }

    @Transactional
    public void deleteBuilding(String email, Long buildingId) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + email));
        Building building = buildingRepository.findById(buildingId)
                .orElseThrow(() -> new ResourceNotFoundException("Building not found with id: " + buildingId));

        if (user.getRole() != User.Role.SUPER_ADMIN) {
            BuildingOwner owner = ownerRepository.findByUser(user)
                    .orElseThrow(() -> new ResourceNotFoundException("Owner profile not found"));
            if (!building.getOwner().getOwnerId().equals(owner.getOwnerId())) {
                throw new BadRequestException("Unauthorized access: You do not own building '" + building.getBuildingName() + "'!");
            }
        }

        try {
            entityManager.createNativeQuery("SET FOREIGN_KEY_CHECKS = 0").executeUpdate();
            deleteBuildingCascade(buildingId);
        } finally {
            entityManager.createNativeQuery("SET FOREIGN_KEY_CHECKS = 1").executeUpdate();
            entityManager.clear();
        }
    }

    private BuildingResponse convertToBuildingResponse(Building building) {
        return BuildingResponse.builder()
                .buildingId(building.getBuildingId())
                .ownerId(building.getOwner().getOwnerId())
                .ownerName(building.getOwner().getUser().getName())
                .buildingName(building.getBuildingName())
                .buildingNumber(building.getBuildingNumber())
                .totalFlats(building.getTotalFlats())
                .location(building.getLocation())
                .createdAt(building.getCreatedAt())
                .build();
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<ResidentHistoryItemDTO> getResidentHistory(String ownerEmail) {
        User user = userRepository.findByEmail(ownerEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + ownerEmail));

        Long ownerId = null;
        String ownerDisplayName = user.getName();
        if (user.getRole() != User.Role.SUPER_ADMIN) {
            BuildingOwner owner = ownerRepository.findByUser(user)
                    .orElseThrow(() -> new ResourceNotFoundException("Owner profile not found for user: " + ownerEmail));
            ownerId = owner.getOwnerId();
            if (owner.getName() != null) ownerDisplayName = owner.getName();
        }

        List<ResidentHistoryItemDTO> list = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();

        // 1. Audit logs from resident_history table (most accurate chronological records)
        try {
            String queryStr = (ownerId != null)
                    ? "SELECT id, building_name, apartment_name, room_number, resident_name, resident_email, resident_phone, activity_type, details, actor, timestamp " +
                      "FROM resident_history WHERE owner_id = :oId ORDER BY timestamp DESC"
                    : "SELECT id, building_name, apartment_name, room_number, resident_name, resident_email, resident_phone, activity_type, details, actor, timestamp " +
                      "FROM resident_history ORDER BY timestamp DESC";

            var nativeQuery = entityManager.createNativeQuery(queryStr);
            if (ownerId != null) {
                nativeQuery.setParameter("oId", ownerId);
            }
            List<Object[]> rows = nativeQuery.getResultList();

            for (Object[] r : rows) {
                Long id = r[0] != null ? ((Number) r[0]).longValue() : 0L;
                String bldgName = r[1] != null ? r[1].toString() : "Building";
                String aptName = r[2] != null ? r[2].toString() : "Apartment";
                String room = r[3] != null ? r[3].toString() : "";
                String resName = r[4] != null ? r[4].toString() : "Resident";
                String resEmail = r[5] != null ? r[5].toString() : "";
                String resPhone = r[6] != null ? r[6].toString() : "";
                String type = r[7] != null ? r[7].toString() : "JOINED";
                String details = r[8] != null ? r[8].toString() : "";
                String actor = r[9] != null ? r[9].toString() : "";
                String ts = r[10] != null ? r[10].toString() : LocalDateTime.now().toString();

                String dedupKey = type + "_" + room + "_" + (resEmail.isEmpty() ? resName : resEmail);
                seenKeys.add(dedupKey);

                list.add(new ResidentHistoryItemDTO(
                        "HIST-" + id,
                        type,
                        resName,
                        resEmail,
                        resPhone,
                        bldgName,
                        aptName,
                        room,
                        details,
                        actor,
                        ts
                ));
            }
        } catch (Exception e) {
            log.error("Error reading resident_history: {}", e.getMessage(), e);
        }

        // 2. Fetch from apartment_occupancies for this owner's buildings (synthesize any missing history)
        final Long finalOwnerId = ownerId;
        List<ApartmentOccupancy> occupancies = occupancyRepository.findAll().stream()
                .filter(o -> o.getApartment() != null
                        && o.getApartment().getBuilding() != null
                        && (finalOwnerId == null || (o.getApartment().getBuilding().getOwner() != null && o.getApartment().getBuilding().getOwner().getOwnerId().equals(finalOwnerId))))
                .collect(Collectors.toList());

        for (ApartmentOccupancy occ : occupancies) {
            String resName = occ.getResident() != null ? occ.getResident().getName() : "Resident";
            String resEmail = occ.getResident() != null ? occ.getResident().getEmail() : "";
            String resPhone = occ.getResident() != null ? occ.getResident().getPhone() : "";
            String bldgName = occ.getApartment().getBuilding().getBuildingName();
            String aptName = occ.getApartment().getApartmentName();
            String room = occ.getApartment().getRoomNumber();

            // 2a. Move-in / Come to apartment
            String inDedupKey = "JOINED_" + room + "_" + (resEmail.isEmpty() ? resName : resEmail);
            if (!seenKeys.contains(inDedupKey)) {
                seenKeys.add(inDedupKey);
                String inTs = occ.getStartDate() != null ? occ.getStartDate().toString() : (occ.getCreatedAt() != null ? occ.getCreatedAt().toString() : LocalDateTime.now().toString());
                list.add(new ResidentHistoryItemDTO(
                        "OCC-IN-" + occ.getOccupancyId(),
                        "JOINED",
                        resName,
                        resEmail,
                        resPhone,
                        bldgName,
                        aptName,
                        room,
                        "Resident moved in and occupied " + aptName + " (" + bldgName + ")",
                        resName,
                        inTs
                ));
            }

            // 2b. Move-out / Leave apartment
            if (occ.getStatus() == ApartmentOccupancy.OccupancyStatus.ENDED || occ.getEndDate() != null) {
                String outDedupKey = "LEFT_" + room + "_" + (resEmail.isEmpty() ? resName : resEmail);
                if (!seenKeys.contains(outDedupKey)) {
                    seenKeys.add(outDedupKey);
                    String outTs = occ.getEndDate() != null ? occ.getEndDate().toString() : LocalDateTime.now().toString();
                    String reason = occ.getMoveOutReason() != null ? occ.getMoveOutReason() : "Vacated apartment";
                    String actor = reason.toLowerCase().contains("owner") ? ownerDisplayName : resName;
                    list.add(new ResidentHistoryItemDTO(
                            "OCC-OUT-" + occ.getOccupancyId(),
                            "LEFT",
                            resName,
                            resEmail,
                            resPhone,
                            bldgName,
                            aptName,
                            room,
                            "Resident left " + aptName + ". Note: " + reason,
                            actor,
                            outTs
                    ));
                }
            }
        }

        // 3. Add legacy residents who don't have an active ApartmentOccupancy entry
        List<Resident> ownerResidents = (ownerId != null)
                ? residentRepository.findByBuildingOwnerOwnerId(ownerId)
                : residentRepository.findAll();

        for (Resident res : ownerResidents) {
            String room = res.getApartmentNumber();
            if (room != null && !room.startsWith("FORMER-") && !room.equals("Vacated") && !room.equals("PENDING") && !room.equals("Removed")) {
                String inDedupKey = "JOINED_" + room + "_" + (res.getEmail() != null ? res.getEmail() : res.getName());
                if (!seenKeys.contains(inDedupKey)) {
                    seenKeys.add(inDedupKey);
                    list.add(new ResidentHistoryItemDTO(
                            "RES-IN-" + res.getResidentId(),
                            "JOINED",
                            res.getName(),
                            res.getEmail(),
                            res.getPhone(),
                            res.getBuilding() != null ? res.getBuilding().getBuildingName() : "Building",
                            "Flat " + room,
                            room,
                            "Resident moved in and occupied Flat " + room,
                            res.getName(),
                            res.getCreatedAt() != null ? res.getCreatedAt().toString() : LocalDateTime.now().toString()
                    ));
                }
            }
        }

        // Sort by timestamp descending
        list.sort((a, b) -> {
            if (a.getTimestamp() == null) return 1;
            if (b.getTimestamp() == null) return -1;
            return b.getTimestamp().compareTo(a.getTimestamp());
        });

        return list;
    }

    @Transactional
    public void leaveApplication(String email) {
        BuildingOwner owner = getOwnerByUserEmail(email);
        Long ownerId = owner.getOwnerId();
        String ownerName = owner.getUser() != null ? owner.getUser().getName() : owner.getName();
        String phone = owner.getPhone() != null ? owner.getPhone() : "";
        Long userId = owner.getUser() != null ? owner.getUser().getUserId() : null;

        // 1. Audit log in owner_history using direct native SQL
        try {
            entityManager.createNativeQuery(
                    "INSERT INTO owner_history (owner_id, owner_name, email, phone, event_type, details, actor, timestamp) " +
                    "VALUES (:oid, :oname, :email, :phone, 'REMOVED', :details, :actor, NOW())"
            )
            .setParameter("oid", ownerId)
            .setParameter("oname", ownerName)
            .setParameter("email", email)
            .setParameter("phone", phone)
            .setParameter("details", "Owner voluntarily left the application and closed account.")
            .setParameter("actor", ownerName + " (Self)")
            .executeUpdate();
        } catch (Exception e) {
            log.warn("Failed to record owner history audit: {}", e.getMessage());
        }

        try {
            entityManager.createNativeQuery("SET FOREIGN_KEY_CHECKS = 0").executeUpdate();

            // 2. Cascade delete all buildings owned by this owner
            List<Building> buildings = buildingRepository.findByOwner(owner);
            for (Building b : buildings) {
                deleteBuildingCascade(b.getBuildingId());
            }

            // 3. Delete resident invitations issued by this owner
            entityManager.createNativeQuery("DELETE FROM resident_invitations WHERE invited_by_id = :ownerId")
                    .setParameter("ownerId", ownerId)
                    .executeUpdate();

            // 4. Delete building owner profile
            entityManager.createNativeQuery("DELETE FROM building_owners WHERE owner_id = :ownerId")
                    .setParameter("ownerId", ownerId)
                    .executeUpdate();

            // 5. Clean up user references and delete user account
            if (userId != null) {
                entityManager.createNativeQuery("UPDATE household_size_history SET changed_by_user_id = NULL WHERE changed_by_user_id = :uid")
                        .setParameter("uid", userId)
                        .executeUpdate();
                entityManager.createNativeQuery("DELETE FROM leak_feedback WHERE submitted_by_user_id = :uid")
                        .setParameter("uid", userId)
                        .executeUpdate();
                entityManager.createNativeQuery("DELETE FROM users WHERE user_id = :userId AND role != 'SUPER_ADMIN'")
                        .setParameter("userId", userId)
                        .executeUpdate();
            }
        } finally {
            entityManager.createNativeQuery("SET FOREIGN_KEY_CHECKS = 1").executeUpdate();
            entityManager.clear();
        }
    }
}
