package ru.anblazhnov.springaidocloader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

final class DiscoveryReport implements AutoCloseable {
    private final BufferedWriter writer;

    DiscoveryReport(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        writer = Files.newBufferedWriter(absolute, StandardCharsets.UTF_8);
        writer.write("repository_id,module_id,relative_path,source_set,file_kind,generated,status,reason\n");
        writer.flush();
    }

    synchronized void record(Map<String, Object> metadata, String kind, String status, String reason) {
        try {
            String[] fields = {
                    String.valueOf(metadata.getOrDefault("repository_id", "")),
                    String.valueOf(metadata.getOrDefault("module_id", "")),
                    String.valueOf(metadata.getOrDefault("relative_path", "")),
                    String.valueOf(metadata.getOrDefault("source_set", "other")), kind,
                    String.valueOf(metadata.getOrDefault("generated", false)), status, reason
            };
            for (int index = 0; index < fields.length; index++) {
                if (index > 0) writer.write(',');
                writer.write('"');
                writer.write(fields[index].replace("\"", "\"\""));
                writer.write('"');
            }
            writer.newLine();
            writer.flush();
        }
        catch (IOException error) { throw new UncheckedIOException("Cannot write discovery report", error); }
    }

    @Override public synchronized void close() throws IOException { writer.close(); }
}
