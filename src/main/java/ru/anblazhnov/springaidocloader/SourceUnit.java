package ru.anblazhnov.springaidocloader;

import java.util.Map;

/** Extracted unit with a verbatim range in a decoded (possibly sanitized) source snapshot. */
record SourceUnit(String source, int startOffset, int endOffset, String chunkKind,
        Map<String, Object> metadata) {
    SourceUnit {
        if (startOffset < 0 || endOffset < startOffset || endOffset > source.length()) {
            throw new IllegalArgumentException("Invalid source range");
        }
        if (chunkKind == null || chunkKind.isBlank()) throw new IllegalArgumentException("Chunk kind is required");
        metadata = Map.copyOf(metadata);
        for (String field : new String[] {"repository_id", "module_id", "relative_path", "language", "file_kind", "source_set", "file_hash"}) {
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

    String text() { return source.substring(startOffset, endOffset); }
}
