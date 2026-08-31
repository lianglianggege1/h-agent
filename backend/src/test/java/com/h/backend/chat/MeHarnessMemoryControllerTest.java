package com.h.backend.chat;

import com.h.backend.chat.application.HarnessMemoryDocumentManager;
import com.h.backend.chat.domain.memory.HarnessMemoryDocument;
import com.h.backend.chat.domain.memory.HarnessMemoryDocumentException;
import com.h.backend.chat.domain.memory.HarnessMemoryDocumentException.Kind;
import com.h.backend.chat.interfaces.web.HarnessMemoryDocumentExceptionAdvisor;
import com.h.backend.chat.interfaces.web.MeHarnessMemoryController;
import com.h.backend.common.exception.GlobalExceptionHandler;
import com.h.backend.shared.infrastructure.security.AuthUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MeHarnessMemoryControllerTest {

    private static final long USER_ID = 7L;

    private HarnessMemoryDocumentManager manager;
    private MockMvc mockMvc;
    private AuthUserPrincipal principal;

    @BeforeEach
    void setUp() {
        manager = mock(HarnessMemoryDocumentManager.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new MeHarnessMemoryController(manager))
                .setControllerAdvice(new HarnessMemoryDocumentExceptionAdvisor(), new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        principal = new AuthUserPrincipal(USER_ID, "user@example.com", "USER");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getReturnsDocumentForAuthenticatedUser() throws Exception {
        when(manager.view(USER_ID)).thenReturn(new HarnessMemoryDocument(
                "# 用户长期记忆", 7L, true, Instant.parse("2026-08-31T06:30:00Z")));

        mockMvc.perform(get("/api/me/memory"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("OK"))
                .andExpect(jsonPath("$.data.content").value("# 用户长期记忆"))
                .andExpect(jsonPath("$.data.revision").value(7))
                .andExpect(jsonPath("$.data.exists").value(true))
                .andExpect(jsonPath("$.data.updatedAt").value("2026-08-31T06:30:00Z"));

        verify(manager).view(USER_ID);
    }

    @Test
    void getReturnsVirtualDocumentWhenFileDoesNotExist() throws Exception {
        when(manager.view(USER_ID)).thenReturn(new HarnessMemoryDocument(
                "# 用户长期记忆\n\n## 工作偏好", 0L, false, null));

        mockMvc.perform(get("/api/me/memory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exists").value(false))
                .andExpect(jsonPath("$.data.revision").value(0))
                .andExpect(jsonPath("$.data.updatedAt").value(org.hamcrest.Matchers.nullValue()));

        verify(manager).view(USER_ID);
    }

    @Test
    void putSavesDocumentAndReturnsServerBaseline() throws Exception {
        when(manager.save(USER_ID, "# 新内容", 7L)).thenReturn(new HarnessMemoryDocument(
                "# 新内容", 8L, true, Instant.parse("2026-08-31T07:00:00Z")));

        mockMvc.perform(put("/api/me/memory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"# 新内容\",\"expectedRevision\":7}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.content").value("# 新内容"))
                .andExpect(jsonPath("$.data.revision").value(8))
                .andExpect(jsonPath("$.data.exists").value(true))
                .andExpect(jsonPath("$.data.updatedAt").value("2026-08-31T07:00:00Z"));

        verify(manager).save(USER_ID, "# 新内容", 7L);
    }

    @Test
    void putRejectsMissingContent() throws Exception {
        mockMvc.perform(put("/api/me/memory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void putRejectsMissingExpectedRevision() throws Exception {
        mockMvc.perform(put("/api/me/memory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"内容\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void putRejectsNegativeExpectedRevision() throws Exception {
        mockMvc.perform(put("/api/me/memory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"内容\",\"expectedRevision\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void putMapsRevisionConflictTo409WithSafeMessage() throws Exception {
        when(manager.save(anyLong(), anyString(), anyLong()))
                .thenThrow(new HarnessMemoryDocumentException(Kind.REVISION_CONFLICT));

        mockMvc.perform(put("/api/me/memory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"内容\",\"expectedRevision\":0}"))
                .andExpect(status().isConflict())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(40920))
                .andExpect(jsonPath("$.message").value("记忆内容已被其他会话更新，请重新加载最新内容后再保存"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void putMapsTooLargeContentTo413() throws Exception {
        when(manager.save(anyLong(), anyString(), anyLong()))
                .thenThrow(new HarnessMemoryDocumentException(Kind.CONTENT_TOO_LARGE));

        mockMvc.perform(put("/api/me/memory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"内容\",\"expectedRevision\":0}"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value(41301));
    }

    @Test
    void putMapsCorruptStorageTo500WithoutLeakingDetails() throws Exception {
        when(manager.save(anyLong(), anyString(), anyLong()))
                .thenThrow(new HarnessMemoryDocumentException(Kind.CONTENT_CORRUPT));

        mockMvc.perform(put("/api/me/memory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"内容\",\"expectedRevision\":0}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(50020))
                .andExpect(jsonPath("$.message").value("记忆数据异常，请稍后重试或联系管理员"));
    }

    @Test
    void putMapsStoreUnavailableTo503() throws Exception {
        when(manager.save(anyLong(), anyString(), anyLong()))
                .thenThrow(new HarnessMemoryDocumentException(Kind.STORE_UNAVAILABLE));

        mockMvc.perform(put("/api/me/memory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"内容\",\"expectedRevision\":0}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(50320))
                .andExpect(jsonPath("$.message").value("记忆存储暂时不可用，请稍后重试"));
    }

    @Test
    void rejectsDeleteOnSingleDocumentRoute() throws Exception {
        mockMvc.perform(delete("/api/me/memory"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void rejectsUnknownSubRoutes() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/me/memory/versions"))
                .andExpect(status().isNotFound());
    }
}
