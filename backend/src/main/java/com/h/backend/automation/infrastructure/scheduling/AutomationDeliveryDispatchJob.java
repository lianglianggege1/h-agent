package com.h.backend.automation.infrastructure.scheduling;

import com.h.backend.automation.application.AutomationProposalModule;
import com.h.backend.automation.application.DeliveryModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 投递扫描与提案过期清理：与触发方式（本地轮询 / XXL-Job）无关，始终启用。
 */
@Component
@ConditionalOnExpression("'${automation.enabled:true}' == 'true'")
public class AutomationDeliveryDispatchJob {

    private final DeliveryModule deliveryModule;
    private final AutomationProposalModule proposalModule;

    public AutomationDeliveryDispatchJob(DeliveryModule deliveryModule,
                                         AutomationProposalModule proposalModule) {
        this.deliveryModule = deliveryModule;
        this.proposalModule = proposalModule;
    }

    @Scheduled(fixedDelayString = "${automation.delivery.polling-delay:10s}")
    public void dispatch() {
        deliveryModule.dispatchPending();
    }

    @Scheduled(fixedDelayString = "${automation.proposal-sweep-delay:5m}")
    public void sweepExpiredProposals() {
        proposalModule.sweepExpired();
    }
}
