package ru.anblazhnov.springaidocloader;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ingestion.chunks")
public class ChunkingProperties {
    private int maxTokens = 1200;
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    void validate() {
        if (maxTokens < 128) throw new IllegalArgumentException("ingestion.chunks.java-max-tokens must be at least 128");
    }
}
