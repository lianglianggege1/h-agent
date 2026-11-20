package com.h.capabilities.seam.port;

import com.h.capabilities.seam.AnchorMessage;
import com.h.capabilities.seam.AssetRef;

import java.util.List;
import java.util.Map;

/**
 * 会话附件与锚点读取。scopeId 在产品聊天是 sessionId，在团队房间是 roomId。
 * 归属/引用校验失败抛 BusinessException（40404 附件不存在等），与原有工具协议一致。
 */
public interface ConversationAssetPort {

    /** 校验 scope 归属与能力接入资格。 */
    void requireScope(long userId, String scopeId);

    /** 校验附件归属且已被当前 scope 引用，返回元数据。 */
    AssetRef requireAsset(long userId, String scopeId, String resourceId);

    /** 读取附件内容，超过 maxBytes 抛 BusinessException。 */
    byte[] readAsset(long userId, String scopeId, String resourceId, long maxBytes);

    /** 当前 scope 可引用的附件清单（文档工具 inputs 视图）。 */
    List<Map<String, Object>> inputs(long userId, String scopeId);

    /** 最近一条该用户消息（续跑锚点）；无则 null。团队 scope 按用户专属 Matrix 身份过滤。 */
    AnchorMessage latestUserMessage(long userId, String scopeId);

    /** 按主键读锚点消息；无则 null。 */
    AnchorMessage messageById(long messageId);
}