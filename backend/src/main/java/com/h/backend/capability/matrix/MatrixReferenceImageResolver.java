package com.h.backend.capability.matrix;

import com.h.backend.chat.application.reference.ReferenceImageResolver;
import com.h.backend.chat.application.reference.ResolvedReferenceImage;
import com.h.matrixteam.MatrixClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;

/** 团队参考图解析：mxc:// 下载 + 魔数嗅探，尺寸尽力读取（ImageIO 不支持的格式为 null）。 */
public class MatrixReferenceImageResolver implements ReferenceImageResolver {

    private final List<MatrixClient> clients;

    public MatrixReferenceImageResolver(List<MatrixClient> clients) {
        this.clients = clients;
    }

    @Override
    public ResolvedReferenceImage resolve(Long userId, String resourceId) {
        if (resourceId == null || !resourceId.startsWith("mxc://")) {
            throw new IllegalArgumentException("团队参考图必须是房间媒体的 mxc:// 地址");
        }
        byte[] bytes = MatrixClients.forActor(clients, TeamActorContext.get()).download(resourceId);
        String mime = MatrixMediaTypes.sniff(bytes);
        if (mime == null || !mime.startsWith("image/")) {
            throw new IllegalArgumentException("参考素材必须是图片：" + resourceId);
        }
        Integer width = null, height = null;
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image != null) {
                width = image.getWidth();
                height = image.getHeight();
            }
        } catch (Exception ignored) {
            // 尺寸缺失由下游校验器给出明确错误
        }
        return new ResolvedReferenceImage(resourceId, mime, bytes, bytes.length, width, height);
    }
}