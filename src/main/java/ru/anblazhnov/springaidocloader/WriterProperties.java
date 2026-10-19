package ru.anblazhnov.springaidocloader;

import java.nio.file.Path;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ingestion.writer")
public class WriterProperties implements InitializingBean {
    private int batchSize = 32;
    private int concurrency = 2;
    private Path stateFile = Path.of("loaded.txt");

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
    public Path getStateFile() { return stateFile; }
    public void setStateFile(Path stateFile) { this.stateFile = stateFile; }

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    void validate() {
        if (batchSize < 1) throw new IllegalArgumentException("ingestion.writer.batch-size must be at least 1");
        if (concurrency < 1) throw new IllegalArgumentException("ingestion.writer.concurrency must be at least 1");
        if (stateFile == null || stateFile.toString().isBlank())
            throw new IllegalArgumentException("ingestion.writer.state-file must not be blank");
    }
}
