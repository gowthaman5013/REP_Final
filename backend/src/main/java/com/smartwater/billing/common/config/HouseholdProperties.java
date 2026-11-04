package com.smartwater.billing.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "household")
public class HouseholdProperties {

    private Map<String, Integer> defaults = new HashMap<>();
    private Limits limits = new Limits();

    public HouseholdProperties() {
        defaults.put("STUDIO", 1);
        defaults.put("1BHK", 2);
        defaults.put("2BHK", 3);
        defaults.put("3BHK", 4);
        defaults.put("PENTHOUSE", 5);
        defaults.put("default", 3);
    }

    public static class Limits {
        private int min = 1;
        private int max = 15;
        private int sanityWarningThreshold = 6;
        private int maxAwayDays = 180;

        public int getMin() { return min; }
        public void setMin(int min) { this.min = min; }

        public int getMax() { return max; }
        public void setMax(int max) { this.max = max; }

        public int getSanityWarningThreshold() { return sanityWarningThreshold; }
        public void setSanityWarningThreshold(int sanityWarningThreshold) { this.sanityWarningThreshold = sanityWarningThreshold; }

        public int getMaxAwayDays() { return maxAwayDays; }
        public void setMaxAwayDays(int maxAwayDays) { this.maxAwayDays = maxAwayDays; }
    }

    public int getDefaultSizeForUnitType(String unitType) {
        if (unitType == null || unitType.isBlank()) {
            return defaults.getOrDefault("default", 3);
        }
        String normalized = unitType.trim().toUpperCase();
        return defaults.getOrDefault(normalized, defaults.getOrDefault("default", 3));
    }

    public Map<String, Integer> getDefaults() { return defaults; }
    public void setDefaults(Map<String, Integer> defaults) { this.defaults = defaults; }

    public Limits getLimits() { return limits; }
    public void setLimits(Limits limits) { this.limits = limits; }
}

