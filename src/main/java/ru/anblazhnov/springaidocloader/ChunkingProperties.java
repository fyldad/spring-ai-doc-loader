package ru.anblazhnov.springaidocloader;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ingestion.chunks")
public class ChunkingProperties {
    private int maxCharacters = 1200;
    private int javaMaxTokens = 800;
    private Path snapshotDirectory = Path.of(".ingestion/source-snapshots");

    public int getMaxCharacters() { return maxCharacters; }
    public void setMaxCharacters(int maxCharacters) { this.maxCharacters = maxCharacters; }
    public int getJavaMaxTokens() { return javaMaxTokens; }
    public void setJavaMaxTokens(int javaMaxTokens) { this.javaMaxTokens = javaMaxTokens; }
    public Path getSnapshotDirectory() { return snapshotDirectory; }
    public void setSnapshotDirectory(Path snapshotDirectory) { this.snapshotDirectory = snapshotDirectory; }

    void validate() {
        if (maxCharacters < 128) throw new IllegalArgumentException("ingestion.chunks.max-characters must be at least 128");
        if (javaMaxTokens < 128) throw new IllegalArgumentException("ingestion.chunks.java-max-tokens must be at least 128");
        if (snapshotDirectory == null) throw new IllegalArgumentException("Source snapshot directory is required");
    }
}
