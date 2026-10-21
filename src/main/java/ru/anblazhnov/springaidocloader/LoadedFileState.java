package ru.anblazhnov.springaidocloader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * Durable append-only checkpoints. Only completed files are recorded.
 */
final class LoadedFileState {
    private static final Logger log = LoggerFactory.getLogger(LoadedFileState.class);
    private final Path path;
    private final String settingsHash;
    private final Map<String, Checkpoint> loaded = new HashMap<>();

    record Checkpoint(String key, String fileHash, String settingsHash, String sourcePath) {
    }

    LoadedFileState(Path path, String settingsHash) {
        this.path = path.toAbsolutePath().normalize();
        this.settingsHash = settingsHash;
        load();
    }

    static String settingsHash(Environment environment, DiscoveryProperties discovery, ChunkingProperties chunks,
                               JavaParsingProperties java, XmlParsingProperties xml) {
        Binder binder = Binder.get(environment);
        return SourceIdentity.hash(canonical(List.of(
                "loaded-state-v1", JavaSourceReader.VERSION, XmlSourceReader.VERSION,
                "full-file-reader-v1", "token-text-splitter-v1",
                String.valueOf(org.springframework.ai.document.Document.class.getPackage().getImplementationVersion()),
                chunks.getMaxTokens(), java.getLanguageLevel(), java.isResolveSymbols(),
                java.getSourceRoots(), java.getDependencyJars(),
                xml.getMaxImportDepth(), xml.getMaxImportFiles(), xml.getElementNames(), xml.getMavenResolutionPolicy(),
                discovery.getEncoding(), discovery.getSensitiveKeyPattern(),
                environment.getProperty("spring.ai.ollama.base-url", "http://localhost:11434"),
                binder.bind("spring.ai.ollama.embedding", Bindable.mapOf(String.class, Object.class)).orElse(Map.of()),
                binder.bind("spring.ai.vectorstore.qdrant", Bindable.mapOf(String.class, Object.class)).orElse(Map.of()))));
    }

    Checkpoint checkpoint(DiscoveredFile file) {
        String sourcePath = file.path().toAbsolutePath().normalize().toString();
        String key = SourceIdentity.hash(canonical(List.of(sourcePath,
                file.metadata().get("repository_id"), file.metadata().get("module_id"),
                file.metadata().get("relative_path"))));
        return new Checkpoint(key, (String) file.metadata().get("file_hash"), settingsHash, sourcePath);
    }

    boolean isStateFile(Path candidate) {
        return path.equals(candidate.toAbsolutePath().normalize());
    }

    synchronized boolean isLoaded(Checkpoint checkpoint) {
        return checkpoint.equals(loaded.get(checkpoint.key()));
    }

    synchronized void markLoaded(Checkpoint checkpoint) {
        if (isLoaded(checkpoint)) return;
        String payload = String.join("\t", "v1", checkpoint.key(), checkpoint.fileHash(), checkpoint.settingsHash(),
                Base64.getUrlEncoder().encodeToString(checkpoint.sourcePath().getBytes(StandardCharsets.UTF_8)));
        // A leading newline separates a previous interrupted append from this record.
        ByteBuffer bytes = StandardCharsets.UTF_8.encode("\n" + payload + "\t" + SourceIdentity.hash(payload) + "\n");
        try {
            Files.createDirectories(path.getParent());
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            loaded.put(checkpoint.key(), checkpoint);
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot save loaded-file state to " + path, error);
        }
    }

    private void load() {
        if (Files.notExists(path)) return;
        int invalid = 0;
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] fields = line.split("\t", -1);
                if (fields.length != 6 || !fields[0].equals("v1")
                        || !SourceIdentity.hash(line.substring(0, line.lastIndexOf('\t'))).equals(fields[5])) {
                    invalid++;
                    continue;
                }
                try {
                    Checkpoint checkpoint = new Checkpoint(fields[1], fields[2], fields[3],
                            new String(Base64.getUrlDecoder().decode(fields[4]), StandardCharsets.UTF_8));
                    loaded.put(checkpoint.key(), checkpoint);
                } catch (IllegalArgumentException error) {
                    invalid++;
                }
            }
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot read loaded-file state from " + path, error);
        }
        log.info("Read {} file checkpoints from {}", loaded.size(), path);
        if (invalid > 0) log.warn("Ignored {} incomplete or invalid checkpoint records in {}", invalid, path);
    }

    private static String canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), item));
            return "{" + sorted.entrySet().stream()
                    .map(entry -> canonical(entry.getKey()) + canonical(entry.getValue()))
                    .collect(java.util.stream.Collectors.joining()) + "}";
        }
        if (value instanceof Collection<?> collection) {
            return "[" + collection.stream().map(LoadedFileState::canonical)
                    .collect(java.util.stream.Collectors.joining()) + "]";
        }
        String text = String.valueOf(value);
        return text.length() + ":" + text;
    }
}
