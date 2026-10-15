package ru.anblazhnov.springaidocloader;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ingestion.chunks")
public class ChunkingProperties {
    private int maxTokens = 1200;
    private Path snapshotDirectory = Path.of(".ingestion/source-snapshots");

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    public Path getSnapshotDirectory() { return snapshotDirectory; }
    public void setSnapshotDirectory(Path snapshotDirectory) { this.snapshotDirectory = snapshotDirectory; }

    void validate() {
        if (maxTokens < 128) throw new IllegalArgumentException("ingestion.chunks.java-max-tokens must be at least 128");
        if (snapshotDirectory == null) throw new IllegalArgumentException("Source snapshot directory is required");
    }
}
