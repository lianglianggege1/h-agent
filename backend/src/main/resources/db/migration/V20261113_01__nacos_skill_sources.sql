-- Keep existing Release IDs, Artifact Descriptors and Git provenance intact.
ALTER TABLE skill_proposals ADD COLUMN source_provider VARCHAR(16) NOT NULL DEFAULT 'gitee';
ALTER TABLE skill_proposals ADD COLUMN source_version VARCHAR(128);
ALTER TABLE skill_proposals ALTER COLUMN branch_name DROP NOT NULL;
ALTER TABLE skill_proposals ALTER COLUMN head_commit_sha TYPE VARCHAR(128);
ALTER TABLE skill_proposals ALTER COLUMN validated_head_sha TYPE VARCHAR(128);
ALTER TABLE skill_releases ADD COLUMN source_provider VARCHAR(16) NOT NULL DEFAULT 'gitee';
ALTER TABLE skill_releases ADD COLUMN source_version VARCHAR(128);
ALTER TABLE skill_releases ADD COLUMN source_revision VARCHAR(128);
ALTER TABLE skill_releases ALTER COLUMN tag_name DROP NOT NULL;
ALTER TABLE skill_releases ALTER COLUMN commit_sha DROP NOT NULL;
ALTER TABLE skill_publication_operations ALTER COLUMN expected_proposal_head TYPE VARCHAR(128);
ALTER TABLE skill_publication_operations ADD COLUMN release_note TEXT;
ALTER TABLE skill_publication_operations ADD COLUMN source_coordinates_json JSONB;
ALTER TABLE skill_proposal_write_operations ALTER COLUMN expected_head_commit_sha TYPE VARCHAR(128);
ALTER TABLE skill_proposal_write_operations ALTER COLUMN target_head_commit_sha TYPE VARCHAR(128);
COMMENT ON COLUMN skill_proposals.head_commit_sha IS 'Compatibility column: opaque source revision, SHA-256 for Nacos; not a Git commit';
COMMENT ON COLUMN skill_releases.source_version IS 'Exact Nacos version; independent of product version_number and latest';
CREATE INDEX idx_skill_releases_nacos_version ON skill_releases(skill_id, source_version)
    WHERE source_provider = 'nacos';

ALTER TABLE skill_proposals ADD CONSTRAINT ck_skill_proposals_nacos_source CHECK (
    source_provider IN ('gitee', 'nacos') AND
    (source_provider <> 'nacos' OR (source_version IS NOT NULL AND head_commit_sha ~ '^[a-f0-9]{64}$'))
);
ALTER TABLE skill_releases ADD CONSTRAINT ck_skill_releases_nacos_source CHECK (
    source_provider IN ('gitee', 'nacos') AND
    (source_provider <> 'nacos' OR (source_version IS NOT NULL AND source_revision IS NOT NULL
     AND source_revision ~ '^[a-f0-9]{64}$' AND artifact_digest = 'sha256:' || source_revision))
);
