package ru.anblazhnov.springaidocloader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/** Content-addressed full sources and parent excerpts, kept outside vector payloads. */
final class SourceSnapshotStore {
    private final Path directory;

    SourceSnapshotStore(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }

    String save(String parentId, String text) {
        String reference = parentId + "/" + SourceIdentity.hash(text) + ".txt";
        Path target = resolve(reference);
        try {
            Files.createDirectories(target.getParent());
            if (!Files.exists(target)) {
                Path temporary = Files.createTempFile(target.getParent(), "snapshot-", ".tmp");
                try {
                    Files.writeString(temporary, text, StandardCharsets.UTF_8);
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }
                finally { Files.deleteIfExists(temporary); }
            }
            return reference;
        }
        catch (IOException error) { throw new UncheckedIOException("Cannot persist source snapshot", error); }
    }

    String read(String reference) {
        Path target = resolve(reference);
        try {
            String text = Files.readString(target, StandardCharsets.UTF_8);
            String expectedHash = target.getFileName().toString().replace(".txt", "");
            if (!SourceIdentity.hash(text).equals(expectedHash)) throw new IOException("Source snapshot hash mismatch");
            return text;
        }
        catch (IOException error) { throw new UncheckedIOException(error); }
    }

    String excerpt(Map<String, Object> metadata) {
        String source = read((String) metadata.get("source_snapshot"));
        return source.substring(((Number) metadata.get("start_offset")).intValue(),
                ((Number) metadata.get("end_offset")).intValue());
    }

    private Path resolve(String reference) {
        if (reference == null || !reference.matches("[0-9a-f-]{36}/[0-9a-f]{64}\\.txt")) {
            throw new IllegalArgumentException("Invalid snapshot reference");
        }
        Path target = directory.resolve(reference).normalize();
        if (!target.startsWith(directory)) throw new IllegalArgumentException("Snapshot outside configured directory");
        return target;
    }
}
