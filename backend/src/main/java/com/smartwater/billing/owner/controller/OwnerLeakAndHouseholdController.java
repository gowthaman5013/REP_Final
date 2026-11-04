package com.smartwater.billing.owner.controller;

import com.smartwater.billing.resident.dto.HouseholdDTOs.BuildingHouseholdStatusResponse;
import com.smartwater.billing.resident.dto.HouseholdDTOs.HouseholdDetailResponse;
import com.smartwater.billing.resident.dto.HouseholdDTOs.HouseholdUpdateRequest;
import com.smartwater.billing.resident.service.HouseholdService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping
public class OwnerLeakAndHouseholdController {

    private final HouseholdService householdService;

    public OwnerLeakAndHouseholdController(HouseholdService householdService) {
        this.householdService = householdService;
    }

    /**
     * Building-wide household confirmation status for owner.
     */
    @GetMapping({"/owner/buildings/{buildingId}/residents/household-status", "/api/owner/buildings/{buildingId}/residents/household-status"})
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BUILDING_OWNER')")
    public ResponseEntity<List<BuildingHouseholdStatusResponse>> getBuildingHouseholdStatuses(
            Authentication authentication,
            @PathVariable("buildingId") Long buildingId
    ) {
        return ResponseEntity.ok(householdService.getBuildingHouseholdStatuses(authentication.getName(), buildingId));
    }

    /**
     * Owner overrides/updates household size for an apartment.
     */
    @RequestMapping(value = {"/owner/apartments/{apartmentId}/household", "/api/owner/apartments/{apartmentId}/household"}, method = {RequestMethod.POST, RequestMethod.PUT})
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BUILDING_OWNER')")
    public ResponseEntity<HouseholdDetailResponse> updateApartmentHousehold(
            Authentication authentication,
            @PathVariable("apartmentId") Long apartmentId,
            @Valid @RequestBody HouseholdUpdateRequest request
    ) {
        return ResponseEntity.ok(householdService.ownerUpdateHousehold(authentication.getName(), apartmentId, request));
    }
}
