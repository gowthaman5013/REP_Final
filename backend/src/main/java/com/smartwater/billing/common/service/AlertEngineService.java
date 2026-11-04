package com.smartwater.billing.common.service;

import com.smartwater.billing.common.entity.*;
import com.smartwater.billing.resident.entity.*;
import com.smartwater.billing.resident.repository.NotificationRepository;
import com.smartwater.billing.resident.repository.ResidentRepository;
import com.smartwater.billing.common.repository.WaterMeterRepository;
import com.smartwater.billing.common.repository.WaterUsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AlertEngineService {

    private static final Logger log = LoggerFactory.getLogger(AlertEngineService.class);

    private final WaterMeterRepository waterMeterRepository;
    private final WaterUsageRepository waterUsageRepository;
    private final NotificationRepository notificationRepository;
    private final ResidentRepository residentRepository;
    private final EmailService emailService;

    public AlertEngineService(WaterMeterRepository waterMeterRepository,
                              WaterUsageRepository waterUsageRepository,
                              NotificationRepository notificationRepository,
                              ResidentRepository residentRepository,
                              EmailService emailService) {
        this.waterMeterRepository = waterMeterRepository;
        this.waterUsageRepository = waterUsageRepository;
        this.notificationRepository = notificationRepository;
        this.residentRepository = residentRepository;
        this.emailService = emailService;
    }

    public void checkOverUsageAlerts() {
        log.info("Running daily Over-Usage Alert Audit...");
        List<Resident> residents = residentRepository.findAll();
        for (Resident resident : residents) {
            WaterMeter meter = waterMeterRepository.findByResidentResidentId(resident.getResidentId()).orElse(null);
            if (meter != null) {
                List<WaterUsage> usageList = waterUsageRepository.findByMeterMeterIdOrderByYearDescMonthDesc(meter.getMeterId());
                if (!usageList.isEmpty()) {
                    double currentUnits = usageList.get(0).getUnitsConsumed();
                    double monthlyLimit = 20.0; // kL threshold

                    if (currentUnits > monthlyLimit) {
                        double excess = Math.round((currentUnits - monthlyLimit) * 100.0) / 100.0;
                        String title = "⚠️ Over Usage Warning!";

                        // Duplicate Alert Protection: Check if warning was sent in the last 25 days
                        List<Notification> existingAlerts = notificationRepository.findByResidentResidentIdOrderByCreatedAtDesc(resident.getResidentId());
                        boolean duplicateExists = existingAlerts.stream().anyMatch(n -> 
                            title.equalsIgnoreCase(n.getTitle()) && 
                            n.getCreatedAt() != null && 
                            n.getCreatedAt().isAfter(LocalDateTime.now().minusDays(25))
                        );

                        if (duplicateExists) {
                            log.info("Overusage alert already sent to resident {} for this cycle. Skipping duplicate.", resident.getName());
                            continue;
                        }

                        String message = String.format(
                                "Your water consumption is %.2f kL, which is above the recommended %.2f kL monthly threshold (Excess: %.2f kL).\n\n" +
                                "Try:\n" +
                                "• Reducing unnecessary water usage\n" +
                                "• Monitoring daily consumption",
                                currentUnits, monthlyLimit, excess
                        );

                        Notification alert = Notification.builder()
                                .resident(resident)
                                .title(title)
                                .message(message)
                                .isRead(false)
                                .build();
                        notificationRepository.save(alert);

                        emailService.sendNotificationEmail(resident.getEmail(), resident.getName(), title, message);
                    }
                }
            }
        }
    }
}
