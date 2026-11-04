package com.smartwater.billing.common.scheduler;

import com.smartwater.billing.common.service.AlertEngineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AlertScheduler {

    private static final Logger log = LoggerFactory.getLogger(AlertScheduler.class);
    private final AlertEngineService alertEngineService;

    public AlertScheduler(AlertEngineService alertEngineService) {
        this.alertEngineService = alertEngineService;
    }

    @Scheduled(cron = "0 0 8 * * *") // Runs daily at 8 AM
    public void runDailyAlertChecks() {
        log.info("AlertScheduler triggered: Running daily over-usage audit at 8 AM...");
        alertEngineService.checkOverUsageAlerts();
    }
}
