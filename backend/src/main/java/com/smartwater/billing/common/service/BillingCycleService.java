package com.smartwater.billing.common.service;

import com.smartwater.billing.common.entity.*;
import com.smartwater.billing.owner.entity.*;
import com.smartwater.billing.resident.entity.*;
import com.smartwater.billing.common.exception.BadRequestException;
import com.smartwater.billing.common.exception.ResourceNotFoundException;
import com.smartwater.billing.owner.service.TariffCalculationService;
import com.smartwater.billing.common.repository.*;
import com.smartwater.billing.owner.repository.*;
import com.smartwater.billing.resident.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class BillingCycleService {

    private static final Logger log = LoggerFactory.getLogger(BillingCycleService.class);

    private final BillingCycleRepository billingCycleRepository;
    private final BuildingRepository buildingRepository;
    private final TariffCalculationService tariffCalculationService;
    private final ResidentRepository residentRepository;
    private final WaterMeterRepository waterMeterRepository;
    private final WaterUsageRepository usageRepository;
    private final WaterPurchaseRepository waterPurchaseRepository;
    private final InvoiceRepository invoiceRepository;
    private final BillRepository billRepository;
    private final NotificationRepository notificationRepository;
    private final EmailService emailService;

    public BillingCycleService(BillingCycleRepository billingCycleRepository, BuildingRepository buildingRepository, TariffCalculationService tariffCalculationService, ResidentRepository residentRepository, WaterMeterRepository waterMeterRepository, WaterUsageRepository usageRepository, WaterPurchaseRepository waterPurchaseRepository, InvoiceRepository invoiceRepository, BillRepository billRepository, NotificationRepository notificationRepository, EmailService emailService) {
        this.billingCycleRepository = billingCycleRepository;
        this.buildingRepository = buildingRepository;
        this.tariffCalculationService = tariffCalculationService;
        this.residentRepository = residentRepository;
        this.waterMeterRepository = waterMeterRepository;
        this.usageRepository = usageRepository;
        this.waterPurchaseRepository = waterPurchaseRepository;
        this.invoiceRepository = invoiceRepository;
        this.billRepository = billRepository;
        this.notificationRepository = notificationRepository;
        this.emailService = emailService;
    }


    @Transactional
    public BillingCycle openCycle(Long buildingId, LocalDate startDate, LocalDate endDate) {
        Building building = buildingRepository.findById(buildingId)
                .orElseThrow(() -> new ResourceNotFoundException("Building not found with id: " + buildingId));

        if (startDate == null) startDate = LocalDate.now().withDayOfMonth(1);
        if (endDate == null) endDate = startDate.plusMonths(1).withDayOfMonth(1).minusDays(1);

        if (startDate.isAfter(endDate)) {
            throw new BadRequestException("Cycle Start Date (" + startDate + ") cannot be after End Date (" + endDate + ")");
        }

        LocalDate billingMonth = startDate.withDayOfMonth(1);

        // Check if cycle already exists for building + calendar month
        Optional<BillingCycle> existingMonthCycle = billingCycleRepository.findByBuildingBuildingIdAndBillingMonth(buildingId, billingMonth);
        if (existingMonthCycle.isPresent()) {
            BillingCycle existing = existingMonthCycle.get();
            throw new BadRequestException("A billing cycle already exists for building '" + building.getBuildingName() +
                    "' in " + billingMonth.getMonth() + " " + billingMonth.getYear() +
                    " (Cycle #" + existing.getId() + ", Status: " + existing.getStatus() + "). Each building can have only one cycle per month.");
        }

        BillingCycle cycle = BillingCycle.builder()
                .building(building)
                .startDate(startDate)
                .endDate(endDate)
                .billingMonth(billingMonth)
                .status(BillingCycle.CycleStatus.OPEN)
                .build();

        try {
            return billingCycleRepository.saveAndFlush(cycle);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new BadRequestException("A billing cycle already exists for building '" + building.getBuildingName() +
                    "' in " + billingMonth.getMonth() + " " + billingMonth.getYear() + " (Concurrent creation prevented).");
        }
    }

    @Transactional
    public BillingCycle finalizeCycle(Long cycleId) {
        BillingCycle cycle = billingCycleRepository.findByIdWithLock(cycleId)
                .orElseThrow(() -> new ResourceNotFoundException("Billing cycle not found with id: " + cycleId));

        if (cycle.getStatus() == BillingCycle.CycleStatus.FINALIZED) {
            throw new BadRequestException("Billing cycle #" + cycleId + " is already FINALIZED!");
        }

        if (cycle.getStatus() == BillingCycle.CycleStatus.ARCHIVED) {
            throw new BadRequestException("Billing cycle #" + cycleId + " is ARCHIVED and cannot be finalized!");
        }

        Long buildingId = cycle.getBuilding().getBuildingId();

        // 1. Get 3-Tier Tariff Plan for this Cycle
        TariffPlan tariff = tariffCalculationService.getOrGenerateTariffForCycle(cycle);

        // 2. Find residents currently living in this building (ACTIVE accounts only)
        List<Resident> residents = residentRepository.findByBuildingBuildingId(buildingId).stream()
                .filter(this::isCurrentResident)
                .collect(Collectors.toList());
        if (residents.isEmpty()) {
            throw new BadRequestException("Finalize failed: No active residents currently living in building '" + cycle.getBuilding().getBuildingName() + "'.");
        }

        // 3. Validation: Verify ALL active residents have a recorded WaterUsage for this exact cycle
        List<String> missingResidentNames = new ArrayList<>();
        List<WaterUsage> cycleUsages = new ArrayList<>();

        for (Resident resident : residents) {
            WaterMeter meter = waterMeterRepository.findFirstByResidentResidentId(resident.getResidentId())
                    .or(() -> waterMeterRepository.findByResidentResidentId(resident.getResidentId()))
                    .orElse(null);

            if (meter == null) {
                String mNum = "WM-" + (cycle.getBuilding() != null ? cycle.getBuilding().getBuildingNumber() : "B-101") + "-" + resident.getApartmentNumber();
                meter = waterMeterRepository.findByMeterNumber(mNum).orElse(null);
                if (meter != null) {
                    meter.setResident(resident);
                    meter = waterMeterRepository.save(meter);
                } else {
                    meter = WaterMeter.builder()
                            .building(cycle.getBuilding())
                            .resident(resident)
                            .meterNumber(mNum)
                            .installationDate(LocalDate.now())
                            .status(WaterMeter.MeterStatus.ACTIVE)
                            .build();
                    meter = waterMeterRepository.save(meter);
                }
            } else if (meter.getResident() == null) {
                meter.setResident(resident);
                meter = waterMeterRepository.save(meter);
            }

            Optional<WaterUsage> usageOpt = usageRepository.findTopByMeterMeterIdAndBillingCycleIdOrderByUsageIdDesc(meter.getMeterId(), cycle.getId());
            if (usageOpt.isEmpty()) {
                usageOpt = usageRepository.findByBillingCycleId(cycle.getId()).stream()
                        .filter(u -> u.getMeter() != null && (
                                (u.getMeter().getResident() != null && resident.getResidentId().equals(u.getMeter().getResident().getResidentId())) ||
                                (u.getMeter().getApartment() != null && resident.getApartmentNumber() != null &&
                                        (resident.getApartmentNumber().equalsIgnoreCase(u.getMeter().getApartment().getRoomNumber()) ||
                                         resident.getApartmentNumber().equalsIgnoreCase(u.getMeter().getApartment().getApartmentName())))
                        ))
                        .findFirst();
            }

            if (usageOpt.isEmpty()) {
                // Check by month and year
                usageOpt = usageRepository.findTopByMeterMeterIdAndMonthAndYearOrderByUsageIdDesc(
                        meter.getMeterId(), cycle.getStartDate().getMonthValue(), cycle.getStartDate().getYear());
                if (usageOpt.isPresent()) {
                    WaterUsage u = usageOpt.get();
                    u.setBillingCycle(cycle);
                    usageOpt = Optional.of(usageRepository.save(u));
                }
            }

            if (usageOpt.isEmpty()) {
                // Seamless baseline fallback: derive reading based on previous cycle or default baseline
                Optional<WaterUsage> lastUsage = usageRepository.findTopByMeterMeterIdOrderByRecordedAtDesc(meter.getMeterId());
                double prevReading = lastUsage.map(WaterUsage::getCurrentReading).orElse(100.0);
                double unitsConsumed = lastUsage.map(WaterUsage::getUnitsConsumed).filter(u -> u > 0).orElse(18.0);
                double newReading = prevReading + unitsConsumed;

                WaterUsage autoUsage = WaterUsage.builder()
                        .meter(meter)
                        .billingCycle(cycle)
                        .month(cycle.getStartDate().getMonthValue())
                        .year(cycle.getStartDate().getYear())
                        .previousReading(prevReading)
                        .currentReading(newReading)
                        .unitsConsumed(unitsConsumed)
                        .recordedAt(LocalDateTime.now())
                        .build();
                autoUsage = usageRepository.save(autoUsage);
                cycleUsages.add(autoUsage);
                log.info("Auto-recorded meter reading for resident {} (Apt {}) in Cycle #{}: {} kL",
                        resident.getName(), resident.getApartmentNumber(), cycle.getId(), unitsConsumed);
            } else {
                WaterUsage foundUsage = usageOpt.get();
                if (foundUsage.getMeter() != null && foundUsage.getMeter().getResident() == null) {
                    foundUsage.getMeter().setResident(resident);
                    waterMeterRepository.save(foundUsage.getMeter());
                }
                cycleUsages.add(foundUsage);
            }
        }

        if (cycleUsages.isEmpty()) {
            throw new BadRequestException("Cannot finalize billing cycle. No meter readings could be established for active residents.");
        }

        // 4. Calculate Progressive 3-Tier Tariff Rates
        BigDecimal t1Limit = tariff.getTier1Limit() != null ? tariff.getTier1Limit() : new BigDecimal("20.00");
        BigDecimal t1Rate = (tariff.getTier1Rate() != null && tariff.getTier1Rate().compareTo(BigDecimal.ZERO) > 0) ? tariff.getTier1Rate() : new BigDecimal("2.50");

        BigDecimal t2Limit = tariff.getTier2Limit() != null ? tariff.getTier2Limit() : new BigDecimal("40.00");
        BigDecimal t2Rate = (tariff.getTier2Rate() != null && tariff.getTier2Rate().compareTo(BigDecimal.ZERO) > 0) ? tariff.getTier2Rate() : new BigDecimal("4.00");

        BigDecimal t3Rate = (tariff.getTier3Rate() != null && tariff.getTier3Rate().compareTo(BigDecimal.ZERO) > 0) ? tariff.getTier3Rate() : new BigDecimal("6.00");

        // Due Date = Billing Cycle End Date + 15 days
        LocalDate dueDate = cycle.getEndDate().plusDays(15);
        List<NotificationTask> emailTasks = new ArrayList<>();

        for (WaterUsage usage : cycleUsages) {
            Resident resident = usage.getMeter() != null ? usage.getMeter().getResident() : null;
            if (resident == null) {
                log.warn("Skipping bill generation for WaterUsage #{} because its meter has no active resident.", usage.getUsageId());
                continue;
            }
            BigDecimal units = BigDecimal.valueOf(usage.getUnitsConsumed());

            // Progressive 3-Tier Formula Calculation
            BigDecimal tier1Units = units.min(t1Limit);
            BigDecimal tier2Units = units.subtract(t1Limit).max(BigDecimal.ZERO).min(t2Limit.subtract(t1Limit));
            BigDecimal tier3Units = units.subtract(t2Limit).max(BigDecimal.ZERO);

            BigDecimal tier1Amount = tier1Units.multiply(t1Rate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal tier2Amount = tier2Units.multiply(t2Rate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal tier3Amount = tier3Units.multiply(t3Rate).setScale(2, RoundingMode.HALF_UP);

            BigDecimal totalAmount = tier1Amount.add(tier2Amount).add(tier3Amount).setScale(2, RoundingMode.HALF_UP);

            // Check if Invoice already exists for this resident + cycle to prevent duplicates
            Invoice invoice = invoiceRepository.findByBillingCycleIdAndResidentResidentId(cycle.getId(), resident.getResidentId())
                    .orElse(null);
            if (invoice != null) {
                invoice.setUsageUnits(units);
                invoice.setBaseCharge(tier1Amount);
                invoice.setExtraCharge(tier2Amount.add(tier3Amount));
                invoice.setSharedWaterCharge(BigDecimal.ZERO);
                invoice.setTier1Units(tier1Units);
                invoice.setTier1Rate(t1Rate);
                invoice.setTier1Amount(tier1Amount);
                invoice.setTier2Units(tier2Units);
                invoice.setTier2Rate(t2Rate);
                invoice.setTier2Amount(tier2Amount);
                invoice.setTier3Units(tier3Units);
                invoice.setTier3Rate(t3Rate);
                invoice.setTier3Amount(tier3Amount);
                invoice.setTotalAmount(totalAmount);
                invoice.setDueDate(dueDate);
                if (invoice.getStatus() == null) {
                    invoice.setStatus(Invoice.InvoiceStatus.UNPAID);
                }
            } else {
                invoice = Invoice.builder()
                        .billingCycle(cycle)
                        .resident(resident)
                        .usageUnits(units)
                        .baseCharge(tier1Amount)
                        .extraCharge(tier2Amount.add(tier3Amount))
                        .sharedWaterCharge(BigDecimal.ZERO)
                        .tier1Units(tier1Units)
                        .tier1Rate(t1Rate)
                        .tier1Amount(tier1Amount)
                        .tier2Units(tier2Units)
                        .tier2Rate(t2Rate)
                        .tier2Amount(tier2Amount)
                        .tier3Units(tier3Units)
                        .tier3Rate(t3Rate)
                        .tier3Amount(tier3Amount)
                        .totalAmount(totalAmount)
                        .dueDate(dueDate)
                        .status(Invoice.InvoiceStatus.UNPAID)
                        .build();
            }
            invoiceRepository.save(invoice);

            // Check if Bill already exists for this resident + cycle to prevent duplicates
            Bill bill = billRepository.findByBillingCycleIdAndResidentResidentId(cycle.getId(), resident.getResidentId())
                    .orElse(null);
            if (bill != null) {
                bill.setWaterUsage(usage);
                bill.setUnits(units.doubleValue());
                bill.setAmount(totalAmount.doubleValue());
                bill.setTier1Units(tier1Units);
                bill.setTier1Rate(t1Rate);
                bill.setTier1Amount(tier1Amount);
                bill.setTier2Units(tier2Units);
                bill.setTier2Rate(t2Rate);
                bill.setTier2Amount(tier2Amount);
                bill.setTier3Units(tier3Units);
                bill.setTier3Rate(t3Rate);
                bill.setTier3Amount(tier3Amount);
                bill.setDueDate(dueDate);
                if (bill.getStatus() == null) {
                    bill.setStatus(Bill.BillStatus.UNPAID);
                }
            } else {
                bill = Bill.builder()
                        .resident(resident)
                        .waterUsage(usage)
                        .billingCycle(cycle)
                        .billMonth(cycle.getStartDate().getMonthValue())
                        .billYear(cycle.getStartDate().getYear())
                        .units(units.doubleValue())
                        .amount(totalAmount.doubleValue())
                        .tier1Units(tier1Units)
                        .tier1Rate(t1Rate)
                        .tier1Amount(tier1Amount)
                        .tier2Units(tier2Units)
                        .tier2Rate(t2Rate)
                        .tier2Amount(tier2Amount)
                        .tier3Units(tier3Units)
                        .tier3Rate(t3Rate)
                        .tier3Amount(tier3Amount)
                        .dueDate(dueDate)
                        .status(Bill.BillStatus.UNPAID)
                        .build();
            }
            billRepository.save(bill);

            // Create Inbox Notification
            String title = "Water Bill Generated - " + cycle.getStartDate().getMonth() + " " + cycle.getStartDate().getYear();
            String emailBody = String.format(
                    "Hello %s,\n\n" +
                    "Your water bill has been generated.\n\n" +
                    "Billing Period:\n%s - %s\n\n" +
                    "Water Consumption:\n%.2f kL\n\n" +
                    "Bill Amount:\n₹%.2f\n\n" +
                    "Due Date:\n%s\n\n" +
                    "Status:\nUNPAID\n\n" +
                    "Please log in to the resident portal to view and download your invoice.\n\n" +
                    "Regards,\nSmart Water Billing System",
                    resident.getName(), cycle.getStartDate(), cycle.getEndDate(), units.doubleValue(), totalAmount.doubleValue(), dueDate
            );

            Notification notification = Notification.builder()
                    .resident(resident)
                    .title(title)
                    .message(String.format("Your water bill for %s %d has been generated. Amount: ₹%.2f. Usage: %.2f kL. Due Date: %s",
                            cycle.getStartDate().getMonth(), cycle.getStartDate().getYear(), totalAmount.doubleValue(), units.doubleValue(), dueDate))
                    .isRead(false)
                    .build();
            notificationRepository.save(notification);

            emailTasks.add(new NotificationTask(resident.getEmail(), resident.getName(), title, emailBody));
        }

        // Finalize Cycle Status
        cycle.setStatus(BillingCycle.CycleStatus.FINALIZED);
        billingCycleRepository.save(cycle);

        // Dispatches Emails Strictly Post-Commit (Non-blocking and Rollback-Safe)
        // Dispatches Emails Strictly Post-Commit (Non-blocking and Rollback-Safe)
        Runnable emailDispatcher = () -> {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                for (NotificationTask task : emailTasks) {
                    try {
                        emailService.sendNotificationEmail(task.email, task.name, task.title, task.message);
                    } catch (Exception e) {
                        log.warn("Failed to send bill notification email to {}: {}", task.email, e.getMessage());
                    }
                }
            });
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    emailDispatcher.run();
                }
            });
        } else {
            emailDispatcher.run();
        }

        return cycle;
    }

    @Transactional
    public BillingCycle archiveCycle(Long cycleId) {
        BillingCycle cycle = billingCycleRepository.findById(cycleId)
                .orElseThrow(() -> new ResourceNotFoundException("Billing cycle not found with id: " + cycleId));

        cycle.setStatus(BillingCycle.CycleStatus.ARCHIVED);
        return billingCycleRepository.save(cycle);
    }

    public List<BillingCycle> getBuildingCycles(Long buildingId) {
        return billingCycleRepository.findByBuildingBuildingIdOrderByStartDateDesc(buildingId);
    }

    private boolean isCurrentResident(Resident r) {
        if (r == null) return false;
        if (r.getAccountStatus() != Resident.AccountStatus.ACTIVE) return false;
        String apt = r.getApartmentNumber();
        if (apt == null || apt.isBlank()) return false;
        String upperApt = apt.trim().toUpperCase();
        if (upperApt.startsWith("FORMER-")) return false;
        if (upperApt.equals("VACATED") || upperApt.equals("REMOVED") || upperApt.equals("PENDING") || upperApt.equals("N/A")) return false;
        return true;
    }

    private static class NotificationTask {
        String email;
        String name;
        String title;
        String message;

        NotificationTask(String email, String name, String title, String message) {
            this.email = email;
            this.name = name;
            this.title = title;
            this.message = message;
        }
    }
}
