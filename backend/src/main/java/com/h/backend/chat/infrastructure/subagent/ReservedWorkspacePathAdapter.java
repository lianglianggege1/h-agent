package com.h.backend.chat.infrastructure.subagent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 父 Workspace 的 {@code subagents/} 保留路径适配器（设计 7.3）。
 *
 * <p>Catalog 开启后，用户 Subagent 定义的唯一入口是草稿/发布管理接口；
 * workspace 文件不再是执行入口：</p>
 * <ul>
 *   <li>glob / ls / grep / exists 对该目录返回"无内容"，SDK scanner
 *       （{@code AgentSpecLoader.loadFromFilesystem}）因此拿不到任何 workspace 声明；</li>
 *   <li>read / write / edit / delete / move / upload 对该目录返回明确的保留路径错误；</li>
 *   <li>已存在的 {@code subagents/*.md} 不删除，但不参与 Catalog。</li>
 * </ul>
 */
public final class ReservedWorkspacePathAdapter implements AbstractFilesystem {

    public static final String RESERVED_DIR = "subagents";

    private final AbstractFilesystem delegate;

    public ReservedWorkspacePathAdapter(AbstractFilesystem delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
        if (isReservedDir(path)) {
            return LsResult.success(List.of());
        }
        return delegate.ls(runtimeContext, path);
    }

    @Override
    public ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit) {
        if (isReserved(filePath)) {
            return ReadResult.fail(reservedError(filePath));
        }
        return delegate.read(runtimeContext, filePath, offset, limit);
    }

    @Override
    public WriteResult write(RuntimeContext runtimeContext, String filePath, String content) {
        if (isReserved(filePath)) {
            return WriteResult.fail(reservedError(filePath));
        }
        return delegate.write(runtimeContext, filePath, content);
    }

    @Override
    public EditResult edit(
            RuntimeContext runtimeContext,
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll) {
        if (isReserved(filePath)) {
            return EditResult.fail(reservedError(filePath));
        }
        return delegate.edit(runtimeContext, filePath, oldString, newString, replaceAll);
    }

    @Override
    public GrepResult grep(RuntimeContext runtimeContext, String pattern, String path, String glob) {
        if (isReservedDir(path) || isReserved(path)) {
            return GrepResult.success(List.of());
        }
        return delegate.grep(runtimeContext, pattern, path, glob);
    }

    @Override
    public GlobResult glob(RuntimeContext runtimeContext, String pattern, String path) {
        if (isReservedDir(path)) {
            return GlobResult.success(List.of());
        }
        GlobResult result = delegate.glob(runtimeContext, pattern, path);
        // 递归 pattern（如 **/*.md）可能列出 subagents/ 下的文件，统一从结果中剔除。
        if (result.isSuccess() && result.matches() != null) {
            List<FileInfo> matches = result.matches();
            List<FileInfo> filtered = new ArrayList<>(matches.size());
            boolean changed = false;
            for (FileInfo info : matches) {
                if (info != null && isReserved(info.path())) {
                    changed = true;
                    continue;
                }
                filtered.add(info);
            }
            if (changed) {
                return GlobResult.success(List.copyOf(filtered));
            }
        }
        return result;
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        List<Map.Entry<String, byte[]>> allowed = new ArrayList<>();
        List<FileUploadResponse> responses = new ArrayList<>();
        for (Map.Entry<String, byte[]> file : files) {
            if (isReserved(file.getKey())) {
                responses.add(FileUploadResponse.fail(file.getKey(), reservedError(file.getKey())));
            } else {
                allowed.add(file);
            }
        }
        if (!allowed.isEmpty()) {
            responses.addAll(delegate.uploadFiles(runtimeContext, allowed));
        }
        return responses;
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths) {
        List<String> allowed = new ArrayList<>();
        List<FileDownloadResponse> responses = new ArrayList<>();
        for (String path : paths) {
            if (isReserved(path)) {
                responses.add(FileDownloadResponse.fail(path, reservedError(path)));
            } else {
                allowed.add(path);
            }
        }
        if (!allowed.isEmpty()) {
            responses.addAll(delegate.downloadFiles(runtimeContext, allowed));
        }
        return responses;
    }

    @Override
    public WriteResult delete(RuntimeContext runtimeContext, String path) {
        if (isReservedDir(path) || isReserved(path)) {
            return WriteResult.fail(reservedError(path));
        }
        return delegate.delete(runtimeContext, path);
    }

    @Override
    public WriteResult move(RuntimeContext runtimeContext, String fromPath, String toPath) {
        if (isReserved(fromPath)) {
            return WriteResult.fail(reservedError(fromPath));
        }
        if (isReserved(toPath)) {
            return WriteResult.fail(reservedError(toPath));
        }
        return delegate.move(runtimeContext, fromPath, toPath);
    }

    @Override
    public boolean exists(RuntimeContext runtimeContext, String path) {
        if (isReservedDir(path) || isReserved(path)) {
            return false;
        }
        return delegate.exists(runtimeContext, path);
    }

    private static boolean isReservedDir(String path) {
        return RESERVED_DIR.equals(normalize(path));
    }

    private static boolean isReserved(String path) {
        return normalize(path).startsWith(RESERVED_DIR + "/");
    }

    private static String normalize(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.replace('\\', '/').strip();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static String reservedError(String path) {
        return "subagents/ 是 Subagent 定义的保留路径：'" + path + "' 不能通过文件工具读写。"
                + "Subagent 定义请通过平台的 Subagent 管理接口创建和发布。";
    }
}
