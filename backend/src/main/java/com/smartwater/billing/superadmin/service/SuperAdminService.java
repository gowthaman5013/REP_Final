package com.smartwater.billing.superadmin.service;

import com.smartwater.billing.common.dto.DashboardDTOs.*;
import com.smartwater.billing.common.entity.Bill;
import com.smartwater.billing.common.entity.Invoice;
import com.smartwater.billing.common.entity.User;
import com.smartwater.billing.common.exception.BadRequestException;
import com.smartwater.billing.common.exception.ResourceNotFoundException;
import com.smartwater.billing.common.repository.*;
import com.smartwater.billing.common.service.EmailService;
import com.smartwater.billing.resident.entity.Notification;
import com.smartwater.billing.resident.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import com.smartwater.billing.owner.entity.Building;
import com.smartwater.billing.owner.entity.BuildingOwner;
import com.smartwater.billing.owner.repository.*;
import com.smartwater.billing.owner.service.BuildingOwnerService;
import com.smartwater.billing.resident.entity.Resident;
import com.smartwater.billing.resident.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import com.smartwater.billing.owner.entity.OwnerHistory;
import com.smartwater.billing.owner.repository.OwnerHistoryRepository;
import java.time.LocalDateTime;

import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SuperAdminService {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminService.class);

    private final BuildingOwnerRepository ownerRepository;
    private final BuildingRepository buildingRepository;
    private final ResidentRepository residentRepository;
    private final BillRepository billRepository;
    private final InvoiceRepository invoiceRepository;
    private final NotificationRepository notificationRepository;
    private final EmailService emailService;
    private final UserRepository userRepository;
    private final BuildingOwnerService buildingOwnerService;
    private final OwnerHistoryRepository ownerHistoryRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public SuperAdminService(
            BuildingOwnerRepository ownerRepository,
            BuildingRepository buildingRepository,
            ResidentRepository residentRepository,
            BillRepository billRepository,
            InvoiceRepository invoiceRepository,
            NotificationRepository notificationRepository,
            EmailService emailService,
            UserRepository userRepository,
            BuildingOwnerService buildingOwnerService,
            OwnerHistoryRepository ownerHistoryRepository
    ) {
        this.ownerRepository = ownerRepository;
        this.buildingRepository = buildingRepository;
        this.residentRepository = residentRepository;
        this.billRepository = billRepository;
        this.invoiceRepository = invoiceRepository;
        this.notificationRepository = notificationRepository;
        this.emailService = emailService;
        this.userRepository = userRepository;
        this.buildingOwnerService = buildingOwnerService;
        this.ownerHistoryRepository = ownerHistoryRepository;
    }

    public AdminDashboardStats getDashboardStats() {
        long totalOwners = ownerRepository.count();
        long pendingOwners = ownerRepository.findByApprovalStatus(BuildingOwner.ApprovalStatus.PENDING).size();
        long totalBuildings = buildingRepository.count();
        long totalResidents = residentRepository.findAll().stream()
                .filter(r -> r.getAccountStatus() == Resident.AccountStatus.ACTIVE
                        && r.getApartmentNumber() != null
                        && !r.getApartmentNumber().equalsIgnoreCase("PENDING")
                        && !r.getApartmentNumber().equalsIgnoreCase("REJECTED")
                        && !r.getApartmentNumber().startsWith("FORMER-")
                        && !r.getApartmentNumber().equalsIgnoreCase("Vacated"))
                .count();
        Double totalRev = billRepository.getTotalSystemRevenue();
        double totalSystemRevenue = totalRev != null ? totalRev : 0.0;

        List<BuildingOwnerDTO> recentOwners = ownerRepository.findAll().stream()
                .map(this::convertToOwnerDTO)
                .collect(Collectors.toList());

        return AdminDashboardStats.builder()
                .totalOwners(totalOwners)
                .pendingOwners(pendingOwners)
                .totalBuildings(totalBuildings)
                .totalResidents(totalResidents)
                .totalSystemRevenue(totalSystemRevenue)
                .recentOwners(recentOwners)
                .build();
    }

    public List<BuildingOwnerDTO> getAllOwners() {
        return ownerRepository.findAll().stream()
                .map(this::convertToOwnerDTO)
                .collect(Collectors.toList());
    }

    public List<BuildingOwnerDTO> getPendingOwners() {
        return ownerRepository.findByApprovalStatus(BuildingOwner.ApprovalStatus.PENDING).stream()
                .map(this::convertToOwnerDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public BuildingOwnerDTO approveOwner(Long ownerId) {
        BuildingOwner owner = ownerRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Building Owner not found with id: " + ownerId));

        owner.setApprovalStatus(BuildingOwner.ApprovalStatus.APPROVED);
        User user = owner.getUser();
        if (user != null) {
            user.setStatus(User.UserStatus.APPROVED);
            userRepository.save(user);
        }
        ownerRepository.save(owner);

        return convertToOwnerDTO(owner);
    }

    @Transactional
    public BuildingOwnerDTO rejectOwner(Long ownerId) {
        BuildingOwner owner = ownerRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Building Owner not found with id: " + ownerId));

        owner.setApprovalStatus(BuildingOwner.ApprovalStatus.REJECTED);
        User user = owner.getUser();
        if (user != null) {
            user.setStatus(User.UserStatus.REJECTED);
            userRepository.save(user);
        }
        ownerRepository.save(owner);

        return convertToOwnerDTO(owner);
    }

    @Transactional(noRollbackFor = BadRequestException.class)
    public void deleteOwner(Long ownerId) {
        BuildingOwner owner = ownerRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Building Owner not found with id: " + ownerId));

        // 1. Check if any resident under this owner has an outstanding/unpaid balance payment
        List<Bill> unpaidBills = billRepository.findUnpaidBillsByOwnerId(ownerId);
        List<Invoice> unpaidInvoices = invoiceRepository.findUnpaidInvoicesByOwnerId(ownerId);

        Map<Long, ResidentBalanceRecord> balanceMap = new LinkedHashMap<>();

        for (Bill b : unpaidBills) {
            Resident r = b.getResident();
            if (r != null) {
                double amt = b.getAmount() != null ? b.getAmount() : 0.0;
                Long cycleId = b.getBillingCycle() != null ? b.getBillingCycle().getId() : null;
                balanceMap.computeIfAbsent(r.getResidentId(), k -> new ResidentBalanceRecord(r))
                        .addBill(cycleId, amt, b.getBillId(), b.getStatus().name());
            }
        }

        for (Invoice inv : unpaidInvoices) {
            Resident r = inv.getResident();
            if (r != null) {
                double amt = inv.getTotalAmount() != null ? inv.getTotalAmount().doubleValue() : 0.0;
                Long cycleId = inv.getBillingCycle() != null ? inv.getBillingCycle().getId() : null;
                balanceMap.computeIfAbsent(r.getResidentId(), k -> new ResidentBalanceRecord(r))
                        .addInvoice(cycleId, amt, inv.getId(), inv.getStatus().name());
            }
        }

        if (!balanceMap.isEmpty()) {
            double totalBalance = 0.0;
            StringBuilder residentSummary = new StringBuilder();

            for (ResidentBalanceRecord rec : balanceMap.values()) {
                totalBalance += rec.getTotalBalance();
                if (residentSummary.length() > 0) residentSummary.append(", ");
                residentSummary.append(String.format("%s (Flat %s: â‚¹%.2f)",
                        rec.getResident().getName(),
                        rec.getResident().getApartmentNumber() != null ? rec.getResident().getApartmentNumber() : "N/A",
                        rec.getTotalBalance()));
            }

            // A. Send in-app and email notifications to each resident who has a pending balance
            for (ResidentBalanceRecord rec : balanceMap.values()) {
                Resident r = rec.getResident();
                String buildingName = (r.getBuilding() != null && r.getBuilding().getBuildingName() != null)
                        ? r.getBuilding().getBuildingName()
                        : "your property";

                try {
                    Notification residentNotif = Notification.builder()
                            .resident(r)
                            .title("Urgent: Super Admin Notice - Settle Outstanding Water Bill")
                            .message(String.format(
                                    "Super Admin has reviewed the property billing records and requested all billing for %s to be finished and settled immediately. " +
                                    "You have an outstanding water balance of â‚¹%.2f. Please clear your dues promptly in your resident dashboard.",
                                    buildingName,
                                    rec.getTotalBalance()
                            ))
                            .isRead(false)
                            .createdAt(LocalDateTime.now())
                            .build();
                    notificationRepository.save(residentNotif);
                } catch (Exception ex) {
                    log.warn("Failed to create in-app notification for resident #{}: {}", r.getResidentId(), ex.getMessage());
                }

                if (r.getEmail() != null && !r.getEmail().isBlank()) {
                    try {
                        emailService.sendNotificationEmail(
                                r.getEmail(),
                                r.getName(),
                                "Notice from Super Admin: Settle Outstanding Water Bill",
                                String.format(
                                        "Super Admin has requested all billing to be completed and settled for %s immediately.\n\n" +
                                        "Your resident account currently has an outstanding water bill balance of \u20B9%.2f.\n\n" +
                                        "Please log into your AquaFlow portal at http://localhost:5173/login and complete your payment.",
                                        buildingName,
                                        rec.getTotalBalance()
                                )
                        );
                    } catch (Exception ex) {
                        log.warn("Failed to send email to resident {}: {}", r.getEmail(), ex.getMessage());
                    }
                }
            }

            // B. Send email notification to the Building Owner
            String ownerEmail = owner.getUser() != null ? owner.getUser().getEmail() : "N/A";
            String ownerPhone = owner.getPhone() != null ? owner.getPhone() : "";

            if (ownerEmail != null && !ownerEmail.isBlank() && !"N/A".equalsIgnoreCase(ownerEmail)) {
                try {
                    emailService.sendNotificationEmail(
                            ownerEmail,
                            owner.getName(),
                            "URGENT: Super Admin Request - Settle All Billing Before Account Deletion",
                            String.format(
                                    "Super Admin attempted to delete your building owner account, but the action was BLOCKED because %d resident(s) have outstanding balance payments totaling \u20B9%.2f.\n\n" +
                                    "Super Admin has explicitly requested that you finish all billing cycles, collect outstanding resident balances, and clear all pending dues before this account can be deleted.\n\n" +
                                    "Residents with pending balances:\n%s",
                                    balanceMap.size(),
                                    totalBalance,
                                    residentSummary.toString()
                            )
                    );
                } catch (Exception ex) { 
                    log.warn("Failed to send notification email to building owner {}: {}", ownerEmail, ex.getMessage());
                }
            }

            // C. Log DELETION_BLOCKED in owner_history
            try {
                entityManager.createNativeQuery(
                        "INSERT INTO owner_history (owner_id, owner_name, email, phone, event_type, details, actor, timestamp) " +
                        "VALUES (:oid, :oname, :email, :phone, 'DELETION_BLOCKED', :details, 'System Super Admin', NOW())"
                )
                .setParameter("oid", ownerId)
                .setParameter("oname", owner.getName())
                .setParameter("email", ownerEmail)
                .setParameter("phone", ownerPhone)
                .setParameter("details", String.format(
                        "Deletion blocked: %d resident(s) have pending balance payments totaling â‚¹%.2f. Super Admin requested owner and residents to finish all billing.",
                        balanceMap.size(), totalBalance))
                .executeUpdate();
            } catch (Exception ex) {
                log.warn("Failed to log DELETION_BLOCKED event in owner_history: {}", ex.getMessage());
            }

            // D. Notify Super Admin by throwing BadRequestException
            throw new BadRequestException(String.format(
                    "Cannot delete building owner '%s': %d resident(s) have pending balance payments totaling â‚¹%.2f [%s]. " +
                    "Super Admin has blocked deletion and dispatched notifications requesting all billing to be finished and settled.",
                    owner.getName(),
                    balanceMap.size(),
                    totalBalance,
                    residentSummary.toString()
            ));
        }

        // 2. Log REMOVED event into owner_history table before deletion
        String email = owner.getUser() != null ? owner.getUser().getEmail() : "N/A";
        String phone = owner.getPhone() != null ? owner.getPhone() : "";
        Long userId = owner.getUser() != null ? owner.getUser().getUserId() : null;

        try {
            entityManager.createNativeQuery(
                    "INSERT INTO owner_history (owner_id, owner_name, email, phone, event_type, details, actor, timestamp) " +
                    "VALUES (:oid, :oname, :email, :phone, 'REMOVED', 'Building owner removed from platform by Super Admin.', 'System Super Admin', NOW())"
            )
            .setParameter("oid", ownerId)
            .setParameter("oname", owner.getName())
            .setParameter("email", email)
            .setParameter("phone", phone)
            .executeUpdate();
        } catch (Exception ignored) {}

        try {
            entityManager.createNativeQuery("SET FOREIGN_KEY_CHECKS = 0").executeUpdate();

            // 2. Cascade delete all buildings owned by this owner
            List<Building> buildings = buildingRepository.findByOwner(owner);
            for (Building b : buildings) {
                buildingOwnerService.deleteBuildingCascade(b.getBuildingId());
            }

            // 3. Delete resident invitations issued by this owner
            entityManager.createNativeQuery("DELETE FROM resident_invitations WHERE invited_by_id = :ownerId")
                    .setParameter("ownerId", ownerId)
                    .executeUpdate();

            // 4. Delete building owner profile
            entityManager.createNativeQuery("DELETE FROM building_owners WHERE owner_id = :ownerId")
                    .setParameter("ownerId", ownerId)
                    .executeUpdate();

            // 5. Delete user account if not super admin
            if (userId != null) {
                entityManager.createNativeQuery("UPDATE household_size_history SET changed_by_user_id = NULL WHERE changed_by_user_id = :uid")
                        .setParameter("uid", userId).executeUpdate();
                entityManager.createNativeQuery("DELETE FROM leak_feedback WHERE submitted_by_user_id = :uid")
                        .setParameter("uid", userId).executeUpdate();
                entityManager.createNativeQuery("DELETE FROM users WHERE user_id = :userId AND role != 'SUPER_ADMIN'")
                        .setParameter("userId", userId)
                        .executeUpdate();
            }
        } finally {
            entityManager.createNativeQuery("SET FOREIGN_KEY_CHECKS = 1").executeUpdate();
            entityManager.clear();
        }
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<SystemHistoryItemDTO> getSystemHistory() {
        List<SystemHistoryItemDTO> items = new ArrayList<>();

        // 1. Current / Joined Building Owners
        for (BuildingOwner owner : ownerRepository.findAll()) {
            String ts = owner.getCreatedAt() != null ? owner.getCreatedAt().toString() : "2026-10-14T00:00:00";
            String email = owner.getUser() != null ? owner.getUser().getEmail() : "N/A";
            String phone = owner.getPhone() != null && !owner.getPhone().isEmpty() ? owner.getPhone() : "Not provided";
            String status = owner.getApprovalStatus() == BuildingOwner.ApprovalStatus.APPROVED ? "JOINED" : owner.getApprovalStatus().name();
            items.add(new SystemHistoryItemDTO(
                    "JOIN-" + owner.getOwnerId(),
                    "JOINED",
                    "Building Owner Joined: " + owner.getName(),
                    "Owner account registered with email " + email + " and phone " + phone + " (Approval Status: " + owner.getApprovalStatus() + ")",
                    ts,
                    status,
                    owner.getName()
            ));
        }

        // 2. Removed / Left Building Owners from owner_history table
        try {
            List<OwnerHistory> histories = ownerHistoryRepository.findAllByOrderByTimestampDesc();
            for (OwnerHistory h : histories) {
                items.add(new SystemHistoryItemDTO(
                        "HIST-" + h.getId(),
                        h.getEventType() != null ? h.getEventType() : "REMOVED",
                        "Building Owner Removed: " + h.getOwnerName(),
                        h.getDetails() != null ? h.getDetails() : "Building owner removed from platform. Contact: " + h.getEmail() + " / " + h.getPhone(),
                        h.getTimestamp() != null ? h.getTimestamp().toString() : "2026-10-14T00:00:00",
                        "REMOVED",
                        h.getActor() != null ? h.getActor() : "System Super Admin"
                ));
            }
        } catch (Exception ignored) {}

        // Sort by timestamp descending
        items.sort((a, b) -> {
            if (a.getTimestamp() == null) return 1;
            if (b.getTimestamp() == null) return -1;
            return b.getTimestamp().compareTo(a.getTimestamp());
        });

        return items;
    }

    private BuildingOwnerDTO convertToOwnerDTO(BuildingOwner owner) {
        int totalBuildings = buildingRepository.findByOwner(owner).size();
        return BuildingOwnerDTO.builder()
                .ownerId(owner.getOwnerId())
                .userId(owner.getUser() != null ? owner.getUser().getUserId() : null)
                .name(owner.getName())
                .email(owner.getUser() != null ? owner.getUser().getEmail() : "")
                .phone(owner.getPhone())
                .approvalStatus(owner.getApprovalStatus().name())
                .totalBuildings(totalBuildings)
                .createdAt(owner.getCreatedAt() != null ? owner.getCreatedAt().toString() : "")
                .build();
    }

    private static class ResidentBalanceRecord {
        private final Resident resident;
        private final Map<Long, Double> cycleBalances = new HashMap<>();
        private double nonCycleBalance = 0.0;

        public ResidentBalanceRecord(Resident resident) {
            this.resident = resident;
        }

        public Resident getResident() {
            return resident;
        }

        public void addBill(Long cycleId, double amount, Long billId, String status) {
            if (cycleId != null) {
                cycleBalances.put(cycleId, Math.max(cycleBalances.getOrDefault(cycleId, 0.0), amount));
            } else {
                nonCycleBalance += amount;
            }
        }

        public void addInvoice(Long cycleId, double amount, Long invoiceId, String status) {
            if (cycleId != null) {
                cycleBalances.put(cycleId, Math.max(cycleBalances.getOrDefault(cycleId, 0.0), amount));
            } else {
                nonCycleBalance += amount;
            }
        }

        public double getTotalBalance() {
            double total = nonCycleBalance;
            for (double b : cycleBalances.values()) {
                total += b;
            }
            return Math.round(total * 100.0) / 100.0;
        }
    }
}
