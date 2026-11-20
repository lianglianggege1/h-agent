package com.h.capabilities.document.application;

import com.h.capabilities.seam.AssetRef;
import com.h.capabilities.seam.port.ConversationAssetPort;
import com.h.capabilities.document.infrastructure.config.DocumentProperties;

import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** 附件访问应用服务：归属/引用校验与内容读取委托 ConversationAssetPort，大小上限由文档配置约束。 */
@Component
public class DocumentAssets {
    private final ConversationAssetPort assets;
    private final DocumentProperties properties;

    public DocumentAssets(ConversationAssetPort assets, DocumentProperties properties) {
        this.assets = assets;
        this.properties = properties;
    }

    public void session(Long user, String session) {
        assets.requireScope(user, session);
    }

    public AssetRef require(Long user, String session, String id) {
        return assets.requireAsset(user, session, id);
    }

    public byte[] read(Long user, String session, String id) {
        return assets.readAsset(user, session, id, properties.getMaxBytes());
    }

    public List<Map<String, Object>> inputs(Long user, String session) {
        return assets.inputs(user, session);
    }

    public static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}