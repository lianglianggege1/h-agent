package com.h.backend.automation.domain;

public enum AutomationDeliveryStatus {
    PENDING,
    DELIVERING,
    DELIVERED,
    RETRYING,
    DEAD_LETTER,
    SKIPPED
}
