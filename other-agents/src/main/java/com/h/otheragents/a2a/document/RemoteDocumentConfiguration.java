package com.h.otheragents.a2a.document;

import com.h.capabilities.chat.application.ResourceContentPolicy;
import com.h.capabilities.chat.infrastructure.content.ResourceContentInspector;
import com.h.capabilities.chat.infrastructure.storage.ResourceRange;
import com.h.capabilities.chat.infrastructure.storage.ResourceStorage;
import com.h.capabilities.chat.infrastructure.storage.ResourceStorageConfiguration;
import com.h.capabilities.common.exception.BusinessException;
import com.h.capabilities.document.application.DocumentAssets;
import com.h.capabilities.document.application.DocumentWorkflow;
import com.h.capabilities.document.infrastructure.config.DocumentProperties;
import com.h.capabilities.document.infrastructure.generation.DocumentGenerator;
import com.h.capabilities.document.infrastructure.parsing.LocalDocumentParser;
import com.h.capabilities.document.infrastructure.persistence.DocumentRepository;
import com.h.capabilities.document.infrastructure.persistence.mapper.DocumentTaskMapper;
import com.h.capabilities.document.infrastructure.provider.ModelBestDocumentClient;
import com.h.capabilities.seam.AnchorMessage;
import com.h.capabilities.seam.AssetRef;
import com.h.capabilities.seam.port.ConversationAssetPort;
import com.h.capabilities.seam.port.ResourceUrlPort;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "other-agents.a2a.documents", name = "enabled", havingValue = "true")
@Import(ResourceStorageConfiguration.class)
@MapperScan(basePackageClasses = DocumentTaskMapper.class)
class RemoteDocumentConfiguration {
    @Bean
    RemoteDocumentWork remoteDocumentWork(DataSource source, ResourceStorage storage, Environment environment,
            DocumentTaskMapper mapper, PlatformTransactionManager transactions, ObjectMapper json) {
        var settings = Binder.get(environment).bind("chat.documents", DocumentProperties.class)
                .orElseGet(DocumentProperties::new);
        settings.setOwner("backend");
        var jdbc = JdbcClient.create(source);
        var assets = new ChatAssets(jdbc, storage);
        var parser = new LocalDocumentParser(settings);
        var workflow = new DocumentWorkflow(new DocumentRepository(mapper, json, transactions),
                new DocumentAssets(assets, settings), parser, settings, new ModelBestDocumentClient(settings, json),
                new DocumentGenerator(settings),
                (owner, session, command, type, filename, mime, text, attach) -> {
                    throw new UnsupportedOperationException("Remote document agent only reads and verifies documents");
                },
                assets, new ResourceUrlPort() {
                    @Override public String view(String id) { return "/api/chat/resources/" + id + "/content"; }
                    @Override public String download(String id) { return "/api/chat/resources/" + id + "/download"; }
                }, json, new ResourceContentInspector(), new ResourceContentPolicy());
        return new RemoteDocumentWork(workflow, jdbc, parser);
    }

    private record ChatAssets(JdbcClient jdbc, ResourceStorage storage) implements ConversationAssetPort {
        @Override
        public void requireScope(long owner, String session) {
            boolean allowed = jdbc.sql("""
                    SELECT EXISTS(SELECT 1 FROM agent_sessions WHERE user_id = ? AND session_id = ?
                    AND agent_id IN ('standard-chat', 'harness-agent'))
                    """).params(owner, session).query(Boolean.class).single();
            if (!allowed) throw new BusinessException(40404, "会话不存在");
        }

        @Override
        public AssetRef requireAsset(long owner, String session, String resource) {
            requireScope(owner, session);
            return jdbc.sql("""
                    SELECT r.id, r.file_name, r.mime_type, r.file_size, r.storage_key
                    FROM chat_message_resources r
                    WHERE r.id = ? AND r.user_id = ? AND EXISTS (
                        SELECT 1 FROM chat_message_resources alias
                        JOIN chat_session_messages m ON m.id = alias.message_id
                        WHERE alias.user_id = r.user_id AND alias.storage_key = r.storage_key
                            AND m.user_id = alias.user_id AND m.session_id = ? AND m.superseded_at IS NULL
                    )
                    """).params(resource, owner, session)
                    .query((row, index) -> new AssetRef(row.getString(1), owner, row.getString(2), row.getString(3),
                            row.getObject(4, Long.class), row.getString(5)))
                    .optional().orElseThrow(() -> new BusinessException(40404, "附件不存在"));
        }

        @Override
        public byte[] readAsset(long owner, String session, String asset, long maxBytes) {
            var reference = requireAsset(owner, session, asset);
            try (var content = storage.open(reference.storageKey(), ResourceRange.fullRead())) {
                byte[] bytes = content.inputStream().readNBytes(Math.toIntExact(maxBytes + 1));
                if (bytes.length > maxBytes) throw new BusinessException(40000, "文档超过大小上限");
                return bytes;
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot read document from MinIO", failure);
            }
        }

        @Override public List<Map<String, Object>> inputs(long owner, String session) {
            requireScope(owner, session);
            return List.of();
        }
        @Override public AnchorMessage latestUserMessage(long owner, String scopeId) { return null; }
        @Override public AnchorMessage messageById(long messageId) { return null; }
    }
}
