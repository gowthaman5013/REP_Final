package com.smartwater.billing.owner.service;

import com.smartwater.billing.owner.dto.BulkBillingDTOs.*;
import com.smartwater.billing.common.entity.*;
import com.smartwater.billing.owner.entity.*;
import com.smartwater.billing.resident.entity.*;
import com.smartwater.billing.common.exception.BadRequestException;
import com.smartwater.billing.common.exception.ResourceNotFoundException;
import com.smartwater.billing.common.repository.*;
import com.smartwater.billing.owner.repository.*;
import com.smartwater.billing.resident.repository.*;
import com.smartwater.billing.common.service.AlertEngineService;
import com.smartwater.billing.common.service.BillingCycleService;
import com.smartwater.billing.common.util.CsvHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class BulkBillingService {

    private static final Logger log = LoggerFactory.getLogger(BulkBillingService.class);

    private final BuildingRepository buildingRepository;
    private final BillingCycleRepository billingCycleRepository;
    private final ResidentRepository residentRepository;
    private final WaterMeterRepository waterMeterRepository;
    private final WaterUsageRepository usageRepository;
    private final BillRepository billRepository;
    private final NotificationRepository notificationRepository;
    private final BuildingOwnerService ownerService;
    private final TariffCalculationService tariffCalculationService;
    private final BillingCycleService billingCycleService;
    private final AlertEngineService alertEngineService;
    private final ApartmentRepository apartmentRepository;

    public BulkBillingService(BuildingRepository buildingRepository, BillingCycleRepository billingCycleRepository, ResidentRepository residentRepository, WaterMeterRepository waterMeterRepository, WaterUsageRepository usageRepository, BillRepository billRepository, NotificationRepository notificationRepository, BuildingOwnerService ownerService, TariffCalculationService tariffCalculationService, BillingCycleService billingCycleService, AlertEngineService alertEngineService, ApartmentRepository apartmentRepository) {
        this.buildingRepository = buildingRepository;
        this.billingCycleRepository = billingCycleRepository;
        this.residentRepository = residentRepository;
        this.waterMeterRepository = waterMeterRepository;
        this.usageRepository = usageRepository;
        this.billRepository = billRepository;
        this.notificationRepository = notificationRepository;
        this.ownerService = ownerService;
        this.tariffCalculationService = tariffCalculationService;
        this.billingCycleService = billingCycleService;
        this.alertEngineService = alertEngineService;
        this.apartmentRepository = apartmentRepository;
    }


    /**
     * Resolves the authoritative trusted previous reading for a given meter and billing cycle.
     * 
     * Invariants:
     * 1. If an existing WaterUsage record has already been logged for this EXACT cycle (e.g. from prior CSV import),
     *    the starting meter reading of this cycle is that record's previousReading.
     * 2. Otherwise, the starting reading of this cycle is the currentReading of the most recent historical
     *    WaterUsage record from a PRECEDING cycle (i.e. where cycle ID != currentCycleId).
     * 3. If no historical usage exists for this meter, the initial starting reading is 0.00.
     */
    public double getTrustedPreviousReading(Long meterId, Long currentCycleId) {
        if (meterId == null) return 0.0;

        // 1. Check if a reading was already recorded in this cycle
        if (currentCycleId != null) {
            Optional<WaterUsage> currentCycleUsage = usageRepository.findByMeterMeterIdAndBillingCycleId(meterId, currentCycleId);
            if (currentCycleUsage.isPresent()) {
                return currentCycleUsage.get().getPreviousReading();
            }
        }

        // 2. Query historical usages for this meter, excluding the current cycle
        List<WaterUsage> historical = usageRepository.findByMeterMeterIdOrderByYearDescMonthDesc(meterId);
        WaterUsage priorUsage = historical.stream()
                .filter(u -> u.getBillingCycle() == null || (currentCycleId == null || !currentCycleId.equals(u.getBillingCycle().getId())))
                .findFirst()
                .orElse(null);

        if (priorUsage != null) {
            return priorUsage.getCurrentReading();
        }

        // 3. Brand new meter / no prior usage
        return 0.0;
    }

    /**
     * Generates a pre-populated CSV template containing all active residents and meters
     * for a specific building and billing cycle, with historical previous readings pre-filled.
     * STRICT SAFEGUARD: Read-only query; does not modify or create database records.
     */
    @Transactional
    public byte[] generateTemplate(String ownerEmail, Long buildingId, Long cycleId) {
        Building building = verifyBuildingOwnership(ownerEmail, buildingId);
        BillingCycle cycle = billingCycleRepository.findById(cycleId)
                .orElseThrow(() -> new ResourceNotFoundException("Billing cycle not found with id: " + cycleId));

        if (!cycle.getBuilding().getBuildingId().equals(buildingId)) {
            throw new BadRequestException("Billing cycle #" + cycleId + " does not belong to building '" + building.getBuildingName() + "'.");
        }

        List<Resident> residents = residentRepository.findByBuildingBuildingId(buildingId).stream()
                .filter(this::isCurrentResident)
                .collect(Collectors.toList());
        if (residents.isEmpty()) {
            throw new BadRequestException("No active residents found in building '" + building.getBuildingName() + "'. Please add residents before generating a template.");
        }

        // Sort residents by apartment number
        residents.sort(Comparator.comparing(
                Resident::getApartmentNumber,
                Comparator.nullsLast(String::compareToIgnoreCase)
        ));

        List<String> headers = List.of(
                "resident_id",
                "apartment_number",
                "resident_name",
                "meter_id",
                "meter_number",
                "previous_reading",
                "current_reading"
        );

        List<List<String>> dataRows = new ArrayList<>();

        for (Resident resident : residents) {
            WaterMeter meter = waterMeterRepository.findFirstByResidentResidentId(resident.getResidentId())
                    .or(() -> waterMeterRepository.findByResidentResidentId(resident.getResidentId()))
                    .orElse(null);

            if (meter == null && resident.getApartmentNumber() != null) {
                List<WaterMeter> bMeters = waterMeterRepository.findByBuildingBuildingId(buildingId);
                meter = bMeters.stream()
                        .filter(m -> m.getApartment() != null && resident.getApartmentNumber().equalsIgnoreCase(m.getApartment().getRoomNumber()))
                        .findFirst()
                        .orElse(null);
            }

            if (meter == null && resident.getApartmentNumber() != null) {
                String mNum = "WM-" + building.getBuildingNumber() + "-" + resident.getApartmentNumber();
                meter = waterMeterRepository.findByMeterNumber(mNum).orElse(null);
            }

            if (meter == null) {
                String mNum = "WM-" + building.getBuildingNumber() + "-" + (resident.getApartmentNumber() != null ? resident.getApartmentNumber() : "RES-" + resident.getResidentId());
                Apartment apt = resident.getApartmentNumber() != null
                        ? apartmentRepository.findByBuildingBuildingIdAndRoomNumber(buildingId, resident.getApartmentNumber()).orElse(null)
                        : null;
                meter = WaterMeter.builder()
                        .building(building)
                        .apartment(apt)
                        .resident(resident)
                        .meterNumber(mNum)
                        .installationDate(LocalDate.now())
                        .status(WaterMeter.MeterStatus.ACTIVE)
                        .build();
                meter = waterMeterRepository.save(meter);
            } else if (meter.getResident() == null || !meter.getResident().getResidentId().equals(resident.getResidentId())) {
                meter.setResident(resident);
                waterMeterRepository.save(meter);
            }

            String meterIdStr = "";
            String meterNumberStr = "";
            double prevReading = 0.0;

            if (meter != null) {
                meterIdStr = String.valueOf(meter.getMeterId());
                meterNumberStr = meter.getMeterNumber() != null ? meter.getMeterNumber() : "";
                prevReading = getTrustedPreviousReading(meter.getMeterId(), cycleId);
            }

            List<String> row = List.of(
                    String.valueOf(resident.getResidentId()),
                    resident.getApartmentNumber() != null ? resident.getApartmentNumber() : "",
                    resident.getName() != null ? resident.getName() : "",
                    meterIdStr,
                    meterNumberStr,
                    String.format(Locale.US, "%.2f", prevReading),
                    "" // Current reading left blank for owner to fill
            );
            dataRows.add(row);
        }

        return CsvHelper.createCsv(headers, dataRows);
    }

    /**
     * Checks cycle status and eligible resident counts for pre-import status display.
     */
    @Transactional(readOnly = true)
    public CycleReadinessDTO getCycleReadiness(String ownerEmail, Long buildingId, Long cycleId) {
        Building building = verifyBuildingOwnership(ownerEmail, buildingId);
        BillingCycle cycle = billingCycleRepository.findById(cycleId)
                .orElseThrow(() -> new ResourceNotFoundException("Billing cycle not found with id: " + cycleId));

        List<Resident> residents = residentRepository.findByBuildingBuildingId(buildingId).stream()
                .filter(this::isCurrentResident)
                .collect(Collectors.toList());
        List<WaterUsage> recordedUsages = usageRepository.findByBillingCycleId(cycleId);
        
        long existingBillsCount = residents.stream()
                .filter(r -> billRepository.findByBillingCycleIdAndResidentResidentId(cycleId, r.getResidentId()).isPresent())
                .count();

        int totalResidents = residents.size();
        int recordedCount = recordedUsages.size();
        int missingCount = Math.max(0, totalResidents - recordedCount);
        boolean canImport = cycle.getStatus() == BillingCycle.CycleStatus.OPEN;

        return CycleReadinessDTO.builder()
                .buildingId(buildingId)
                .buildingName(building.getBuildingName())
                .billingCycleId(cycleId)
                .cycleStatus(cycle.getStatus().name())
                .startDate(cycle.getStartDate().toString())
                .endDate(cycle.getEndDate().toString())
                .eligibleResidentsCount(totalResidents)
                .recordedReadingsCount(recordedCount)
                .missingReadingsCount(missingCount)
                .existingBillsCount((int) existingBillsCount)
                .canImport(canImport)
                .build();
    }

    /**
     * Dry-run validation of uploaded CSV file.
     * STRICT SAFEGUARD: Does NOT insert WaterUsage, does NOT generate Bills, does NOT finalize BillingCycle.
     */
    @Transactional
    public BulkValidationResponse validateCsv(String ownerEmail, Long buildingId, Long cycleId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Uploaded CSV file is empty. Please select a valid CSV file.");
        }

        Building building = verifyBuildingOwnership(ownerEmail, buildingId);
        BillingCycle cycle = billingCycleRepository.findById(cycleId)
                .orElseThrow(() -> new ResourceNotFoundException("Billing cycle not found with id: " + cycleId));

        if (cycle.getStatus() != BillingCycle.CycleStatus.OPEN) {
            throw new BadRequestException("Billing cycle #" + cycleId + " is " + cycle.getStatus() + ". Meter readings can only be imported for OPEN billing cycles.");
        }

        List<List<String>> csvRows;
        try {
            csvRows = CsvHelper.parseCsv(file.getInputStream());
        } catch (IOException e) {
            throw new BadRequestException("Failed to read CSV file: " + e.getMessage());
        }

        if (csvRows.isEmpty()) {
            throw new BadRequestException("CSV file contains no data rows.");
        }

        // Header mapping
        List<String> headerRow = csvRows.get(0);
        Map<String, Integer> colMap = buildHeaderMap(headerRow);
        validateRequiredHeaders(colMap);

        List<Resident> buildingResidents = residentRepository.findByBuildingBuildingId(buildingId).stream()
                .filter(this::isCurrentResident)
                .collect(Collectors.toList());
        Map<Long, Resident> residentMap = new HashMap<>();
        for (Resident r : buildingResidents) {
            residentMap.put(r.getResidentId(), r);
        }

        // Retrieve current cycle TariffPlan for server-side rate estimation
        TariffPlan tariffPlan = null;
        try {
            tariffPlan = tariffCalculationService.getOrGenerateTariffForCycle(cycle);
        } catch (Exception e) {
            log.warn("Could not retrieve or generate cycle tariff for estimation: {}", e.getMessage());
        }

        List<BulkReadingRowDTO> previewRows = new ArrayList<>();
        Set<Long> seenResidentIds = new HashSet<>();
        Set<Long> seenMeterIds = new HashSet<>();

        int validCount = 0;
        int errorCount = 0;
        int alreadyBilledCount = 0;
        double totalEstimatedUnits = 0.0;
        double totalEstimatedAmount = 0.0;

        for (int i = 1; i < csvRows.size(); i++) {
            List<String> rowData = csvRows.get(i);
            int rowNumber = i + 1; // 1-indexed including header

            BulkReadingRowDTO rowDto = BulkReadingRowDTO.builder()
                    .rowNumber(rowNumber)
                    .status(RowStatus.VALID)
                    .build();

            try {
                // 1. Extract and Validate resident_id
                String resIdStr = getColumnValue(rowData, colMap, "resident_id");
                if (resIdStr == null || resIdStr.isBlank()) {
                    throw new IllegalArgumentException("Missing resident_id");
                }
                Long residentId = Long.parseLong(resIdStr.trim());
                rowDto.setResidentId(residentId);

                Resident resident = residentMap.get(residentId);
                if (resident == null) {
                    throw new IllegalArgumentException("Resident ID " + residentId + " does not exist in building '" + building.getBuildingName() + "'");
                }
                rowDto.setResidentName(resident.getName());
                rowDto.setApartmentNumber(resident.getApartmentNumber());

                // Check for duplicate rows in the same CSV
                if (!seenResidentIds.add(residentId)) {
                    rowDto.setStatus(RowStatus.DUPLICATE_ROW);
                    rowDto.setErrorMessage("Duplicate row: Resident " + resident.getName() + " appears multiple times in the CSV");
                    errorCount++;
                    previewRows.add(rowDto);
                    continue;
                }

                // 2. Extract and Validate meter_id
                String meterIdStr = getColumnValue(rowData, colMap, "meter_id");
                String meterNumFromCsv = getColumnValue(rowData, colMap, "meter_number");
                Long meterId = null;
                if (meterIdStr != null && !meterIdStr.isBlank()) {
                    try {
                        meterId = Long.parseLong(meterIdStr.trim());
                    } catch (NumberFormatException ignored) {}
                }

                WaterMeter meter = null;
                if (meterId != null) {
                    meter = waterMeterRepository.findById(meterId).orElse(null);
                }

                if (meter == null && meterNumFromCsv != null && !meterNumFromCsv.isBlank()) {
                    meter = waterMeterRepository.findByMeterNumber(meterNumFromCsv.trim()).orElse(null);
                }

                if (meter == null) {
                    meter = waterMeterRepository.findFirstByResidentResidentId(residentId)
                            .or(() -> waterMeterRepository.findByResidentResidentId(residentId))
                            .orElse(null);
                }

                if (meter == null && resident.getApartmentNumber() != null) {
                    List<WaterMeter> bMeters = waterMeterRepository.findByBuildingBuildingId(buildingId);
                    meter = bMeters.stream()
                            .filter(m -> m.getApartment() != null && resident.getApartmentNumber().equalsIgnoreCase(m.getApartment().getRoomNumber()))
                            .findFirst()
                            .orElse(null);
                }

                if (meter == null && resident.getApartmentNumber() != null) {
                    String mNum = "WM-" + building.getBuildingNumber() + "-" + resident.getApartmentNumber();
                    meter = waterMeterRepository.findByMeterNumber(mNum).orElse(null);
                }

                if (meter == null) {
                    String mNum = (meterNumFromCsv != null && !meterNumFromCsv.isBlank())
                            ? meterNumFromCsv.trim()
                            : "WM-" + building.getBuildingNumber() + "-" + (resident.getApartmentNumber() != null ? resident.getApartmentNumber() : "RES-" + residentId);
                    Apartment apt = resident.getApartmentNumber() != null
                            ? apartmentRepository.findByBuildingBuildingIdAndRoomNumber(buildingId, resident.getApartmentNumber()).orElse(null)
                            : null;
                    meter = WaterMeter.builder()
                            .building(building)
                            .apartment(apt)
                            .resident(resident)
                            .meterNumber(mNum)
                            .installationDate(LocalDate.now())
                            .status(WaterMeter.MeterStatus.ACTIVE)
                            .build();
                    meter = waterMeterRepository.save(meter);
                }

                if (!meter.getBuilding().getBuildingId().equals(buildingId)) {
                    throw new IllegalArgumentException("Water meter #" + meter.getMeterNumber() + " belongs to a different building");
                }

                if (meter.getResident() != null && !residentId.equals(meter.getResident().getResidentId())) {
                    throw new IllegalArgumentException("Water meter #" + meter.getMeterNumber() + " is already assigned to resident " + meter.getResident().getName() + " (Apartment " + meter.getResident().getApartmentNumber() + ")");
                }

                rowDto.setMeterId(meter.getMeterId());
                rowDto.setMeterNumber(meter.getMeterNumber());
                rowDto.setApartmentNumber(resident.getApartmentNumber());

                if (!seenMeterIds.add(meter.getMeterId())) {
                    rowDto.setStatus(RowStatus.DUPLICATE_ROW);
                    rowDto.setErrorMessage("Duplicate row: Meter " + meter.getMeterNumber() + " appears multiple times in the CSV");
                    errorCount++;
                    previewRows.add(rowDto);
                    continue;
                }

                // 3. Check if resident already has an existing bill for this cycle
                if (billRepository.findByBillingCycleIdAndResidentResidentId(cycleId, residentId).isPresent()) {
                    rowDto.setStatus(RowStatus.ALREADY_BILLED);
                    rowDto.setErrorMessage("Resident already has a finalized bill in this billing cycle");
                    alreadyBilledCount++;
                    previewRows.add(rowDto);
                    continue;
                }

                double trustedPrevReading = getTrustedPreviousReading(meter.getMeterId(), cycleId);

                String prevStr = getColumnValue(rowData, colMap, "previous_reading");
                double prevReading = trustedPrevReading;
                if (prevStr != null && !prevStr.isBlank()) {
                    prevReading = Double.parseDouble(prevStr.trim());
                }
                if (prevReading < 0) {
                    throw new IllegalArgumentException("Previous reading cannot be negative (" + prevReading + ")");
                }
                rowDto.setPreviousReading(prevReading);

                // 5. Validate current_reading
                String curStr = getColumnValue(rowData, colMap, "current_reading");
                if (curStr == null || curStr.isBlank()) {
                    throw new IllegalArgumentException("Missing current_reading value");
                }
                double curReading = Double.parseDouble(curStr.trim());
                if (curReading < 0) {
                    throw new IllegalArgumentException("Current reading cannot be negative (" + curReading + ")");
                }
                rowDto.setCurrentReading(curReading);

                // Decreasing reading detection
                if (curReading < prevReading) {
                    throw new IllegalArgumentException(String.format(Locale.US,
                            "Decreasing meter reading: Current reading (%.2f) cannot be less than previous reading (%.2f)",
                            curReading, prevReading));
                }

                // Anti-Tampering Check: Uploaded previous reading must strictly match trusted DB previous reading
                if (prevStr != null && !prevStr.isBlank()) {
                    if (Math.abs(prevReading - trustedPrevReading) > 0.001) {
                        throw new IllegalArgumentException(String.format(Locale.US,
                                "Previous reading mismatch for resident %s (Meter: %s). Uploaded: %.2f, Trusted Database: %.2f. Tampering detected.",
                                resident.getName(), meter.getMeterNumber(), prevReading, trustedPrevReading));
                    }
                }

                // 6. Calculate units & estimate charge server-side
                double units = Math.round((curReading - prevReading) * 100.0) / 100.0;
                rowDto.setUnitsConsumed(units);

                double estimatedCharge = calculateEstimatedCharge(units, tariffPlan);
                rowDto.setEstimatedCharge(estimatedCharge);

                totalEstimatedUnits += units;
                totalEstimatedAmount += estimatedCharge;
                validCount++;

            } catch (NumberFormatException nfe) {
                rowDto.setStatus(RowStatus.INVALID);
                rowDto.setErrorMessage("Numeric format error: " + nfe.getMessage());
                errorCount++;
            } catch (IllegalArgumentException iae) {
                rowDto.setStatus(RowStatus.INVALID);
                rowDto.setErrorMessage(iae.getMessage());
                errorCount++;
            } catch (Exception ex) {
                rowDto.setStatus(RowStatus.INVALID);
                rowDto.setErrorMessage("Unexpected row error: " + ex.getMessage());
                errorCount++;
            }

            previewRows.add(rowDto);
        }

        // Identify Missing Residents (residents in building not present in CSV)
        List<String> missingResidentNames = new ArrayList<>();
        for (Resident r : buildingResidents) {
            if (!seenResidentIds.contains(r.getResidentId())) {
                missingResidentNames.add(r.getName() + " (" + r.getApartmentNumber() + ")");
            }
        }
        int missingCount = missingResidentNames.size();

        // Safe Finalization Gate: All eligible residents must have readings and 0 errors
        boolean canFinalize = (errorCount == 0 && missingCount == 0 && validCount > 0);
        String statusMessage;
        if (canFinalize) {
            statusMessage = "All " + validCount + " resident meter readings verified. Ready for batch generation.";
        } else if (missingCount > 0) {
            statusMessage = "Validation incomplete: " + missingCount + " resident(s) from this building are missing in the CSV.";
        } else {
            statusMessage = "Validation failed: " + errorCount + " row(s) contain validation errors.";
        }

        return BulkValidationResponse.builder()
                .buildingId(buildingId)
                .buildingName(building.getBuildingName())
                .billingCycleId(cycleId)
                .cyclePeriod(cycle.getStartDate() + " to " + cycle.getEndDate())
                .cycleStatus(cycle.getStatus().name())
                .totalResidentsCount(buildingResidents.size())
                .totalRowsInCsv(csvRows.size() - 1)
                .validCount(validCount)
                .errorCount(errorCount)
                .alreadyBilledCount(alreadyBilledCount)
                .missingResidentsCount(missingCount)
                .totalEstimatedUnits(Math.round(totalEstimatedUnits * 100.0) / 100.0)
                .totalEstimatedAmount(Math.round(totalEstimatedAmount * 100.0) / 100.0)
                .canFinalize(canFinalize)
                .statusMessage(statusMessage)
                .missingResidents(missingResidentNames)
                .rows(previewRows)
                .build();
    }

    /**
     * Generates a downloadable CSV containing only invalid/problematic rows and their error messages.
     */
    public byte[] generateErrorReport(BulkValidationResponse response) {
        List<String> headers = List.of(
                "row_number",
                "resident_id",
                "resident_name",
                "apartment_number",
                "meter_number",
                "previous_reading",
                "current_reading",
                "status",
                "error_reason"
        );

        List<List<String>> dataRows = new ArrayList<>();

        if (response.getRows() != null) {
            for (BulkReadingRowDTO row : response.getRows()) {
                if (row.getStatus() != RowStatus.VALID) {
                    List<String> r = List.of(
                            String.valueOf(row.getRowNumber() != null ? row.getRowNumber() : ""),
                            String.valueOf(row.getResidentId() != null ? row.getResidentId() : ""),
                            row.getResidentName() != null ? row.getResidentName() : "",
                            row.getApartmentNumber() != null ? row.getApartmentNumber() : "",
                            row.getMeterNumber() != null ? row.getMeterNumber() : "",
                            row.getPreviousReading() != null ? String.format(Locale.US, "%.2f", row.getPreviousReading()) : "",
                            row.getCurrentReading() != null ? String.format(Locale.US, "%.2f", row.getCurrentReading()) : "",
                            row.getStatus().name(),
                            row.getErrorMessage() != null ? row.getErrorMessage() : ""
                    );
                    dataRows.add(r);
                }
            }
        }

        // Add missing residents as informational rows
        if (response.getMissingResidents() != null) {
            for (String missing : response.getMissingResidents()) {
                List<String> r = List.of(
                        "-",
                        "-",
                        missing,
                        "-",
                        "-",
                        "-",
                        "-",
                        "MISSING_IN_FILE",
                        "Resident is missing in the uploaded CSV file"
                );
                dataRows.add(r);
            }
        }

        return CsvHelper.createCsv(headers, dataRows);
    }

    /**
     * Imports water-meter readings from an uploaded CSV file for an OPEN billing cycle.
     * Persists WaterUsage records ONLY.
     * 
     * STRICT SAFEGUARDS:
     * - Does NOT finalize or close the billing cycle. The cycle remains OPEN.
     * - Does NOT generate bills or invoices.
     * - Does NOT alter tariffs or billing rules.
     * - Re-validates the CSV thoroughly; rejects immediately if ANY validation error exists.
     * - Atomic transaction: rolling back on any failure to guarantee zero partial records.
     */
    @Transactional(rollbackFor = Exception.class)
    public BulkImportResponse importReadings(String ownerEmail, Long buildingId, Long cycleId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Uploaded CSV file is empty. Please select a valid CSV file.");
        }

        Building building = verifyBuildingOwnership(ownerEmail, buildingId);
        BillingCycle cycle = billingCycleRepository.findById(cycleId)
                .orElseThrow(() -> new ResourceNotFoundException("Billing cycle not found with id: " + cycleId));

        if (!cycle.getBuilding().getBuildingId().equals(buildingId)) {
            throw new BadRequestException("Billing cycle #" + cycleId + " does not belong to building '" + building.getBuildingName() + "'.");
        }

        if (cycle.getStatus() != BillingCycle.CycleStatus.OPEN) {
            throw new BadRequestException("Billing cycle #" + cycleId + " is " + cycle.getStatus() + ". Meter readings can only be imported for OPEN billing cycles.");
        }

        // 1. Run complete validation using existing application validation rules
        BulkValidationResponse validation = validateCsv(ownerEmail, buildingId, cycleId, file);

        if (validation.getErrorCount() > 0) {
            throw new BadRequestException("CSV validation failed with " + validation.getErrorCount() + " error(s). Please fix validation errors before submitting.");
        }

        if (validation.getRows() == null || validation.getRows().isEmpty()) {
            throw new BadRequestException("CSV file contains no meter reading rows.");
        }

        // 2. Prepare usage records for all valid rows
        List<WaterUsage> usageRecordsToSave = new ArrayList<>();
        int anomaliesCount = 0;
        double totalUnits = 0.0;
        int month = cycle.getStartDate().getMonthValue();
        int year = cycle.getStartDate().getYear();

        for (BulkReadingRowDTO row : validation.getRows()) {
            if (row.getStatus() != RowStatus.VALID) {
                continue;
            }

            WaterMeter meter = waterMeterRepository.findById(row.getMeterId())
                    .orElseThrow(() -> new ResourceNotFoundException("Water meter not found for id: " + row.getMeterId()));

            double prevReading = row.getPreviousReading() != null ? row.getPreviousReading() : 0.0;
            double curReading = row.getCurrentReading();
            double units = row.getUnitsConsumed() != null ? row.getUnitsConsumed() : Math.round((curReading - prevReading) * 100.0) / 100.0;
            totalUnits += units;

            String anomalyStatus = "NORMAL";
            if (!"NORMAL".equalsIgnoreCase(anomalyStatus) && !"OK".equalsIgnoreCase(anomalyStatus) && !"NONE".equalsIgnoreCase(anomalyStatus) && anomalyStatus != null && !anomalyStatus.isBlank()) {
                anomaliesCount++;
            }

            // Deduplication / Upsert: find existing usage for this meter + cycle
            WaterUsage usage = usageRepository.findTopByMeterMeterIdAndBillingCycleIdOrderByUsageIdDesc(meter.getMeterId(), cycle.getId())
                    .orElse(null);

            if (usage != null) {
                usage.setPreviousReading(prevReading);
                usage.setCurrentReading(curReading);
                usage.setUnitsConsumed(units);
                usage.setAnomalyStatus(anomalyStatus);
                usage.setMonth(month);
                usage.setYear(year);
                usage.setRecordedAt(LocalDateTime.now());
            } else {
                usage = WaterUsage.builder()
                        .meter(meter)
                        .billingCycle(cycle)
                        .month(month)
                        .year(year)
                        .previousReading(prevReading)
                        .currentReading(curReading)
                        .unitsConsumed(units)
                        .anomalyStatus(anomalyStatus)
                        .recordedAt(LocalDateTime.now())
                        .build();
            }
            usageRecordsToSave.add(usage);

            // Potential leak notification
            if ("POTENTIAL_LEAK".equals(anomalyStatus) && meter.getResident() != null) {
                Resident resident = meter.getResident();
                String title = "🚨 Potential Water Leak Detected!";
                String msg = "Alert! Meter #" + meter.getMeterNumber() + " registered " + units + " kL consumption, exceeding historical threshold. Potential leak detected!";
                Notification notification = Notification.builder()
                        .resident(resident)
                        .title(title)
                        .message(msg)
                        .isRead(false)
                        .build();
                notificationRepository.save(notification);
            }
        }

        if (usageRecordsToSave.isEmpty()) {
            throw new BadRequestException("No valid meter readings found to import.");
        }

        // 3. Persist water usage records atomically
        usageRepository.saveAllAndFlush(usageRecordsToSave);



        log.info("Successfully imported {} water meter readings for building '{}', cycle #{}. Cycle remains OPEN.",
                usageRecordsToSave.size(), building.getBuildingName(), cycleId);

        return BulkImportResponse.builder()
                .buildingId(buildingId)
                .buildingName(building.getBuildingName())
                .billingCycleId(cycleId)
                .cyclePeriod(cycle.getStartDate() + " to " + cycle.getEndDate())
                .cycleStatus(cycle.getStatus().name())
                .cycleFinalized(false)
                .billsGeneratedCount(0)
                .totalAmountBilled(0.0)
                .totalUnitsBilled(Math.round(totalUnits * 100.0) / 100.0)
                .anomaliesDetected(anomaliesCount)
                .message("Successfully imported " + usageRecordsToSave.size() + " meter readings. Billing cycle #" + cycleId + " remains OPEN.")
                .build();
    }

    /**
     * Executes bulk meter reading import, persists WaterUsage records, and finalizes the cycle via BillingCycleService.
     * 
     * SAFEGUARDS ENFORCED:
     * 1. Concurrency-safe: Locks cycle using findByIdWithLock(cycleId) (PESSIMISTIC_WRITE).
     * 2. Idempotency: Rejects if cycle.getStatus() != OPEN.
     * 3. Zero-Trust Verification: Matches uploaded previous reading against trusted DB reading (flags mismatches).
     * 4. Complete Resident Coverage: Rejects if ANY eligible resident in building is missing in CSV.
     * 5. Zero-Tolerance Validation: Rejects immediately if ANY row has errors (decreasing reading, negative, etc.).
     * 6. Transactional Atomicity: @Transactional(rollbackFor = Exception.class) guarantees all WaterUsage and finalized records roll back on failure.
     * 7. Duplicate Bill Prevention: Refuses to overwrite or delete existing finalized bills.
     * 8. Reuses existing BillingCycleService and TariffCalculationService (no tariff duplication or fallback).
     */
    @Transactional(rollbackFor = Exception.class)
    public BulkImportResponse generateBills(String ownerEmail, Long buildingId, Long cycleId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Uploaded CSV file is empty. Please select a valid CSV file.");
        }

        Building building = verifyBuildingOwnership(ownerEmail, buildingId);

        // 1. Concurrency lock on billing cycle
        BillingCycle cycle = billingCycleRepository.findByIdWithLock(cycleId)
                .orElseThrow(() -> new ResourceNotFoundException("Billing cycle not found with id: " + cycleId));

        if (!cycle.getBuilding().getBuildingId().equals(buildingId)) {
            throw new BadRequestException("Billing cycle #" + cycleId + " does not belong to building '" + building.getBuildingName() + "'.");
        }

        // 2. Strict Cycle Status Guard
        if (cycle.getStatus() != BillingCycle.CycleStatus.OPEN) {
            throw new BadRequestException("Billing cycle #" + cycleId + " is " + cycle.getStatus() + ". Bills can only be generated for OPEN billing cycles.");
        }

        // 3. Existing Bills Guard (Ensure cycle hasn't had bills generated previously)
        List<Resident> buildingResidents = residentRepository.findByBuildingBuildingId(buildingId).stream()
                .filter(this::isCurrentResident)
                .collect(Collectors.toList());
        if (buildingResidents.isEmpty()) {
            throw new BadRequestException("No active residents found in building '" + building.getBuildingName() + "'. Cannot generate bills.");
        }

        for (Resident r : buildingResidents) {
            if (billRepository.findByBillingCycleIdAndResidentResidentId(cycleId, r.getResidentId()).isPresent()) {
                throw new BadRequestException("Resident " + r.getName() + " already has a finalized bill in cycle #" + cycleId + ". Cannot regenerate.");
            }
        }

        // 4. Parse CSV
        List<List<String>> csvRows;
        try {
            csvRows = CsvHelper.parseCsv(file.getInputStream());
        } catch (IOException e) {
            throw new BadRequestException("Failed to read CSV file: " + e.getMessage());
        }

        if (csvRows.isEmpty() || csvRows.size() < 2) {
            throw new BadRequestException("CSV file contains no data rows.");
        }

        List<String> headerRow = csvRows.get(0);
        Map<String, Integer> colMap = buildHeaderMap(headerRow);
        validateRequiredHeaders(colMap);

        Map<Long, Resident> residentMap = new HashMap<>();
        for (Resident r : buildingResidents) {
            residentMap.put(r.getResidentId(), r);
        }

        Set<Long> seenResidentIds = new HashSet<>();
        Set<Long> seenMeterIds = new HashSet<>();
        List<WaterUsage> usageRecordsToSave = new ArrayList<>();
        int anomaliesCount = 0;
        double totalUnitsBilled = 0.0;

        // 5. Zero-Trust Validation & Usage Preparation
        for (int i = 1; i < csvRows.size(); i++) {
            List<String> rowData = csvRows.get(i);
            int rowNumber = i + 1;

            // Resident ID
            String resIdStr = getColumnValue(rowData, colMap, "resident_id");
            if (resIdStr == null || resIdStr.isBlank()) {
                throw new BadRequestException("Row " + rowNumber + ": Missing resident_id.");
            }
            Long residentId;
            try {
                residentId = Long.parseLong(resIdStr.trim());
            } catch (NumberFormatException nfe) {
                throw new BadRequestException("Row " + rowNumber + ": Invalid numeric resident_id '" + resIdStr + "'.");
            }

            Resident resident = residentMap.get(residentId);
            if (resident == null) {
                throw new BadRequestException("Row " + rowNumber + ": Resident ID " + residentId + " does not belong to building '" + building.getBuildingName() + "'.");
            }

            if (!seenResidentIds.add(residentId)) {
                throw new BadRequestException("Row " + rowNumber + ": Duplicate resident ID " + residentId + " in CSV.");
            }

            // Meter ID
            String meterIdStr = getColumnValue(rowData, colMap, "meter_id");
            String meterNumFromCsv = getColumnValue(rowData, colMap, "meter_number");
            Long meterId = null;
            if (meterIdStr != null && !meterIdStr.isBlank()) {
                try {
                    meterId = Long.parseLong(meterIdStr.trim());
                } catch (NumberFormatException ignored) {}
            }

            WaterMeter meter = null;
            if (meterId != null) {
                meter = waterMeterRepository.findById(meterId).orElse(null);
            }

            if (meter == null && meterNumFromCsv != null && !meterNumFromCsv.isBlank()) {
                meter = waterMeterRepository.findByMeterNumber(meterNumFromCsv.trim()).orElse(null);
            }

            if (meter == null) {
                meter = waterMeterRepository.findFirstByResidentResidentId(residentId)
                        .or(() -> waterMeterRepository.findByResidentResidentId(residentId))
                        .orElse(null);
            }

            if (meter == null && resident.getApartmentNumber() != null) {
                List<WaterMeter> bMeters = waterMeterRepository.findByBuildingBuildingId(buildingId);
                meter = bMeters.stream()
                        .filter(m -> m.getApartment() != null && resident.getApartmentNumber().equalsIgnoreCase(m.getApartment().getRoomNumber()))
                        .findFirst()
                        .orElse(null);
            }

            if (meter == null && resident.getApartmentNumber() != null) {
                String mNum = "WM-" + building.getBuildingNumber() + "-" + resident.getApartmentNumber();
                meter = waterMeterRepository.findByMeterNumber(mNum).orElse(null);
            }

            if (meter == null) {
                String mNum = (meterNumFromCsv != null && !meterNumFromCsv.isBlank())
                        ? meterNumFromCsv.trim()
                        : "WM-" + building.getBuildingNumber() + "-" + (resident.getApartmentNumber() != null ? resident.getApartmentNumber() : "RES-" + residentId);
                Apartment apt = resident.getApartmentNumber() != null
                        ? apartmentRepository.findByBuildingBuildingIdAndRoomNumber(buildingId, resident.getApartmentNumber()).orElse(null)
                        : null;
                meter = WaterMeter.builder()
                        .building(building)
                        .apartment(apt)
                        .resident(resident)
                        .meterNumber(mNum)
                        .installationDate(LocalDate.now())
                        .status(WaterMeter.MeterStatus.ACTIVE)
                        .build();
                meter = waterMeterRepository.save(meter);
            }

            if (!meter.getBuilding().getBuildingId().equals(buildingId)) {
                throw new BadRequestException("Row " + rowNumber + ": Water meter #" + meter.getMeterNumber() + " belongs to a different building.");
            }

            if (meter.getResident() != null && !residentId.equals(meter.getResident().getResidentId())) {
                throw new BadRequestException("Row " + rowNumber + ": Water meter #" + meter.getMeterNumber() + " is already assigned to resident " + meter.getResident().getName() + ".");
            }

            // Ensure unassigned meter is linked to this resident
            if (meter.getResident() == null) {
                meter.setResident(resident);
                meter = waterMeterRepository.save(meter);
            }

            if (!seenMeterIds.add(meter.getMeterId())) {
                throw new BadRequestException("Row " + rowNumber + ": Duplicate water meter #" + meter.getMeterNumber() + " in CSV.");
            }

            // Trusted DB Previous Reading Verification
            double trustedPrevReading = getTrustedPreviousReading(meter.getMeterId(), cycleId);

            String prevStr = getColumnValue(rowData, colMap, "previous_reading");
            if (prevStr != null && !prevStr.isBlank()) {
                try {
                    double csvPrevReading = Double.parseDouble(prevStr.trim());
                    if (Math.abs(csvPrevReading - trustedPrevReading) > 0.001) {
                        throw new BadRequestException(String.format(Locale.US,
                                "Row %d: Previous reading mismatch for resident %s (Meter: %s). Uploaded: %.2f, Trusted Database: %.2f. Tampering detected.",
                                rowNumber, resident.getName(), meter.getMeterNumber(), csvPrevReading, trustedPrevReading));
                    }
                } catch (NumberFormatException nfe) {
                    throw new BadRequestException("Row " + rowNumber + ": Invalid numeric previous_reading '" + prevStr + "'.");
                }
            }

            // Current Reading
            String curStr = getColumnValue(rowData, colMap, "current_reading");
            if (curStr == null || curStr.isBlank()) {
                throw new BadRequestException("Row " + rowNumber + ": Missing current_reading value.");
            }
            double curReading;
            try {
                curReading = Double.parseDouble(curStr.trim());
            } catch (NumberFormatException nfe) {
                throw new BadRequestException("Row " + rowNumber + ": Invalid numeric current_reading '" + curStr + "'.");
            }

            if (curReading < 0) {
                throw new BadRequestException("Row " + rowNumber + ": Current reading cannot be negative (" + curReading + ").");
            }
            if (curReading < trustedPrevReading) {
                throw new BadRequestException(String.format(Locale.US,
                        "Row %d: Decreasing meter reading for %s: Current reading (%.2f) cannot be less than previous reading (%.2f).",
                        rowNumber, resident.getName(), curReading, trustedPrevReading));
            }

            double units = Math.round((curReading - trustedPrevReading) * 100.0) / 100.0;
            totalUnitsBilled += units;

            String anomalyStatus = "NORMAL";
            if (!"NORMAL".equalsIgnoreCase(anomalyStatus) && !"OK".equalsIgnoreCase(anomalyStatus) && !"NONE".equalsIgnoreCase(anomalyStatus) && anomalyStatus != null && !anomalyStatus.isBlank()) {
                anomaliesCount++;
            }

            int month = cycle.getStartDate().getMonthValue();
            int year = cycle.getStartDate().getYear();

            // Find or create WaterUsage for this meter + billingCycle
            WaterUsage usage = usageRepository.findTopByMeterMeterIdAndBillingCycleIdOrderByUsageIdDesc(meter.getMeterId(), cycle.getId())
                    .orElse(null);

            if (usage != null) {
                usage.setPreviousReading(trustedPrevReading);
                usage.setCurrentReading(curReading);
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
                        .previousReading(trustedPrevReading)
                        .currentReading(curReading)
                        .unitsConsumed(units)
                        .anomalyStatus(anomalyStatus)
                        .recordedAt(LocalDateTime.now())
                        .build();
            }
            usageRecordsToSave.add(usage);
        }

        // 6. Verify 100% Resident Coverage Invariant
        List<String> missingResidents = new ArrayList<>();
        for (Resident r : buildingResidents) {
            if (!seenResidentIds.contains(r.getResidentId())) {
                missingResidents.add(r.getName() + " (" + r.getApartmentNumber() + ")");
            }
        }
        if (!missingResidents.isEmpty()) {
            throw new BadRequestException("Cannot finalize billing cycle. Missing meter readings for " + missingResidents.size() + " resident(s):\n• "
                    + String.join("\n• ", missingResidents) + "\nPlease provide readings for all residents.");
        }

        // 7. Persist WaterUsage records within the transaction
        usageRepository.saveAllAndFlush(usageRecordsToSave);



        // 8. Invoke BillingCycleService.finalizeCycle (joins existing @Transactional)
        BillingCycle finalizedCycle = billingCycleService.finalizeCycle(cycleId);

        // 9. Fetch generated bills sum for accurate reporting
        List<Bill> bills = billRepository.findByOwnerId(building.getOwner().getOwnerId());
        double cycleAmount = 0.0;
        int cycleBillsCount = 0;
        for (Bill b : bills) {
            if (b.getBillingCycle() != null && b.getBillingCycle().getId().equals(cycleId)) {
                cycleAmount += b.getAmount();
                cycleBillsCount++;
            }
        }

        return BulkImportResponse.builder()
                .buildingId(buildingId)
                .buildingName(building.getBuildingName())
                .billingCycleId(cycleId)
                .cyclePeriod(cycle.getStartDate() + " to " + cycle.getEndDate())
                .billsGeneratedCount(cycleBillsCount)
                .totalUnitsBilled(Math.round(totalUnitsBilled * 100.0) / 100.0)
                .totalAmountBilled(Math.round(cycleAmount * 100.0) / 100.0)
                .anomaliesDetected(anomaliesCount)
                .cycleFinalized(true)
                .cycleStatus(finalizedCycle.getStatus().name())
                .message("Successfully imported " + usageRecordsToSave.size() + " meter readings and finalized billing cycle #" + cycleId + ".")
                .build();
    }

    private Building verifyBuildingOwnership(String ownerEmail, Long buildingId) {
        Building building = buildingRepository.findById(buildingId)
                .orElseThrow(() -> new ResourceNotFoundException("Building not found with id: " + buildingId));

        BuildingOwner owner = ownerService.getOwnerByUserEmail(ownerEmail);
        if (!building.getOwner().getOwnerId().equals(owner.getOwnerId())) {
            throw new BadRequestException("Unauthorized: You do not own building '" + building.getBuildingName() + "'.");
        }
        return building;
    }

    private Map<String, Integer> buildHeaderMap(List<String> headerRow) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < headerRow.size(); i++) {
            String colName = headerRow.get(i).trim().toLowerCase().replace(" ", "_");
            map.put(colName, i);
        }
        return map;
    }

    private void validateRequiredHeaders(Map<String, Integer> map) {
        if (!map.containsKey("resident_id")) {
            throw new BadRequestException("CSV is missing required header: 'resident_id'");
        }
        if (!map.containsKey("current_reading")) {
            throw new BadRequestException("CSV is missing required header: 'current_reading'");
        }
    }

    private String getColumnValue(List<String> row, Map<String, Integer> colMap, String colName) {
        Integer idx = colMap.get(colName);
        if (idx == null || idx >= row.size()) {
            return null;
        }
        return row.get(idx);
    }

    private double calculateEstimatedCharge(double units, TariffPlan tariff) {
        if (tariff == null) {
            return Math.round((units * 3.0 + 10.0) * 100.0) / 100.0;
        }

        BigDecimal t1Limit = tariff.getTier1Limit() != null ? tariff.getTier1Limit() : new BigDecimal("20.00");
        BigDecimal t1Rate = tariff.getTier1Rate() != null && tariff.getTier1Rate().compareTo(BigDecimal.ZERO) > 0 ? tariff.getTier1Rate() : new BigDecimal("2.50");

        BigDecimal t2Limit = tariff.getTier2Limit() != null ? tariff.getTier2Limit() : new BigDecimal("40.00");
        BigDecimal t2Rate = tariff.getTier2Rate() != null && tariff.getTier2Rate().compareTo(BigDecimal.ZERO) > 0 ? tariff.getTier2Rate() : new BigDecimal("4.00");

        BigDecimal t3Rate = tariff.getTier3Rate() != null && tariff.getTier3Rate().compareTo(BigDecimal.ZERO) > 0 ? tariff.getTier3Rate() : new BigDecimal("6.00");

        BigDecimal unitsBd = BigDecimal.valueOf(units);
        BigDecimal tier1Units = unitsBd.min(t1Limit);
        BigDecimal tier2Units = unitsBd.subtract(t1Limit).max(BigDecimal.ZERO).min(t2Limit.subtract(t1Limit));
        BigDecimal tier3Units = unitsBd.subtract(t2Limit).max(BigDecimal.ZERO);

        BigDecimal tier1Amount = tier1Units.multiply(t1Rate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal tier2Amount = tier2Units.multiply(t2Rate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal tier3Amount = tier3Units.multiply(t3Rate).setScale(2, RoundingMode.HALF_UP);

        BigDecimal total = tier1Amount.add(tier2Amount).add(tier3Amount);
        return total.doubleValue();
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
}
