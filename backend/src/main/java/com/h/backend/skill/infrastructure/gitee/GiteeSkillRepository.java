package com.h.backend.skill.infrastructure.gitee;

import java.util.List;

public interface GiteeSkillRepository {

    String masterHead();

    String branchHead(String branch);

    boolean branchExists(String branch);

    void createBranch(String branch, String fromRef);

    List<GiteeFile> listFilesUnder(String directoryPath, String ref);

    byte[] readFile(String path, String ref);

    String readFileSha(String path, String ref);

    String putFile(String path, String branch, String contentBase64, String message);

    void deleteFile(String path, String branch, String sha, String message);

    void createTag(String tagName, String targetSha, String message);

    String verifyTagCommit(String tagName);

    boolean tagExists(String tagName);

    void deleteBranch(String branch);

    /** 创建 head -> base 的 PR，返回编号；用于发布时的干净 publication commit。 */
    long createPullRequest(String head, String base, String title);

    /** squash 合并 PR，返回合并 commit SHA。 */
    String mergePullRequest(long prNumber, String title, String message);

    record GiteeFile(String path, String sha, long size, String type) {

        public boolean isFile() {
            return "file".equals(type);
        }
    }
}
