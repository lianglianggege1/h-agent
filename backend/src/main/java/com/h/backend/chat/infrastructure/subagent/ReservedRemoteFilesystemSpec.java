package com.h.backend.chat.infrastructure.subagent;

import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;

import java.nio.file.Path;

/**
 * 在 {@link RemoteFilesystemSpec} 之上叠加 {@code subagents/} 保留路径约束（设计 7.3）。
 *
 * <p>{@code HarnessAgent.Builder#filesystem(RemoteFilesystemSpec)} 只接受 spec，
 * 而 {@link RemoteFilesystemSpec#toFilesystem} 是构建期虚调用，因此通过子类覆盖
 * 把真实 filesystem 包装为 {@link ReservedWorkspacePathAdapter}。WorkspaceManager
 * 与 SubagentsMiddleware 使用的都是同一份包装结果，SDK scanner 无法从
 * {@code subagents/*.md} 加载任何动态声明。</p>
 */
public final class ReservedRemoteFilesystemSpec extends RemoteFilesystemSpec {

    @Override
    public AbstractFilesystem toFilesystem(
            Path workspace, String agentId, NamespaceFactory localNamespaceFactory) {
        return new ReservedWorkspacePathAdapter(
                super.toFilesystem(workspace, agentId, localNamespaceFactory));
    }
}
