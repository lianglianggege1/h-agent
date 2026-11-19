-- Add source integrity checks separately from the already applied source migration.
ALTER TABLE skill_proposals ADD CONSTRAINT ck_skill_proposals_nacos_source CHECK (
    source_provider IN ('gitee', 'nacos') AND
    (source_provider <> 'nacos' OR (source_version IS NOT NULL AND head_commit_sha ~ '^[a-f0-9]{64}$'))
);
ALTER TABLE skill_releases ADD CONSTRAINT ck_skill_releases_nacos_source CHECK (
    source_provider IN ('gitee', 'nacos') AND
    (source_provider <> 'nacos' OR (source_version IS NOT NULL AND source_revision IS NOT NULL
     AND source_revision ~ '^[a-f0-9]{64}$' AND artifact_digest = 'sha256:' || source_revision))
);
