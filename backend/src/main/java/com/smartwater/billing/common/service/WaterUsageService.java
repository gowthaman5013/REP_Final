package com.smartwater.billing.common.service;

import com.smartwater.billing.common.dto.UsageDTOs.*;
import com.smartwater.billing.common.entity.*;
import com.smartwater.billing.owner.entity.*;
import com.smartwater.billing.resident.entity.*;
import com.smartwater.billing.common.exception.BadRequestException;
import com.smartwater.billing.common.exception.ResourceNotFoundException;
import com.smartwater.billing.owner.service.BuildingOwnerService;
import com.smartwater.billing.common.repository.*;
import com.smartwater.billing.owner.repository.*;
import com.smartwater.billing.resident.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class WaterUsageService {

    private final WaterUsageRepository usageRepository;
    private final WaterMeterRepository meterRepository;
    private final ResidentRepository residentRepository;
    private final BillingCycleRepository cycleRepository;
    private final NotificationRepository notificationRepository;
    private final BuildingOwnerService ownerService;
    private final EmailService emailService;

    public WaterUsageService(WaterUsageRepository usageRepository, WaterMeterRepository meterRepository, ResidentRepository residentRepository, BillingCycleRepository cycleRepository, NotificationRepository notificationRepository, BuildingOwnerService ownerService, EmailService emailService) {
        this.usageRepository = usageRepository;
        this.meterRepository = meterRepository;
        this.residentRepository = residentRepository;
        this.cycleRepository = cycleRepository;
        this.notificationRepository = notificationRepository;
        this.ownerService = ownerService;
        this.emailService = emailService;
    }


    @Transactional
    public UsageResponse recordUsage(String ownerEmail, RecordUsageRequest request) {
        BuildingOwner owner = ownerService.getOwnerByUserEmail(ownerEmail);
        WaterMeter meter = meterRepository.findById(request.getMeterId())
                .or(() -> meterRepository.findFirstByResidentResidentId(request.getMeterId()))
                .orElseThrow(() -> new ResourceNotFoundException("Water meter not found for id: " + request.getMeterId()));

        if (!meter.getBuilding().getOwner().getOwnerId().equals(owner.getOwnerId())) {
            throw new BadRequestException("Unauthorized access to water meter!");
        }

        if (request.getPreviousReading() == null || request.getPreviousReading() < 0) {
            throw new BadRequestException("Validation Error: Previous reading must be a non-negative number!");
        }
        if (request.getCurrentReading() == null || request.getCurrentReading() < 0) {
            throw new BadRequestException("Validation Error: Current reading must be a non-negative number!");
        }
        if (request.getCurrentReading() < request.getPreviousReading()) {
            throw new BadRequestException("Validation Error: Current reading (" + request.getCurrentReading() + ") cannot be less than Previous reading (" + request.getPreviousReading() + ")!");
        }

        BillingCycle cycle = null;
        if (request.getBillingCycleId() != null) {
            cycle = cycleRepository.findById(request.getBillingCycleId())
                    .orElseThrow(() -> new ResourceNotFoundException("Billing cycle not found for id: " + request.getBillingCycleId()));
        } else {
            cycle = cycleRepository.findTopByBuildingBuildingIdAndStatus(meter.getBuilding().getBuildingId(), BillingCycle.CycleStatus.OPEN)
                    .orElseGet(() -> {
                        List<BillingCycle> cycles = cycleRepository.findByBuildingBuildingIdOrderByStartDateDesc(meter.getBuilding().getBuildingId());
                        if (cycles.isEmpty()) {
                            throw new BadRequestException("No open billing cycle exists for this building. Please create a billing cycle first!");
                        }
                        return cycles.get(0);
                    });
        }

        if (cycle.getStatus() == BillingCycle.CycleStatus.FINALIZED) {
            throw new BadRequestException("Cannot record or modify water usage for a FINALIZED billing cycle!");
        }

        Double units = request.getCurrentReading() - request.getPreviousReading();
        int month = cycle.getStartDate() != null ? cycle.getStartDate().getMonthValue() : (request.getMonth() != null ? request.getMonth() : LocalDate.now().getMonthValue());
        int year = cycle.getStartDate() != null ? cycle.getStartDate().getYear() : (request.getYear() != null ? request.getYear() : LocalDate.now().getYear());

        String anomalyStatus = "NORMAL";

        // Deduplication: Find existing usage for meter + billing cycle safely
        WaterUsage usage = usageRepository.findTopByMeterMeterIdAndBillingCycleIdOrderByUsageIdDesc(meter.getMeterId(), cycle.getId())
                .orElse(null);

        if (usage != null) {
            usage.setPreviousReading(request.getPreviousReading());
            usage.setCurrentReading(request.getCurrentReading());
            usage.setUnitsConsumed(units);
            usage.setAnomalyStatus(anomalyStatus);
            usage.setMonth(month);
            usage.setYear(year);
        } else {
            usage = WaterUsage.builder()
                    .meter(meter)
                    .billingCycle(cycle)
                    .month(month)
                    .year(year)
                    .previousReading(request.getPreviousReading())
                    .currentReading(request.getCurrentReading())
                    .unitsConsumed(units)
                    .anomalyStatus(anomalyStatus)
                    .build();
        }

        usageRepository.save(usage);

        if ("POTENTIAL_LEAK".equals(anomalyStatus) && meter.getResident() != null) {
            Resident resident = meter.getResident();
            String title = "🚨 Potential Water Leak Detected!";
            String msg = "Alert! Meter #" + meter.getMeterNumber() + " registered " + units + " kL consumption, exceeding historical threshold (Mean + 2*StdDev). Potential leak detected!";

            Notification notification = Notification.builder()
                    .resident(resident)
                    .title(title)
                    .message(msg)
                    .isRead(false)
                    .build();
            notificationRepository.save(notification);

            try {
                emailService.sendNotificationEmail(resident.getEmail(), resident.getName(), title, msg);
            } catch (Exception ignored) {}
        }

        return convertToUsageResponse(usage);
    }

    public List<UsageResponse> getOwnerUsageRecords(String ownerEmail) {
        return getOwnerUsageRecords(ownerEmail, null, null, null, null, null);
    }

    public List<UsageResponse> getOwnerUsageRecords(String ownerEmail, Long buildingId, String apartmentNumber, Long billingCycleId, Integer month, Integer year) {
        BuildingOwner owner = ownerService.getOwnerByUserEmail(ownerEmail);
        return usageRepository.findByOwnerId(owner.getOwnerId()).stream()
                .filter(u -> buildingId == null || (u.getMeter() != null && u.getMeter().getBuilding() != null && buildingId.equals(u.getMeter().getBuilding().getBuildingId())))
                .filter(u -> billingCycleId == null || (u.getBillingCycle() != null && billingCycleId.equals(u.getBillingCycle().getId())))
                .filter(u -> month == null || (u.getMonth() != null && month.equals(u.getMonth())))
                .filter(u -> year == null || (u.getYear() != null && year.equals(u.getYear())))
                .filter(u -> {
                    if (apartmentNumber == null || apartmentNumber.isBlank() || "ALL".equalsIgnoreCase(apartmentNumber)) return true;
                    String apt = null;
                    if (u.getMeter() != null) {
                        if (u.getMeter().getApartment() != null && u.getMeter().getApartment().getRoomNumber() != null) {
                            apt = u.getMeter().getApartment().getRoomNumber();
                        }
                        if (apt == null && u.getMeter().getResident() != null && u.getMeter().getResident().getApartmentNumber() != null) {
                            apt = u.getMeter().getResident().getApartmentNumber();
                        }
                        if (apt == null && u.getMeter().getMeterNumber() != null) {
                            String[] parts = u.getMeter().getMeterNumber().split("-");
                            if (parts.length > 0) {
                                apt = parts[parts.length - 1];
                            }
                        }
                    }
                    return apartmentNumber.trim().equalsIgnoreCase(apt != null ? apt.trim() : "");
                })
                .map(this::convertToUsageResponse)
                .collect(Collectors.toList());
    }

    public List<UsageResponse> getResidentUsageRecords(String residentEmail) {
        Resident resident = residentRepository.findByUserEmail(residentEmail)
                .or(() -> residentRepository.findByEmail(residentEmail))
                .orElse(null);
        if (resident == null) return Collections.emptyList();

        return usageRepository.findByResidentId(resident.getResidentId()).stream()
                .map(this::convertToUsageResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public List<Map<String, Object>> getOwnerMeters(String ownerEmail) {
        BuildingOwner owner = ownerService.getOwnerByUserEmail(ownerEmail);
        List<WaterMeter> meters = meterRepository.findByBuildingOwnerOwnerId(owner.getOwnerId());
        
        List<Resident> residents = residentRepository.findByBuildingOwnerOwnerId(owner.getOwnerId());
        for (Resident r : residents) {
            boolean hasMeter = meters.stream().anyMatch(m -> m.getResident() != null && m.getResident().getResidentId().equals(r.getResidentId()));
            if (!hasMeter) {
                String mNum = "WM-" + (r.getBuilding() != null ? r.getBuilding().getBuildingNumber() : "B-101") + "-" + r.getApartmentNumber();
                WaterMeter newMeter = WaterMeter.builder()
                        .building(r.getBuilding())
                        .resident(r)
                        .meterNumber(mNum)
                        .installationDate(LocalDate.now())
                        .status(WaterMeter.MeterStatus.ACTIVE)
                        .build();
                meterRepository.save(newMeter);
            }
        }

        meters = meterRepository.findByBuildingOwnerOwnerId(owner.getOwnerId());

        return meters.stream().map(m -> {
            Map<String, Object> map = new HashMap<>();
            map.put("meterId", m.getMeterId());
            map.put("meterNumber", m.getMeterNumber());
            map.put("residentId", m.getResident() != null ? m.getResident().getResidentId() : null);
            map.put("residentName", m.getResident() != null ? m.getResident().getName() : "Unassigned Resident");
            map.put("apartmentNumber", m.getResident() != null ? m.getResident().getApartmentNumber() : "N/A");
            map.put("buildingName", m.getBuilding() != null ? m.getBuilding().getBuildingName() : "N/A");

            // Auto-populate previous reading from last completed log
            WaterUsage lastLog = usageRepository.findTopByMeterMeterIdOrderByRecordedAtDesc(m.getMeterId()).orElse(null);
            double lastReading = lastLog != null ? lastLog.getCurrentReading() : 0.0;
            map.put("autoPreviousReading", lastReading);

            return map;
        }).collect(Collectors.toList());
    }

    private UsageResponse convertToUsageResponse(WaterUsage usage) {
        String residentName = (usage.getMeter() != null && usage.getMeter().getResident() != null)
                ? usage.getMeter().getResident().getName() : "Unassigned";
        Long residentId = (usage.getMeter() != null && usage.getMeter().getResident() != null)
                ? usage.getMeter().getResident().getResidentId() : null;
        Long cycleId = usage.getBillingCycle() != null ? usage.getBillingCycle().getId() : null;
        String cycleName = usage.getBillingCycle() != null 
                ? "Cycle #" + usage.getBillingCycle().getId() + " (" + usage.getBillingCycle().getStartDate() + " to " + usage.getBillingCycle().getEndDate() + ")"
                : null;

        String apartmentNumber = null;
        Long buildingId = null;
        String buildingName = null;
        if (usage.getMeter() != null) {
            if (usage.getMeter().getApartment() != null && usage.getMeter().getApartment().getRoomNumber() != null) {
                apartmentNumber = usage.getMeter().getApartment().getRoomNumber();
            }
            if (apartmentNumber == null && usage.getMeter().getResident() != null && usage.getMeter().getResident().getApartmentNumber() != null) {
                apartmentNumber = usage.getMeter().getResident().getApartmentNumber();
            }
            if (apartmentNumber == null && usage.getMeter().getMeterNumber() != null) {
                String[] parts = usage.getMeter().getMeterNumber().split("-");
                if (parts.length > 0) {
                    apartmentNumber = parts[parts.length - 1];
                }
            }
            if (usage.getMeter().getBuilding() != null) {
                buildingId = usage.getMeter().getBuilding().getBuildingId();
                buildingName = usage.getMeter().getBuilding().getBuildingName();
            }
        }

        return UsageResponse.builder()
                .usageId(usage.getUsageId())
                .meterId(usage.getMeter() != null ? usage.getMeter().getMeterId() : null)
                .meterNumber(usage.getMeter() != null ? usage.getMeter().getMeterNumber() : "N/A")
                .buildingId(buildingId)
                .buildingName(buildingName)
                .apartmentNumber(apartmentNumber)
                .residentId(residentId)
                .residentName(residentName)
                .billingCycleId(cycleId)
                .cycleName(cycleName)
                .month(usage.getMonth())
                .year(usage.getYear())
                .previousReading(usage.getPreviousReading())
                .currentReading(usage.getCurrentReading())
                .unitsConsumed(usage.getUnitsConsumed())
                .anomalyStatus(usage.getAnomalyStatus() != null ? usage.getAnomalyStatus() : "NORMAL")
                .recordedAt(usage.getRecordedAt())
                .build();
    }
}
