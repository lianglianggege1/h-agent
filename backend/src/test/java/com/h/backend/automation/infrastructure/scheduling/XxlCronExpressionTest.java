package com.h.backend.automation.infrastructure.scheduling;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class XxlCronExpressionTest {

    @Test
    void convertsSpringCronDayFieldsToXxlDialect() {
        assertEquals("0 0 9 * * ?", XxlCronExpression.fromSpring("0 0 9 * * *"));
        assertEquals("0 0 9 ? * 1-5", XxlCronExpression.fromSpring("0 0 9 * * 1-5"));
        assertEquals("0 0 9 1 * ?", XxlCronExpression.fromSpring("0 0 9 1 * *"));
    }

    @Test
    void rejectsCronThatSpecifiesBothDayOfMonthAndDayOfWeek() {
        assertThrows(IllegalArgumentException.class,
                () -> XxlCronExpression.fromSpring("0 0 9 1 * MON"));
    }
}
