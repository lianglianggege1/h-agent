package com.h.backend.outbound;

import com.h.backend.common.exception.BusinessException;
import com.h.backend.chat.domain.agent.ChatAgentIds;
import com.h.backend.outbound.application.OutboundModule;
import com.h.backend.outbound.domain.Contact;
import com.h.backend.outbound.infrastructure.OutboundProperties;
import com.h.backend.outbound.infrastructure.OutboundStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboundModuleTest {

    private OutboundStore store;
    private OutboundProperties properties;
    private OutboundModule module;

    @BeforeEach
    void setup() {
        store = mock(OutboundStore.class);
        properties = new OutboundProperties();
        module = new OutboundModule(store, properties);
    }

    // ── 导入验证：全量校验后才写入 ──

    @Test
    void importContacts_emptyRows_throws() {
        var ex = assertThrows(BusinessException.class,
                () -> module.importContacts(1L, List.of()));
        assertEquals(40001, ex.getCode());
    }

    @Test
    void importContacts_exceeds100_throws() {
        var rows = java.util.stream.Stream.generate(() ->
                        new OutboundModule.ContactInput("1000", "test", "basis"))
                .limit(101).toList();
        var ex = assertThrows(BusinessException.class,
                () -> module.importContacts(1L, rows));
        assertEquals(40002, ex.getCode());
    }

    @Test
    void importContacts_blankPhone_throws() {
        var ex = assertThrows(BusinessException.class,
                () -> module.importContacts(1L, List.of(
                        new OutboundModule.ContactInput("", "test", "basis"))));
        assertEquals(40010, ex.getCode());
    }

    @Test
    void importContacts_blankConsentBasis_throws() {
        var ex = assertThrows(BusinessException.class,
                () -> module.importContacts(1L, List.of(
                        new OutboundModule.ContactInput("1000", "test", ""))));
        assertEquals(40011, ex.getCode());
    }

    @Test
    void importContacts_duplicatePhoneInBatch_throws() {
        var ex = assertThrows(BusinessException.class,
                () -> module.importContacts(1L, List.of(
                        new OutboundModule.ContactInput("1000", "a", "basis"),
                        new OutboundModule.ContactInput("1000", "b", "basis"))));
        assertEquals(40012, ex.getCode());
    }

    @Test
    void importContacts_validBatch_callsStore() {
        when(store.importContacts(anyLong(), anyList())).thenReturn(2);
        when(store.transaction(any())).thenAnswer(inv -> {
            var supplier = inv.getArgument(0, java.util.function.Supplier.class);
            return supplier.get();
        });
        int result = module.importContacts(1L, List.of(
                new OutboundModule.ContactInput("1000", "a", "basis1"),
                new OutboundModule.ContactInput("1001", "b", "basis2")));
        assertEquals(2, result);
        verify(store).importContacts(eq(1L), anyList());
    }

    // ── 任务创建：校验 ──

    @Test
    void createTask_blankName_throws() {
        var ex = assertThrows(BusinessException.class,
                () -> module.createTask(1L, new OutboundModule.CreateTaskInput(
                        "", ChatAgentIds.HARNESS, "goal", List.of(1L))));
        assertEquals(40020, ex.getCode());
    }

    @Test
    void createTask_blankGoal_throws() {
        var ex = assertThrows(BusinessException.class,
                () -> module.createTask(1L, new OutboundModule.CreateTaskInput(
                        "task", ChatAgentIds.HARNESS, "", List.of(1L))));
        assertEquals(40021, ex.getCode());
    }

    @Test
    void createTask_noAgent_throws() {
        var ex = assertThrows(BusinessException.class,
                () -> module.createTask(1L, new OutboundModule.CreateTaskInput(
                        "task", "", "goal", List.of(1L))));
        assertEquals(40022, ex.getCode());
    }

    @Test
    void createTask_noContacts_throws() {
        var ex = assertThrows(BusinessException.class,
                () -> module.createTask(1L, new OutboundModule.CreateTaskInput(
                        "task", ChatAgentIds.HARNESS, "goal", List.of())));
        assertEquals(40023, ex.getCode());
    }

    // ── 任务创建：越权联系人整批失败 ──

    @Test
    void createTask_contactOwnershipMismatch_throws403() {
        when(store.findOwnedContacts(eq(1L), anyList())).thenReturn(List.of());
        var ex = assertThrows(BusinessException.class,
                () -> module.createTask(1L, new OutboundModule.CreateTaskInput(
                        "task", ChatAgentIds.HARNESS, "goal", List.of(1L, 2L))));
        assertEquals(40300, ex.getCode());
    }

    @Test
    void createTask_allDnc_throws() {
        Contact c = new Contact();
        c.setId(1L);
        c.setPhone("1000");
        c.setDnc(true);
        when(store.findOwnedContacts(eq(1L), anyList())).thenReturn(List.of(c));
        var ex = assertThrows(BusinessException.class,
                () -> module.createTask(1L, new OutboundModule.CreateTaskInput(
                        "task", ChatAgentIds.HARNESS, "goal", List.of(1L))));
        assertEquals(40020, ex.getCode());
    }

    @Test
    void createTask_sameRequestIdAndContent_returnsExistingTask() {
        Contact contact = new Contact();
        contact.setId(1L);
        contact.setPhone("1000");
        contact.setName("test");
        when(store.findOwnedContacts(1L, List.of(1L))).thenReturn(List.of(contact));
        when(store.transaction(any())).thenAnswer(inv -> inv.getArgument(0, Supplier.class).get());
        when(store.insertTask(any())).thenAnswer(inv -> {
            var task = inv.getArgument(0, com.h.backend.outbound.domain.Task.class);
            task.setId(9L);
            return 9L;
        });
        when(store.callsForTask(9L)).thenReturn(List.of());
        String requestId = UUID.randomUUID().toString();
        var input = new OutboundModule.CreateTaskInput(
                "task", ChatAgentIds.HARNESS, "goal", List.of(1L), requestId);

        module.createTask(1L, input);
        var task = org.mockito.ArgumentCaptor.forClass(com.h.backend.outbound.domain.Task.class);
        verify(store).insertTask(task.capture());
        when(store.findTaskByRequest(1L, requestId)).thenReturn(task.getValue());

        var replay = module.createTask(1L, input);

        assertEquals(9L, replay.get("id"));
        verify(store, times(1)).insertTask(any());
    }
}
