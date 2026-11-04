package com.smartwater.billing.resident.service;

import com.smartwater.billing.common.entity.User;
import com.smartwater.billing.common.exception.BadRequestException;
import com.smartwater.billing.common.exception.ResourceNotFoundException;
import com.smartwater.billing.common.config.HouseholdProperties;
import com.smartwater.billing.common.repository.UserRepository;
import com.smartwater.billing.owner.entity.Apartment;
import com.smartwater.billing.owner.entity.Building;
import com.smartwater.billing.owner.repository.ApartmentRepository;
import com.smartwater.billing.owner.repository.BuildingRepository;
import com.smartwater.billing.resident.dto.HouseholdDTOs.*;
import com.smartwater.billing.resident.entity.ApartmentOccupancy;
import com.smartwater.billing.resident.entity.ApartmentOccupancy.OccupancyStatus;
import com.smartwater.billing.resident.entity.HouseholdSizeHistory;
import com.smartwater.billing.resident.entity.Resident;
import com.smartwater.billing.resident.repository.ApartmentOccupancyRepository;
import com.smartwater.billing.resident.repository.HouseholdSizeHistoryRepository;
import com.smartwater.billing.resident.repository.ResidentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class HouseholdService {

    private static final Logger log = LoggerFactory.getLogger(HouseholdService.class);

    private final ApartmentOccupancyRepository occupancyRepository;
    private final HouseholdSizeHistoryRepository historyRepository;
    private final ApartmentRepository apartmentRepository;
    private final BuildingRepository buildingRepository;
    private final UserRepository userRepository;
    private final ResidentRepository residentRepository;
    private final HouseholdProperties householdProperties;

    public HouseholdService(ApartmentOccupancyRepository occupancyRepository,
                            HouseholdSizeHistoryRepository historyRepository,
                            ApartmentRepository apartmentRepository,
                            BuildingRepository buildingRepository,
                            UserRepository userRepository,
                            ResidentRepository residentRepository,
                            HouseholdProperties householdProperties) {
        this.occupancyRepository = occupancyRepository;
        this.historyRepository = historyRepository;
        this.apartmentRepository = apartmentRepository;
        this.buildingRepository = buildingRepository;
        this.userRepository = userRepository;
        this.residentRepository = residentRepository;
        this.householdProperties = householdProperties;
    }

    /**
     * Gets current household details and change history for logged-in resident.
     */
    @Transactional(readOnly = true)
    public HouseholdDetailResponse getResidentHouseholdDetails(String residentEmail) {
        ApartmentOccupancy occ = findActiveOccupancyByEmail(residentEmail);

        List<HouseholdSizeHistory> history = historyRepository
                .findByOccupancyOccupancyIdOrderByEffectiveFromDesc(occ.getOccupancyId());

        List<HouseholdHistoryItemResponse> historyItems = history.stream().map(h ->
                HouseholdHistoryItemResponse.builder()
                        .id(h.getId())
                        .householdSize(h.getHouseholdSize())
                        .childrenCount(h.getChildrenCount())
                        .effectiveFrom(h.getEffectiveFrom())
                        .effectiveTo(h.getEffectiveTo())
                        .changedByRole(h.getChangedByRole())
                        .reason(h.getReason())
                        .note(h.getNote())
                        .sanityWarning(h.getSanityWarning())
                        .status(h.getStatus())
                        .createdAt(h.getCreatedAt())
                        .build()
        ).collect(Collectors.toList());

        boolean hasSanityWarning = occ.getHouseholdSize() != null &&
                occ.getHouseholdSize() > householdProperties.getLimits().getSanityWarningThreshold();

        return HouseholdDetailResponse.builder()
                .occupancyId(occ.getOccupancyId())
                .apartmentId(occ.getApartment().getApartmentId())
                .apartmentNumber(occ.getApartment().getRoomNumber())
                .householdSize(occ.getHouseholdSize())
                .childrenCount(occ.getChildrenCount())
                .householdSizeSource(occ.getHouseholdSizeSource())
                .householdSizeConfirmed(occ.getHouseholdSizeConfirmed())
                .sanityWarning(hasSanityWarning)
                .history(historyItems)
                .build();
    }

    /**
     * Updates resident's declared household size.
     */
    @Transactional
    public HouseholdDetailResponse updateResidentHousehold(String residentEmail, HouseholdUpdateRequest request) {
        validateHouseholdInput(request.getHouseholdSize(), request.getChildrenCount());

        ApartmentOccupancy occ = findActiveOccupancyByEmail(residentEmail);
        User user = userRepository.findByEmail(residentEmail).orElse(null);

        boolean sanityWarning = request.getHouseholdSize() > householdProperties.getLimits().getSanityWarningThreshold();

        // Supersede previous active history entries
        List<HouseholdSizeHistory> existingActive = historyRepository
                .findActiveByOccupancyAndDate(occ.getOccupancyId(), LocalDate.now());
        for (HouseholdSizeHistory h : existingActive) {
            h.setEffectiveTo(LocalDate.now());
            h.setStatus("SUPERSEDED");
            historyRepository.save(h);
        }

        // Create new active history row
        String reason = (request.getReason() != null && !request.getReason().isBlank()) ?
                request.getReason().trim().toUpperCase() : "CORRECTION";

        HouseholdSizeHistory newHistory = HouseholdSizeHistory.builder()
                .occupancy(occ)
                .apartment(occ.getApartment())
                .householdSize(request.getHouseholdSize())
                .childrenCount(request.getChildrenCount() != null ? request.getChildrenCount() : 0)
                .effectiveFrom(LocalDate.now())
                .effectiveTo(null)
                .changedByUser(user)
                .changedByRole("RESIDENT")
                .reason(reason)
                .note(request.getNote() != null ? request.getNote().trim() : null)
                .sanityWarning(sanityWarning)
                .status("ACTIVE")
                .createdAt(LocalDateTime.now())
                .build();
        historyRepository.save(newHistory);

        // Update current occupancy
        occ.setHouseholdSize(request.getHouseholdSize());
        occ.setChildrenCount(request.getChildrenCount() != null ? request.getChildrenCount() : 0);
        occ.setHouseholdSizeSource("RESIDENT_DECLARED");
        occ.setHouseholdSizeConfirmed(true);
        occupancyRepository.save(occ);

        log.info("Resident {} updated household size to {} (children: {}) for flat {}",
                residentEmail, request.getHouseholdSize(), request.getChildrenCount(), occ.getApartment().getRoomNumber());

        return getResidentHouseholdDetails(residentEmail);
    }

    /**
     * Resident confirms the current estimated household size.
     */
    @Transactional
    public HouseholdDetailResponse confirmResidentHousehold(String residentEmail) {
        ApartmentOccupancy occ = findActiveOccupancyByEmail(residentEmail);
        occ.setHouseholdSizeConfirmed(true);
        occupancyRepository.save(occ);

        log.info("Resident {} confirmed household size estimate ({} occupants) for flat {}",
                residentEmail, occ.getHouseholdSize(), occ.getApartment().getRoomNumber());

        return getResidentHouseholdDetails(residentEmail);
    }

    /**
     * Owner updates household size for an apartment.
     */
    @Transactional
    public HouseholdDetailResponse ownerUpdateHousehold(String ownerEmail, Long apartmentId, HouseholdUpdateRequest request) {
        validateHouseholdInput(request.getHouseholdSize(), request.getChildrenCount());

        Apartment apt = apartmentRepository.findById(apartmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Apartment not found with id: " + apartmentId));

        verifyOwnerBuildingAccess(ownerEmail, apt.getBuilding().getBuildingId());

        User ownerUser = userRepository.findByEmail(ownerEmail).orElse(null);
        Optional<ApartmentOccupancy> activeOccOpt = occupancyRepository
                .findFirstByApartmentApartmentIdAndStatus(apartmentId, OccupancyStatus.ACTIVE);

        if (activeOccOpt.isEmpty()) {
            throw new BadRequestException("Cannot update household size: Apartment " + apt.getRoomNumber() + " has no active occupancy.");
        }

        ApartmentOccupancy occ = activeOccOpt.get();
        boolean sanityWarning = request.getHouseholdSize() > householdProperties.getLimits().getSanityWarningThreshold();

        // Supersede active history entries
        List<HouseholdSizeHistory> existingActive = historyRepository
                .findActiveByOccupancyAndDate(occ.getOccupancyId(), LocalDate.now());
        for (HouseholdSizeHistory h : existingActive) {
            h.setEffectiveTo(LocalDate.now());
            h.setStatus("SUPERSEDED");
            historyRepository.save(h);
        }

        HouseholdSizeHistory newHistory = HouseholdSizeHistory.builder()
                .occupancy(occ)
                .apartment(apt)
                .householdSize(request.getHouseholdSize())
                .childrenCount(request.getChildrenCount() != null ? request.getChildrenCount() : 0)
                .effectiveFrom(LocalDate.now())
                .effectiveTo(null)
                .changedByUser(ownerUser)
                .changedByRole("BUILDING_OWNER")
                .reason(request.getReason() != null ? request.getReason().trim().toUpperCase() : "CORRECTION")
                .note(request.getNote() != null ? request.getNote().trim() : null)
                .sanityWarning(sanityWarning)
                .status("ACTIVE")
                .createdAt(LocalDateTime.now())
                .build();
        historyRepository.save(newHistory);

        occ.setHouseholdSize(request.getHouseholdSize());
        occ.setChildrenCount(request.getChildrenCount() != null ? request.getChildrenCount() : 0);
        occ.setHouseholdSizeSource("OWNER_ENTERED");
        occ.setHouseholdSizeConfirmed(true);
        occupancyRepository.save(occ);

        log.info("Owner {} updated household size for apartment {} to {}", ownerEmail, apt.getRoomNumber(), request.getHouseholdSize());

        return HouseholdDetailResponse.builder()
                .occupancyId(occ.getOccupancyId())
                .apartmentId(apt.getApartmentId())
                .apartmentNumber(apt.getRoomNumber())
                .householdSize(occ.getHouseholdSize())
                .childrenCount(occ.getChildrenCount())
                .householdSizeSource(occ.getHouseholdSizeSource())
                .householdSizeConfirmed(occ.getHouseholdSizeConfirmed())
                .sanityWarning(sanityWarning)
                .build();
    }

    /**
     * Gets household status for all apartments in a building for the owner dashboard.
     */
    @Transactional(readOnly = true)
    public List<BuildingHouseholdStatusResponse> getBuildingHouseholdStatuses(String ownerEmail, Long buildingId) {
        verifyOwnerBuildingAccess(ownerEmail, buildingId);

        List<Apartment> apartments = apartmentRepository.findByBuildingBuildingIdOrderByRoomNumberAsc(buildingId);
        List<BuildingHouseholdStatusResponse> result = new ArrayList<>();

        for (Apartment apt : apartments) {
            Optional<ApartmentOccupancy> occOpt = occupancyRepository
                    .findFirstByApartmentApartmentIdAndStatus(apt.getApartmentId(), OccupancyStatus.ACTIVE);

            String residentName = "-";
            String residentEmail = "-";
            Integer hhSize = householdProperties.getDefaultSizeForUnitType(apt.getUnitType());
            Integer childrenCount = 0;
            String source = "DEFAULT_ESTIMATE";
            boolean confirmed = false;
            LocalDateTime lastUpdated = null;

            if (occOpt.isPresent()) {
                ApartmentOccupancy occ = occOpt.get();
                if (occ.getResident() != null) {
                    residentName = occ.getResident().getName();
                    residentEmail = occ.getResident().getEmail();
                }
                if (occ.getHouseholdSize() != null) {
                    hhSize = occ.getHouseholdSize();
                }
                childrenCount = occ.getChildrenCount() != null ? occ.getChildrenCount() : 0;
                if (occ.getHouseholdSizeSource() != null) {
                    source = occ.getHouseholdSizeSource();
                }
                confirmed = occ.getHouseholdSizeConfirmed();

                Optional<HouseholdSizeHistory> latestHist = historyRepository
                        .findFirstByOccupancyOccupancyIdAndStatusOrderByEffectiveFromDesc(occ.getOccupancyId(), "ACTIVE");
                if (latestHist.isPresent()) {
                    lastUpdated = latestHist.get().getCreatedAt();
                }
            } else {
                Optional<Resident> resOpt = residentRepository.findByBuildingBuildingId(buildingId).stream()
                        .filter(r -> r.getAccountStatus() == Resident.AccountStatus.ACTIVE
                                && apt.getRoomNumber() != null
                                && apt.getRoomNumber().equalsIgnoreCase(r.getApartmentNumber()))
                        .findFirst();
                if (resOpt.isPresent()) {
                    Resident r = resOpt.get();
                    residentName = r.getName();
                    residentEmail = r.getEmail();
                } else {
                    // Skip unoccupied apartments (no active resident occupancy)
                    continue;
                }
            }

            result.add(BuildingHouseholdStatusResponse.builder()
                    .apartmentId(apt.getApartmentId())
                    .apartmentNumber(apt.getRoomNumber())
                    .floorNumber(apt.getFloorNumber())
                    .unitType(apt.getUnitType())
                    .residentName(residentName)
                    .residentEmail(residentEmail)
                    .householdSize(hhSize)
                    .childrenCount(childrenCount)
                    .householdSizeSource(source)
                    .householdSizeConfirmed(confirmed)
                    .lastUpdated(lastUpdated)
                    .build());
        }

        return result;
    }

    private void validateHouseholdInput(Integer householdSize, Integer childrenCount) {
        int min = householdProperties.getLimits().getMin();
        int max = householdProperties.getLimits().getMax();

        if (householdSize == null || householdSize < min || householdSize > max) {
            throw new BadRequestException(String.format("Household size must be an integer between %d and %d.", min, max));
        }

        if (childrenCount != null) {
            if (childrenCount < 0) {
                throw new BadRequestException("Children count cannot be negative.");
            }
            if (childrenCount > householdSize) {
                throw new BadRequestException("Children count (" + childrenCount + ") cannot exceed total household size (" + householdSize + ").");
            }
        }
    }

    private ApartmentOccupancy findActiveOccupancyByEmail(String email) {
        return occupancyRepository.findActiveByResidentEmail(email, OccupancyStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("No active apartment occupancy found for resident email: " + email));
    }

    private void verifyOwnerBuildingAccess(String ownerEmail, Long buildingId) {
        Building b = buildingRepository.findById(buildingId)
                .orElseThrow(() -> new ResourceNotFoundException("Building not found with id: " + buildingId));

        if (b.getOwner() == null || b.getOwner().getUser() == null ||
                !b.getOwner().getUser().getEmail().equalsIgnoreCase(ownerEmail)) {
            // Check if user is SUPER_ADMIN
            User user = userRepository.findByEmail(ownerEmail).orElse(null);
            if (user == null || user.getRole() != User.Role.SUPER_ADMIN) {
                throw new BadRequestException("You do not have permission to access building #" + buildingId);
            }
        }
    }
}
