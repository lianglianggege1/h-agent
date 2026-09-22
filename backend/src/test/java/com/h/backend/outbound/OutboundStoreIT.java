package com.h.backend.outbound;

import com.h.backend.outbound.application.OutboundModule;
import com.h.backend.outbound.domain.Call;
import com.h.backend.outbound.domain.Contact;
import com.h.backend.outbound.domain.Task;
import com.h.backend.outbound.infrastructure.OutboundStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "OUTBOUND_TEST_DB_URL", matches = ".+")
class OutboundStoreIT {

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("OUTBOUND_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault(
                "OUTBOUND_TEST_DB_USERNAME", "postgres"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault(
                "OUTBOUND_TEST_DB_PASSWORD", "postgres"));
    }

    @Autowired OutboundStore store;

    private static final Long USER_A = 900001L;

    // ── 重复导入不能解除禁呼 ──

    @Test
    void importContacts_duplicateDoesNotRemoveDnc() {
        store.importContacts(USER_A, List.of(contact("1000", "test")));
        Long contactId = store.listContacts(USER_A, 1, 10).getFirst().getId();
        store.markDnc(USER_A, contactId);

        store.importContacts(USER_A, List.of(contact("1000", "test2")));

        List<Contact> contacts = store.listContacts(USER_A, 1, 10);
        assertEquals(1, contacts.size());
        assertTrue(contacts.getFirst().isDnc(), "DNC should not be removed by re-import");
    }

    // ── (user_id, phone) 唯一约束 ──

    @Test
    void importContacts_duplicatePhoneSkipped() {
        store.importContacts(USER_A, List.of(contact("2000", "a")));
        int inserted = store.importContacts(USER_A, List.of(contact("2000", "b")));
        assertEquals(0, inserted, "duplicate should be skipped");
    }

    // ── 任务创建：同事务插入全部 QUEUED 条目 ──

    @Test
    void createTask_insertsAllQueuedCalls() {
        store.importContacts(USER_A, List.of(
                contact("3000", "a"), contact("3001", "b"), contact("3002", "c")));
        List<Contact> contacts = store.listContacts(USER_A, 1, 10);

        OutboundModule module = new OutboundModule(store,
                new com.h.backend.outbound.infrastructure.OutboundProperties());

        var result = module.createTask(USER_A, new OutboundModule.CreateTaskInput(
                "test-task", "harness", "goal",
                contacts.stream().map(Contact::getId).toList()));

        Long taskId = (Long) result.get("id");
        List<Call> calls = store.callsForTask(taskId);
        assertEquals(3, calls.size(), "all 3 calls should be inserted");
        assertTrue(calls.stream().allMatch(c -> "QUEUED".equals(c.getStage())),
                "all calls should be QUEUED");
    }

    // ── DNC 联系人被排除 ──

    @Test
    void createTask_dncContactsExcluded() {
        store.importContacts(USER_A, List.of(contact("4000", "a"), contact("4001", "b")));
        List<Contact> contacts = store.listContacts(USER_A, 1, 10);
        store.markDnc(USER_A, contacts.stream()
                .filter(c -> "4000".equals(c.getPhone()))
                .findFirst().orElseThrow().getId());

        OutboundModule module = new OutboundModule(store,
                new com.h.backend.outbound.infrastructure.OutboundProperties());

        var result = module.createTask(USER_A, new OutboundModule.CreateTaskInput(
                "test-task", "harness", "goal",
                contacts.stream().map(Contact::getId).toList()));

        Long taskId = (Long) result.get("id");
        List<Call> calls = store.callsForTask(taskId);
        assertEquals(1, calls.size(), "DNC contact should be excluded");
        assertEquals("4001", calls.getFirst().getPhoneSnapshot());
    }

    // ── 单 RUNNING 任务约束 ──

    @Test
    void singleRunningTaskConstraint() {
        Task t1 = task("task-1", "req-r1");
        Task t2 = task("task-2", "req-r2");
        store.insertTask(t1);
        store.insertTask(t2);

        store.lockedTask(t1.getId(), task1 -> {
            task1.setStatus("RUNNING");
            store.saveTask(task1);
            return null;
        });

        assertThrows(Exception.class, () -> {
            store.lockedTask(t2.getId(), task2 -> {
                task2.setStatus("RUNNING");
                store.saveTask(task2);
                return null;
            });
        }, "second RUNNING task should violate unique index");
    }

    // ── (user_id, request_id) 唯一约束 ──

    @Test
    void duplicateRequestIdRejected() {
        Task t = task("task-1", "req-dup");
        store.insertTask(t);

        Task dup = task("task-2", "req-dup");
        assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> store.insertTask(dup));
    }

    // ── (task_id, phone) 唯一约束 ──

    @Test
    void duplicatePhoneInCallsRejected() {
        Long contactId = ensureContact("5000");
        Long taskId = createTask("req-p5000");

        Call c1 = call(taskId, contactId, "5000", "QUEUED");
        store.insertCalls(List.of(c1));

        Call c2 = call(taskId, contactId, "5000", "QUEUED");
        assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> store.insertCalls(List.of(c2)));
    }

    // ── 单活跃通话约束 ──

    @Test
    void singleActiveCallConstraint() {
        Long contactId1 = ensureContact("6000");
        Long contactId2 = ensureContact("6001");
        Long taskId1 = createTask("req-a6000");
        Long taskId2 = createTask("req-a6001");

        Call c1 = call(taskId1, contactId1, "6000", "ACTIVE");
        store.insertCalls(List.of(c1));

        Call c2 = call(taskId2, contactId2, "6001", "PREPARING");
        assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> store.insertCalls(List.of(c2)),
                "second active call should violate unique index");
    }

    // ── 进度聚合 ──

    @Test
    void stageCountsAggregatesCorrectly() {
        Long taskId = createTask("req-scount");
        Long c1 = ensureContact("7000");
        Long c2 = ensureContact("7001");
        Long c3 = ensureContact("7002");
        Long c4 = ensureContact("7003");

        store.insertCalls(List.of(
                call(taskId, c1, "7000", "QUEUED"),
                call(taskId, c2, "7001", "QUEUED"),
                call(taskId, c3, "7002", "ACTIVE"),
                call(taskId, c4, "7003", "FINISHED")));

        var counts = store.stageCounts(taskId);
        assertEquals(2, counts.get("QUEUED"));
        assertEquals(1, counts.get("ACTIVE"));
        assertEquals(1, counts.get("FINISHED"));
        assertEquals(4, store.totalCalls(taskId));
    }

    // ── helpers ──

    private Long ensureContact(String phone) {
        store.importContacts(USER_A, List.of(contact(phone, "test-" + phone)));
        return store.listContacts(USER_A, 1, 100).stream()
                .filter(c -> phone.equals(c.getPhone()))
                .findFirst().orElseThrow().getId();
    }

    private Long createTask(String requestId) {
        Task t = task("task-" + requestId, requestId);
        return store.insertTask(t);
    }

    private Contact contact(String phone, String name) {
        Contact c = new Contact();
        c.setPhone(phone);
        c.setName(name);
        c.setConsentBasis("consent");
        c.setDnc(false);
        return c;
    }

    private Task task(String name, String requestId) {
        Task t = new Task();
        t.setUserId(USER_A);
        t.setRequestId(requestId);
        t.setName(name);
        t.setAgentBindingSnapshot("{\"agentId\":\"agent1\"}");
        t.setCommunicationGoal("goal");
        t.setContentHash(UUID.randomUUID().toString());
        t.setStatus("READY");
        long now = System.currentTimeMillis();
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        return t;
    }

    private Call call(Long taskId, Long contactId, String phone, String stage) {
        Call c = new Call();
        c.setTaskId(taskId);
        c.setContactId(contactId);
        c.setUserId(USER_A);
        c.setPhoneSnapshot(phone);
        c.setNameSnapshot("test");
        c.setStage(stage);
        return c;
    }
}
