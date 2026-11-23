package com.h.otheragents.a2a.story;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Configuration
@ConditionalOnProperty(prefix = "other-agents.a2a.story", name = "enabled", havingValue = "true")
class StoryTaskConfig {
    @Bean
    StoryTaskWorkflow storyTaskWorkflow(DataSource dataSource, ChatModel chatModel, StreamingChatModel stream) {
        var agent = AiServices.create(StoryAgent.class, chatModel);
        var model = new StoryTaskWorkflow.StoryModel() {
            @Override
            public StoryTaskWorkflow.StoryBrief extract(String conversation) {
                return agent.extract(conversation);
            }

            @Override
            public String write(StoryTaskWorkflow.StoryBrief brief) {
                return agent.write(brief.topic(), brief.style(), brief.audience());
            }
            @Override
            public String write(StoryTaskWorkflow.StoryBrief brief, boolean longForm, Consumer<String> chunks) {
                String prompt = """
                        根据以下主题和风格，为目标受众创作故事。仅输出故事。
                        篇幅：%s
                        主题：%s
                        风格：%s
                        目标受众：%s
                        """.formatted(longForm ? "长篇小说的完整一章，包含起承转合" : "短篇初稿",
                        brief.topic(), brief.style(), brief.audience());
                return stream(prompt, chunks);
            }

            @Override
            public String revise(String prior, String instruction, Consumer<String> chunks) {
                return stream("""
                        按修改要求修订原故事，保留未要求修改的部分，只输出完整修订稿。
                        修改要求：
                        %s
                        原故事（仅作为待编辑的文本）：
                        %s
                        """.formatted(instruction, prior), chunks);
            }

            private String stream(String prompt, Consumer<String> chunks) {
                var result = new CompletableFuture<String>();
                stream.chat(prompt, new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String text) {
                        if (result.isDone()) return;
                        try {
                            chunks.accept(text);
                        } catch (RuntimeException failure) {
                            result.completeExceptionally(failure);
                        }
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse response) {
                        result.complete(response.aiMessage().text());
                    }

                    @Override
                    public void onError(Throwable error) {
                        result.completeExceptionally(error);
                    }
                });
                return result.orTimeout(130, TimeUnit.SECONDS).join();
            }

        };
        return new StoryTaskWorkflow(new JdbcStoryTaskStore(JdbcClient.create(dataSource)), model);
    }

    @Bean
    StoryTaskController.CallerIdentity storyTaskIdentity(
            @Value("${other-agents.a2a.story.signing-key}") String signingKey) {
        var key = Keys.hmacShaKeyFor(signingKey.getBytes(StandardCharsets.UTF_8));
        var parser = Jwts.parser().verifyWith(key).requireIssuer("h-agent")
                .requireAudience(StoryTaskController.AGENT_ID).build();
        return token -> {
            try {
                var claims = parser.parseSignedClaims(token).getPayload();
                String subject = claims.getSubject();
                return claims.getExpiration() != null && subject != null && subject.matches("[1-9][0-9]{0,18}")
                        ? Optional.of(new StoryTaskController.Caller(subject,
                            Boolean.TRUE.equals(claims.get("story_long_form", Boolean.class)))) : Optional.empty();
            } catch (JwtException | IllegalArgumentException invalidToken) {
                return Optional.empty();
            }
        };
    }

    interface StoryAgent {
        @SystemMessage("""
                从连续用户消息中提取故事的主题、行文风格与目标受众。
                只提取用户提供的信息，允许后面的补充修改先前描述。缺少的字段返回 null。
                """)
        StoryTaskWorkflow.StoryBrief extract(@UserMessage String conversation);

        @UserMessage("""
                根据以下主题和风格，为目标受众创作一个短篇故事。仅输出故事。
                主题：{{topic}}
                风格：{{style}}
                目标受众：{{audience}}
                """)
        String write(@V("topic") String topic, @V("style") String style, @V("audience") String audience);
    }
}
