package com.h.backend.chat.ai.carrentalassistant.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomerInfoTest {

    @Test
    void isCompleteRequiresLocationBeforeBusinessAgentsRun() {
        CustomerInfo customerInfo = new CustomerInfo(
                "张三",
                null,
                "BK-1001",
                "Toyota",
                "Camry",
                null,
                null
        );

        assertFalse(customerInfo.isComplete());
    }

    @Test
    void isCompleteAllowsCustomerIdInsteadOfBookingReference() {
        CustomerInfo customerInfo = new CustomerInfo(
                "张三",
                "C-1001",
                null,
                "Toyota",
                "Camry",
                null,
                "上海虹桥机场"
        );

        assertTrue(customerInfo.isComplete());
    }

    @Test
    void isCompleteRejectsBlankRequiredFields() {
        CustomerInfo customerInfo = new CustomerInfo(
                " ",
                "C-1001",
                null,
                "Toyota",
                "Camry",
                null,
                "上海虹桥机场"
        );

        assertFalse(customerInfo.isComplete());
    }
}
