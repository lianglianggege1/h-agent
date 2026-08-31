package com.h.backend;

import com.h.backend.chat.domain.agent.AgentRegistry;
import com.h.backend.chat.domain.agent.AgentRuntimeType;
import com.h.backend.chat.domain.agent.ChatAgentIds;
import com.h.backend.chat.infrastructure.config.ChatModelEnvironment;
import com.h.backend.shared.infrastructure.security.JwtTokenProvider;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@SpringBootTest
class BackendApplicationTests {

    @Autowired(required = false)
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private AgentRegistry agentRegistry;

    @Autowired
    @Qualifier("harnessModel")
    private Model harnessModel;

    @Autowired
    @Qualifier("harnessDistributedStore")
    private DistributedStore harnessDistributedStore;

    @Autowired
    @Qualifier("harnessWorkspaceStore")
    private BaseStore harnessWorkspaceStore;

    @Autowired
    private HarnessAgent harnessAgent;

    @Autowired
    @Qualifier("harnessCompactionConfig")
    private CompactionConfig harnessCompactionConfig;

    @Autowired
    @Qualifier("harnessMemoryConfig")
    private MemoryConfig harnessMemoryConfig;

    @Autowired
    @Qualifier("harnessToolResultEvictionConfig")
    private ToolResultEvictionConfig harnessToolResultEvictionConfig;

    @Test
    void shouldLoadJwtBean() {
        assertNotNull(jwtTokenProvider);
    }

    @Test
    void shouldRegisterDistributedHarnessAgent() {
        assertNotNull(agentRegistry.requireEnabled(ChatAgentIds.HARNESS));
        org.junit.jupiter.api.Assertions.assertEquals(
                AgentRuntimeType.HARNESS_STREAMING,
                agentRegistry.requireEnabled(ChatAgentIds.HARNESS).runtimeType()
        );
    }

    @Test
    void shouldGiveHarnessAndGatewayOneDistributedStore() {
        assertSame(harnessDistributedStore, harnessAgent.getDistributedStore());
    }

    @Test
    void shouldShareWorkspaceStoreBetweenHarnessAndMemoryManagement() {
        assertSame(harnessWorkspaceStore, harnessDistributedStore.baseStore());
    }

    @Test
    void shouldBoundTheSessionWorkingContextExplicitly() {
        org.junit.jupiter.api.Assertions.assertAll(
                () -> org.junit.jupiter.api.Assertions.assertEquals(50, harnessCompactionConfig.getTriggerMessages()),
                () -> org.junit.jupiter.api.Assertions.assertEquals(0, harnessCompactionConfig.getTriggerTokens()),
                () -> org.junit.jupiter.api.Assertions.assertEquals(20_000, harnessCompactionConfig.getReserved()),
                () -> org.junit.jupiter.api.Assertions.assertEquals(-1, harnessCompactionConfig.getKeepTokens()),
                () -> org.junit.jupiter.api.Assertions.assertEquals(2_000, harnessCompactionConfig.getKeepTokensMin()),
                () -> org.junit.jupiter.api.Assertions.assertEquals(8_000, harnessCompactionConfig.getKeepTokensMax()),
                () -> org.junit.jupiter.api.Assertions.assertEquals(0.25, harnessCompactionConfig.getKeepTokensRatio()),
                () -> org.junit.jupiter.api.Assertions.assertTrue(harnessCompactionConfig.isFlushBeforeCompact()),
                () -> org.junit.jupiter.api.Assertions.assertTrue(harnessCompactionConfig.isOffloadBeforeCompact()),
                () -> assertNotNull(harnessCompactionConfig.getTruncateArgsConfig())
        );
    }

    @Test
    void shouldExtractAndConsolidateUserMemoryExplicitly() {
        org.junit.jupiter.api.Assertions.assertAll(
                () -> org.junit.jupiter.api.Assertions.assertEquals(
                        MemoryConfig.FlushMode.THROTTLED,
                        harnessMemoryConfig.flushTrigger().mode()
                ),
                () -> org.junit.jupiter.api.Assertions.assertEquals(
                        java.time.Duration.ofMinutes(30),
                        harnessMemoryConfig.flushTrigger().minGap()
                ),
                () -> org.junit.jupiter.api.Assertions.assertEquals(
                        java.time.Duration.ofMinutes(30),
                        harnessMemoryConfig.consolidationMinGap()
                ),
                () -> org.junit.jupiter.api.Assertions.assertEquals(4_000, harnessMemoryConfig.consolidationMaxTokens()),
                () -> org.junit.jupiter.api.Assertions.assertEquals(90, harnessMemoryConfig.dailyFileRetentionDays()),
                () -> org.junit.jupiter.api.Assertions.assertEquals(180, harnessMemoryConfig.sessionRetentionDays())
        );
    }

    @Test
    void shouldPersistOversizedToolResultsInSharedArtifacts() {
        org.junit.jupiter.api.Assertions.assertAll(
                () -> org.junit.jupiter.api.Assertions.assertEquals(
                        80_000,
                        harnessToolResultEvictionConfig.getMaxResultChars()
                ),
                () -> org.junit.jupiter.api.Assertions.assertEquals(
                        2_000,
                        harnessToolResultEvictionConfig.getPreviewChars()
                ),
                () -> org.junit.jupiter.api.Assertions.assertEquals(
                        "artifacts/large_tool_results",
                        harnessToolResultEvictionConfig.getEvictionPath()
                )
        );
    }

    @Test
    void shouldUseSameEnvModelForHarnessRuntime() {
        ChatModelEnvironment.load(Path.of("")).ifPresentOrElse(
                environment -> org.junit.jupiter.api.Assertions.assertEquals(
                        environment.modelName(),
                        harnessModel.getModelName()
                ),
                () -> org.junit.jupiter.api.Assertions.assertEquals(
                        "disabled-harness-model",
                        harnessModel.getModelName()
                )
        );
    }

}
