package com.h.backend.chat.infrastructure.ai.a2a;

import com.h.agent.observability.AgentObservability;
import com.h.backend.chat.domain.agent.AgentDefinition;
import com.h.backend.chat.domain.agent.AgentRuntimeType;
import com.h.backend.chat.domain.agent.AgentStepEventBridge;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

@Configuration
@ConditionalOnProperty(prefix = "agents.a2a.other-agents", name = {"enabled", "story.enabled"}, havingValue = "true")
public class RemoteStoryAssistantConfig {
    @Bean
    public AgentDefinition remoteStoryAssistantDefinition(NacosA2AClientFactory nacos, DataSource dataSource,
            AgentStepEventBridge events, AgentObservability observability, com.alibaba.nacos.api.ai.AiService aiService,
            com.h.registry.NacosRegistryProperties registry,
            com.h.backend.chat.application.ImageGenerationService images,
            @Qualifier("chatModel") ChatModel chatModel,
            @Value("${agents.a2a.other-agents.story.signing-key}") String signingKey) {
        var jdbc = JdbcClient.create(dataSource);
        var key = Keys.hmacShaKeyFor(signingKey.getBytes(StandardCharsets.UTF_8));
        var assistant = new RemoteStoryAssistant(() -> nacos.endpoint(RemoteStoryAssistant.REMOTE_AGENT), key,
                new JdbcReferences(jdbc), owner -> jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM user_roles
                          WHERE user_id = ? AND role_code IN ('ADMIN', 'A2A_LONG_FORM'))
                        """).param(owner).query(Boolean.class).single(),
                new NacosStoryReview(aiService, registry, observability),
                new RemoteStoryIllustration(nacos, key, images, events, observability), events, observability,
                AiServices.create(StoryIntentRouter.class, chatModel));
        return new AgentDefinition("a2a-remote-story", "A2A 远端故事助手", "跨服务协作",
                List.of("A2A", "故事创作"), "用自然语言发起远端创作、修订、配图或审稿", assistant,
                AgentRuntimeType.AGENTIC_SYNC, true);
    }

    private record JdbcReferences(JdbcClient jdbc) implements RemoteStoryAssistant.References {
        @Override
        public Optional<RemoteStoryAssistant.Reference> get(long owner, String session) {
            return jdbc.sql("""
                    SELECT task_id, context_id FROM chat_a2a_story_references
                    WHERE user_id = ? AND session_id = ?
                    """).params(owner, session)
                    .query((row, index) -> new RemoteStoryAssistant.Reference(
                            row.getString("task_id"), row.getString("context_id")))
                    .optional();
        }

        @Override
        public void set(long owner, String session, RemoteStoryAssistant.Reference reference) {
            jdbc.sql("""
                    INSERT INTO chat_a2a_story_references (session_id, user_id, task_id, context_id)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (session_id) DO UPDATE SET task_id = EXCLUDED.task_id, context_id = EXCLUDED.context_id
                    WHERE chat_a2a_story_references.user_id = EXCLUDED.user_id
                    """).params(session, owner, reference.taskId(), reference.contextId()).update();
        }

        @Override
        public void remove(long owner, String session) {
            jdbc.sql("DELETE FROM chat_a2a_story_references WHERE user_id = ? AND session_id = ?")
                    .params(owner, session).update();
        }

        @Override
        public Optional<RemoteStoryAssistant.Reference> latestEdition(long owner, String session) {
            return jdbc.sql("""
                    SELECT task_id, context_id FROM chat_a2a_story_editions WHERE user_id = ? AND session_id = ?
                    ORDER BY completed_at DESC, task_id LIMIT 1
                    """).params(owner, session)
                    .query((row, index) -> new RemoteStoryAssistant.Reference(row.getString(1), row.getString(2))).optional();
        }

        @Override
        public void recordEdition(long owner, String session, RemoteStoryAssistant.Reference edition) {
            jdbc.sql("""
                    INSERT INTO chat_a2a_story_editions (session_id, user_id, task_id, context_id)
                    VALUES (?, ?, ?, ?) ON CONFLICT (session_id, task_id) DO NOTHING
                    """).params(session, owner, edition.taskId(), edition.contextId()).update();
        }
    }
}
