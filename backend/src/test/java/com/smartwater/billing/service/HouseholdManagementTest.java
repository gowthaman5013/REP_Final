package com.smartwater.billing.service;

import com.smartwater.billing.common.entity.User;
import com.smartwater.billing.common.exception.BadRequestException;
import com.smartwater.billing.common.config.HouseholdProperties;
import com.smartwater.billing.common.repository.UserRepository;
import com.smartwater.billing.owner.entity.Apartment;
import com.smartwater.billing.owner.entity.Building;
import com.smartwater.billing.owner.entity.BuildingOwner;
import com.smartwater.billing.owner.repository.ApartmentRepository;
import com.smartwater.billing.owner.repository.BuildingOwnerRepository;
import com.smartwater.billing.owner.repository.BuildingRepository;
import com.smartwater.billing.resident.dto.HouseholdDTOs.*;
import com.smartwater.billing.resident.entity.ApartmentOccupancy;
import com.smartwater.billing.resident.entity.ApartmentOccupancy.OccupancyStatus;
import com.smartwater.billing.resident.entity.Resident;
import com.smartwater.billing.resident.repository.ApartmentOccupancyRepository;
import com.smartwater.billing.resident.repository.HouseholdSizeHistoryRepository;
import com.smartwater.billing.resident.repository.ResidentRepository;
import com.smartwater.billing.resident.service.AwayPeriodService;
import com.smartwater.billing.resident.service.HouseholdService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("h2")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:hh_test_db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL;NON_KEYWORDS=MONTH,YEAR",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
    "spring.sql.init.mode=never",
    "spring.mail.host=localhost"
})
@Transactional
public class HouseholdManagementTest {

    @Autowired
    private HouseholdService householdService;

    @Autowired
    private AwayPeriodService awayPeriodService;

    @Autowired
    private HouseholdProperties householdProperties;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BuildingOwnerRepository ownerRepository;

    @Autowired
    private BuildingRepository buildingRepository;

    @Autowired
    private ApartmentRepository apartmentRepository;

    @Autowired
    private ResidentRepository residentRepository;

    @Autowired
    private ApartmentOccupancyRepository occupancyRepository;

    @Autowired
    private HouseholdSizeHistoryRepository historyRepository;

    private User residentUser;
    private Resident resident;
    private Apartment apt;
    private ApartmentOccupancy occupancy;

    @BeforeEach
    void setUp() {
        User ownerUser = userRepository.save(User.builder()
                .name("Owner HH")
                .email("owner.hh@example.com")
                .password("encoded_pass")
                .role(User.Role.BUILDING_OWNER)
                .status(User.UserStatus.ACTIVE)
                .build());

        BuildingOwner owner = ownerRepository.save(BuildingOwner.builder()
                .user(ownerUser)
                .name("Owner HH")
                .phone("9991112222")
                .approvalStatus(BuildingOwner.ApprovalStatus.APPROVED)
                .build());

        Building building = buildingRepository.save(Building.builder()
                .owner(owner)
                .buildingName("HH Gardens")
                .buildingNumber("HH-01")
                .location("North Sector")
                .totalFlats(8)
                .build());

        apt = apartmentRepository.save(Apartment.builder()
                .building(building)
                .roomNumber("201")
                .floorNumber(2)
                .unitType("2BHK")
                .apartmentName("Flat 201")
                .build());

        residentUser = userRepository.save(User.builder()
                .name("Alice Resident")
                .email("alice.resident@example.com")
                .password("encoded_pass")
                .role(User.Role.RESIDENT)
                .status(User.UserStatus.ACTIVE)
                .build());

        resident = residentRepository.save(Resident.builder()
                .user(residentUser)
                .name("Alice Resident")
                .email("alice.resident@example.com")
                .phone("9876543211")
                .apartmentNumber("201")
                .building(building)
                .build());

        occupancy = occupancyRepository.save(ApartmentOccupancy.builder()
                .apartment(apt)
                .resident(resident)
                .startDate(LocalDateTime.now().minusMonths(1))
                .status(OccupancyStatus.ACTIVE)
                .householdSize(3)
                .childrenCount(1)
                .householdSizeSource("DEFAULT_ESTIMATE")
                .householdSizeConfirmed(false)
                .build());
    }

    @Test
    @DisplayName("Unit-type defaults: STUDIO=1, 1BHK=2, 2BHK=3, 3BHK=4, PENTHOUSE=5, unknown=3")
    void testUnitTypeDefaults() {
        assertEquals(1, householdProperties.getDefaultSizeForUnitType("STUDIO"));
        assertEquals(2, householdProperties.getDefaultSizeForUnitType("1BHK"));
        assertEquals(3, householdProperties.getDefaultSizeForUnitType("2BHK"));
        assertEquals(4, householdProperties.getDefaultSizeForUnitType("3BHK"));
        assertEquals(5, householdProperties.getDefaultSizeForUnitType("PENTHOUSE"));
        assertEquals(3, householdProperties.getDefaultSizeForUnitType("VILLA")); // unknown fallback
    }

    @Test
    @DisplayName("Validation: Rejects household size < 1 or > 15")
    void testValidationRejectsOutOfRangeHouseholdSize() {
        assertThrows(BadRequestException.class, () ->
                householdService.updateResidentHousehold(residentUser.getEmail(),
                        HouseholdUpdateRequest.builder().householdSize(0).childrenCount(0).build()));

        assertThrows(BadRequestException.class, () ->
                householdService.updateResidentHousehold(residentUser.getEmail(),
                        HouseholdUpdateRequest.builder().householdSize(16).childrenCount(0).build()));
    }

    @Test
    @DisplayName("Validation: Rejects negative children count or children > household size")
    void testValidationRejectsInvalidChildrenCount() {
        assertThrows(BadRequestException.class, () ->
                householdService.updateResidentHousehold(residentUser.getEmail(),
                        HouseholdUpdateRequest.builder().householdSize(3).childrenCount(-1).build()));

        assertThrows(BadRequestException.class, () ->
                householdService.updateResidentHousehold(residentUser.getEmail(),
                        HouseholdUpdateRequest.builder().householdSize(3).childrenCount(4).build()));
    }

    @Test
    @DisplayName("Sanity warning: Flagged when household size > 6")
    void testSanityWarningFlaggedForLargeHousehold() {
        HouseholdDetailResponse response = householdService.updateResidentHousehold(
                residentUser.getEmail(),
                HouseholdUpdateRequest.builder()
                        .householdSize(8)
                        .childrenCount(3)
                        .reason("FAMILY_JOINED")
                        .note("Extended family visiting")
                        .build()
        );

        assertTrue(response.getSanityWarning());
        assertEquals(8, response.getHouseholdSize());
        assertEquals(3, response.getChildrenCount());
        assertEquals("RESIDENT_DECLARED", response.getHouseholdSizeSource());
        assertTrue(response.getHouseholdSizeConfirmed());
    }

    @Test
    @DisplayName("Away period validation: Rejects end < start or duration > 180 days")
    void testAwayPeriodValidation() {
        // End before start
        assertThrows(BadRequestException.class, () ->
                awayPeriodService.createAwayPeriod(residentUser.getEmail(),
                        AwayPeriodRequest.builder()
                                .startDate(LocalDate.now().plusDays(10))
                                .endDate(LocalDate.now().plusDays(5))
                                .build()));

        // Exceeds 180 days
        assertThrows(BadRequestException.class, () ->
                awayPeriodService.createAwayPeriod(residentUser.getEmail(),
                        AwayPeriodRequest.builder()
                                .startDate(LocalDate.now())
                                .endDate(LocalDate.now().plusDays(185))
                                .build()));
    }

    @Test
    @DisplayName("Away period overlap: Rejects overlapping periods within same tenancy")
    void testAwayPeriodRejectsOverlappingIntervals() {
        // Create first away period
        awayPeriodService.createAwayPeriod(residentUser.getEmail(),
                AwayPeriodRequest.builder()
                        .startDate(LocalDate.now().plusDays(10))
                        .endDate(LocalDate.now().plusDays(20))
                        .note("Vacation trip")
                        .build());

        // Attempt overlapping period
        assertThrows(BadRequestException.class, () ->
                awayPeriodService.createAwayPeriod(residentUser.getEmail(),
                        AwayPeriodRequest.builder()
                                .startDate(LocalDate.now().plusDays(15))
                                .endDate(LocalDate.now().plusDays(25))
                                .note("Second trip")
                                .build()));
    }
}
