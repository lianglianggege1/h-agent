package com.h.backend.skill.domain.source;

import com.h.backend.skill.domain.SkillFileSet;

/** Source content only. Caller holds the PostgreSQL Skill row lock across mutations.
 * Versions are infrastructure identities, never the user's Release number or Active pointer.
 */
public interface SkillSourceRepository {
    /** Called only when PG has no Nacos Proposal: any existing remote draft is unregistered. */
    SourceSnapshot openDraft(long skillId, SkillFileSet seed);
    /** Idempotent historical import; reuses an online version with exactly these bytes. */
    SourceSnapshot importPublished(long skillId, String targetVersion, SkillFileSet files);
    SourceSnapshot read(long skillId, String version);
    SourceSnapshot save(long skillId, String version, String expectedRevision, SkillFileSet files);
    void publish(long skillId, String version, String expectedRevision);
    void discard(long skillId, String version, String expectedRevision);

    record SourceSnapshot(String version, String revision, String parentRevision, SkillFileSet files) { }
}
