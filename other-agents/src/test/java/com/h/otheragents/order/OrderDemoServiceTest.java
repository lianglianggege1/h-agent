package com.h.otheragents.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;

class OrderDemoServiceTest {
    private final MemoryStore store = new MemoryStore();
    private final OrderDemoService service = new OrderDemoService(store);

    static final class MemoryStore implements OrderDemoStore {
        final Map<Long, String> states = new HashMap<>();
        final ObjectMapper json = new ObjectMapper();
        @Override public synchronized <T> T transact(long user, Function<OrderDemoState,T> operation) {
            try {
                OrderDemoState state = states.containsKey(user) ? json.readValue(states.get(user), OrderDemoState.class) : OrderDemoState.seeded();
                T result = operation.apply(state);
                states.put(user, json.writeValueAsString(state)); return result;
            } catch (RuntimeException error) { throw error; }
            catch (Exception error) { throw new IllegalStateException(error); }
        }
    }

    @SuppressWarnings("unchecked") private Map<String,Object> order(Map<String,Object> result) {
        return ((List<Map<String,Object>>) result.get("orders")).getFirst();
    }
    @SuppressWarnings("unchecked") private String decision(Map<String,Object> result) {
        return ((List<Map<String,Object>>) order(result).get("decisions")).getLast().get("id").toString();
    }
    private Map<String,Object> run(long user, String id, String action, String detail) {
        Map<String,Object> current = service.query(user, id);
        return service.execute(user, true, id, action, UUID.randomUUID().toString(), current.get("generation").toString(),
                ((Number)order(current).get("revision")).longValue(), detail);
    }
    private Map<String,Object> run(String id, String action) { return run(1,id,action,""); }
    private Map<String,Object> confirm(String id, Map<String,Object> proposal) { return run(1,id,"CONFIRM",decision(proposal)); }

    @Test void happyPathAndIsolatedAccounts() {
        String id="ORD-DEMO-001";
        assertThat(order(run(id,"PROCESS"))).containsEntry("stage","SHIPPED");
        assertThat(order(run(id,"DELIVERED"))).containsEntry("stage","COMPLETED");
        assertThat(order(service.query(2,id))).containsEntry("stage","NEW");
        assertThat(store.<Integer>transact(1,s->s.availableStock.get("EARPHONE-1"))).isEqualTo(9);
        assertThat(store.<Integer>transact(1,s->s.availableStock.get("CASE-1"))).isEqualTo(9);
        assertThat(order(service.query(1,id))).containsEntry("amountCents",79800L);
        assertThat(order(service.query(1,id)).get("history").toString()).contains("RESERVE", "OUTBOUND", "SHIP");
    }
    @Test void unpaidOrderWaitsThenContinuesOnFact() {
        String id="ORD-DEMO-002";
        assertThat(order(run(id,"PROCESS"))).containsEntry("stage","WAITING_PAYMENT");
        assertThat(order(run(id,"PAYMENT_RECEIVED"))).containsEntry("stage","SHIPPED");
    }
    @Test void stockAndAddressTicketsRequireVerifiedFacts() {
        String id="ORD-DEMO-003";
        assertThat(order(run(id,"PROCESS")).get("tickets").toString()).contains("WAITING");
        run(1,id,"NOTE","已联系仓库"); run(id,"RECHECK");
        assertThat(order(service.query(1,id))).containsEntry("stage","READY");
        assertThat(order(run(id,"RESTOCK"))).containsEntry("stage","SHIPPED");
        assertThat(order(service.query(1,id)).get("tickets").toString()).contains("RESOLVED");
        id="ORD-DEMO-004"; run(id,"PROCESS");
        Map<String,Object> proposal=run(1,id,"PROPOSE_ADDRESS","上海市浦东新区新的演示路 18 号");
        assertThat(order(confirm(id,proposal))).containsEntry("stage","SHIPPED");
    }
    @Test void cancelAndRefundNeedsOperatorAndIsIdempotent() {
        String id="ORD-DEMO-001";
        Map<String,Object> proposal=run(id,"PROPOSE_CANCEL");
        String gen=proposal.get("generation").toString(), decision=decision(proposal);
        long rev=((Number)order(proposal).get("revision")).longValue();
        assertThatThrownBy(()->service.execute(1,false,id,"CONFIRM","forged",gen,rev,decision)).hasMessageContaining("用户确认");
        Map<String,Object> confirmed=service.execute(1,true,id,"CONFIRM","same-command",gen,rev,decision);
        assertThat(order(confirmed)).containsEntry("stage","CANCELLED").containsEntry("payment","REFUNDED");
        assertThat(store.json.valueToTree(service.execute(1,true,id,"CONFIRM","same-command",gen,rev,decision)).toString())
                .isEqualTo(store.json.valueToTree(confirmed).toString());
        assertThatThrownBy(()->service.execute(1,true,id,"PROCESS","same-command",gen,rev,decision)).hasMessageContaining("不同操作");
    }
    @Test void logisticsRecoveryAndLossBothCloseTicketsCorrectly() {
        String id="ORD-DEMO-005"; run(id,"LOGISTICS_DELAY");
        assertThat(order(run(id,"LOGISTICS_RECOVERED")).get("tickets").toString()).contains("RESOLVED");
        run(id,"LOGISTICS_DELAY"); Map<String,Object> lost=run(id,"PACKAGE_LOST");
        assertThat(order(lost)).containsEntry("payment","PAID");
        assertThat(order(confirm(id,lost))).containsEntry("stage","LOST").containsEntry("payment","REFUNDED");
        assertThat(order(service.query(1,id)).get("tickets").toString()).doesNotContain("status=WAITING");
    }
    @Test void acceptedReturnRefundFailureAndRetryKeepRefundIdentity() {
        String id="ORD-DEMO-006"; confirm(id,run(id,"PROPOSE_RETURN")); run(id,"FAIL_NEXT_REFUND");
        assertThat(order(run(id,"RETURN_INSPECTION_PASS"))).containsEntry("payment","REFUND_FAILED");
        String refundId=store.<String>transact(1,s->s.orders.get(id).refundId);
        assertThat(order(run(id,"RETRY_REFUND"))).containsEntry("payment","REFUNDED").containsEntry("aftersales","CLOSED_REFUNDED");
        assertThat(store.<String>transact(1,s->s.orders.get(id).refundId)).isEqualTo(refundId);
        assertThat(store.<Integer>transact(1,s->s.availableStock.get("EARPHONE-6"))).isEqualTo(11);
        assertThatThrownBy(()->run(id,"RETRY_REFUND")).hasMessageContaining("没有失败");
    }
    @Test void failedInspectionCanReturnGoodsOrBeExplicitlyOverridden() {
        String id="ORD-DEMO-006"; confirm(id,run(id,"PROPOSE_RETURN")); run(id,"RETURN_INSPECTION_FAIL");
        assertThat(order(run(id,"RECHECK"))).containsEntry("payment","PAID");
        confirm(id,run(id,"PROPOSE_REJECT_RETURN")); run(id,"RETURN_SHIPPED");
        assertThat(order(run(id,"RETURN_DELIVERED"))).containsEntry("aftersales","CLOSED_REJECTED").containsEntry("payment","PAID");
        confirmOtherUserReturn(id);
    }
    private void confirmOtherUserReturn(String id) {
        Map<String,Object> p=run(2,id,"PROPOSE_RETURN",""); run(2,id,"CONFIRM",decision(p));
        run(2,id,"RETURN_INSPECTION_FAIL",""); p=run(2,id,"PROPOSE_SPECIAL_REFUND","");
        assertThat(order(run(2,id,"CONFIRM",decision(p)))).containsEntry("payment","REFUNDED");
    }
    @Test void staleDecisionsAndCommandsCannotOverrideNewState() {
        String id="ORD-DEMO-002"; Map<String,Object> p=run(id,"PROPOSE_CANCEL"); String old=decision(p);
        run(id,"PAYMENT_RECEIVED");
        assertThatThrownBy(()->run(1,id,"CONFIRM",old)).hasMessageContaining("方案已处理");
        Map<String,Object> now=service.query(1,id);
        assertThatThrownBy(()->service.execute(1,true,id,"PROCESS","stale",now.get("generation").toString(),0,"")).hasMessageContaining("状态已变化");
    }
    @Test void agentCannotInventExternalFactsOrResetAndResetInvalidatesOldCommands() {
        Map<String,Object> now=service.query(1,"ORD-DEMO-001"); String gen=now.get("generation").toString();
        assertThatThrownBy(()->service.execute(1,false,"ORD-DEMO-001","DELIVERED","bad",gen,0,"")).hasMessageContaining("演示入口");
        Map<String,Object> reset=service.execute(1,true,"","RESET","reset",gen,0,"");
        assertThat(reset.get("generation")).isNotEqualTo(gen);
        assertThat(store.json.valueToTree(service.execute(1,true,"","RESET","reset",gen,0,"")).toString())
                .isEqualTo(store.json.valueToTree(reset).toString());
        assertThatThrownBy(()->service.execute(1,true,"ORD-DEMO-001","PROCESS","old",gen,0,"")).hasMessageContaining("已重置");
    }
    @Test void concurrentChatsCannotShipTwice() throws Exception {
        Map<String,Object> now=service.query(1,"ORD-DEMO-001"); String gen=now.get("generation").toString();
        try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> jobs=new ArrayList<>();
            for(int i=0;i<2;i++) jobs.add(executor.submit(()->{
                try {service.execute(1,true,"ORD-DEMO-001","PROCESS",UUID.randomUUID().toString(),gen,0,"");return true;}
                catch(IllegalArgumentException conflict){return false;}
            }));
            assertThat(jobs.get(0).get() ^ jobs.get(1).get()).isTrue();
        }
        assertThat(store.<Long>transact(1,s->s.orders.get("ORD-DEMO-001").history.stream().filter(e->e.action().equals("SHIP")).count())).isEqualTo(1L);
    }
    @Test void multiItemAuditDoesNotPartiallyReserveAndCancelledAddressTicketCloses(){
        store.transact(1,state->{state.availableStock.put("CASE-1",0);return null;});
        assertThat(order(run("ORD-DEMO-001","AUDIT"))).containsEntry("stage","READY");
        assertThat(store.<Integer>transact(1,s->s.availableStock.get("EARPHONE-1"))).isEqualTo(10);
        assertThat(order(service.query(1,"ORD-DEMO-001")).get("tickets").toString()).contains("STOCK");
        run("ORD-DEMO-004","PROCESS");confirm("ORD-DEMO-004",run("ORD-DEMO-004","PROPOSE_CANCEL"));
        assertThat(order(service.query(1,"ORD-DEMO-004")).get("tickets").toString()).contains("RESOLVED");
    }
    @Test void pendingBusinessDecisionStopsNormalProgressUntilTheUserDecides(){
        String id="ORD-DEMO-001";var proposal=run(id,"PROPOSE_CANCEL");
        assertThat(order(run(id,"PROCESS"))).containsEntry("stage","NEW");
        assertThat(order(run(1,id,"REJECT",decision(proposal)))).containsEntry("stage","SHIPPED");
    }
}
