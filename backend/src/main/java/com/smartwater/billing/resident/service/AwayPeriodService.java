package com.smartwater.billing.resident.service;

import com.smartwater.billing.common.exception.BadRequestException;
import com.smartwater.billing.common.exception.ResourceNotFoundException;
import com.smartwater.billing.common.config.HouseholdProperties;
import com.smartwater.billing.resident.dto.HouseholdDTOs.AwayPeriodRequest;
import com.smartwater.billing.resident.dto.HouseholdDTOs.AwayPeriodResponse;
import com.smartwater.billing.resident.entity.ApartmentOccupancy;
import com.smartwater.billing.resident.entity.ApartmentOccupancy.OccupancyStatus;
import com.smartwater.billing.resident.entity.AwayPeriod;
import com.smartwater.billing.resident.repository.ApartmentOccupancyRepository;
import com.smartwater.billing.resident.repository.AwayPeriodRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class AwayPeriodService {

    private static final Logger log = LoggerFactory.getLogger(AwayPeriodService.class);

    private final AwayPeriodRepository awayPeriodRepository;
    private final ApartmentOccupancyRepository occupancyRepository;
    private final HouseholdProperties householdProperties;

    public AwayPeriodService(AwayPeriodRepository awayPeriodRepository,
                             ApartmentOccupancyRepository occupancyRepository,
                             HouseholdProperties householdProperties) {
        this.awayPeriodRepository = awayPeriodRepository;
        this.occupancyRepository = occupancyRepository;
        this.householdProperties = householdProperties;
    }

    @Transactional(readOnly = true)
    public List<AwayPeriodResponse> getResidentAwayPeriods(String residentEmail) {
        ApartmentOccupancy occ = findActiveOccupancy(residentEmail);
        List<AwayPeriod> periods = awayPeriodRepository.findByOccupancyOccupancyIdOrderByStartDateDesc(occ.getOccupancyId());

        return periods.stream().map(p -> AwayPeriodResponse.builder()
                .id(p.getId())
                .occupancyId(occ.getOccupancyId())
                .apartmentId(p.getApartment().getApartmentId())
                .startDate(p.getStartDate())
                .endDate(p.getEndDate())
                .durationDays((int) ChronoUnit.DAYS.between(p.getStartDate(), p.getEndDate()) + 1)
                .note(p.getNote())
                .createdAt(p.getCreatedAt())
                .build()
        ).collect(Collectors.toList());
    }

    @Transactional
    public AwayPeriodResponse createAwayPeriod(String residentEmail, AwayPeriodRequest request) {
        if (request.getStartDate() == null || request.getEndDate() == null) {
            throw new BadRequestException("Start date and end date are required.");
        }

        if (request.getEndDate().isBefore(request.getStartDate())) {
            throw new BadRequestException("End date cannot be before start date.");
        }

        int maxDays = householdProperties.getLimits().getMaxAwayDays();
        long days = ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) + 1;
        if (days > maxDays) {
            throw new BadRequestException("Away period exceeds maximum allowed limit of " + maxDays + " days (Requested: " + days + " days).");
        }

        ApartmentOccupancy occ = findActiveOccupancy(residentEmail);

        // Check overlap within this occupancy
        List<AwayPeriod> overlaps = awayPeriodRepository.findOverlappingPeriods(
                occ.getOccupancyId(), request.getStartDate(), request.getEndDate());
        if (!overlaps.isEmpty()) {
            throw new BadRequestException("The specified away dates overlap with an existing recorded away period.");
        }

        AwayPeriod awayPeriod = AwayPeriod.builder()
                .occupancy(occ)
                .apartment(occ.getApartment())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .note(request.getNote() != null ? request.getNote().trim() : null)
                .createdAt(LocalDateTime.now())
                .build();

        AwayPeriod saved = awayPeriodRepository.save(awayPeriod);
        log.info("Created away period #{} for resident {} from {} to {} ({} days)",
                saved.getId(), residentEmail, saved.getStartDate(), saved.getEndDate(), days);

        return AwayPeriodResponse.builder()
                .id(saved.getId())
                .occupancyId(occ.getOccupancyId())
                .apartmentId(saved.getApartment().getApartmentId())
                .startDate(saved.getStartDate())
                .endDate(saved.getEndDate())
                .durationDays((int) days)
                .note(saved.getNote())
                .createdAt(saved.getCreatedAt())
                .build();
    }

    @Transactional
    public void deleteAwayPeriod(String residentEmail, Long id) {
        AwayPeriod period = awayPeriodRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Away period not found with id: " + id));

        ApartmentOccupancy occ = findActiveOccupancy(residentEmail);
        if (!period.getOccupancy().getOccupancyId().equals(occ.getOccupancyId())) {
            throw new BadRequestException("You can only delete away periods belonging to your own apartment tenancy.");
        }

        awayPeriodRepository.delete(period);
        log.info("Deleted away period #{} by resident {}", id, residentEmail);
    }

    private ApartmentOccupancy findActiveOccupancy(String residentEmail) {
        return occupancyRepository.findActiveByResidentEmail(residentEmail, OccupancyStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("No active apartment occupancy found for resident email: " + residentEmail));
    }
}
