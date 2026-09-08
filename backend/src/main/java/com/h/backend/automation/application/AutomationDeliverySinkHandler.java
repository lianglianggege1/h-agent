package com.h.backend.automation.application;

import com.h.backend.automation.domain.AutomationDelivery;

/** Sink 扩展点：第二个真实外部 Sink（EMAIL/WEBHOOK）出现时再新增实现。 */
public interface AutomationDeliverySinkHandler {

    String sinkType();

    void deliver(AutomationDelivery delivery) throws Exception;
}
