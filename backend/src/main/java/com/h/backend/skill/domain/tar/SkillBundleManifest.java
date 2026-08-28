package com.h.backend.skill.domain.tar;

import java.util.List;

public record SkillBundleManifest(int schemaVersion, List<Entry> files) {

    public static final String MANIFEST_PATH = "manifest.json";
    public static final int SCHEMA_VERSION = 1;

    public record Entry(String path, long size, String sha256) {
    }
}
