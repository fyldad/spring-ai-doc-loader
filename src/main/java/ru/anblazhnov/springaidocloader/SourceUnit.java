package ru.anblazhnov.springaidocloader;

import java.util.Collections;
import java.util.Map;
import java.util.List;

record SourceUnit(String source, String chunkKind, Map<String, Object> metadata) {

    SourceUnit(String text, Map<String, Object> metadata) {
        this(text, "method", metadata);
    }

    SourceUnit {
        if (chunkKind == null || chunkKind.isBlank()) throw new IllegalArgumentException("Chunk kind is required");
        for (String field : new String[]{"repository_id", "module_id", "relative_path", "language", "file_kind", "source_set", "file_hash"}) {
            if (!(metadata.get(field) instanceof String value) || value.isBlank()) {
                throw new IllegalArgumentException("Missing source identity: " + field);
            }
        }
        // Java readers must supply an overload-specific identity, rather than a method name alone.
        if ("java".equals(metadata.get("language")) && metadata.containsKey("symbol_id")
                && !metadata.containsKey("signature")) {
            throw new IllegalArgumentException("Java symbol identity requires a signature");
        }
    }

    String fileId() {
        return SourceIdentity.uuid("file", value("repository_id"), value("module_id"), value("relative_path"));
    }

    String parentId() {
        return metadata.containsKey("symbol_id")
                ? SourceIdentity.uuid("symbol", fileId(), value("symbol_id")) : fileId();
    }

    String value(String key) { return String.valueOf(metadata.get(key)); }

}
