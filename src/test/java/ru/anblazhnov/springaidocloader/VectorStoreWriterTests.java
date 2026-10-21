package ru.anblazhnov.springaidocloader;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class VectorStoreWriterTests {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void combinesFilesIntoBatchesAndWritesTheFinalPartialBatch() {
        VectorStore store = mock(VectorStore.class);
        List<List<Document>> writes = new ArrayList<>();
        doAnswer(invocation -> {
            writes.add(List.copyOf(invocation.<List<Document>>getArgument(0)));
            return null;
        }).when(store).accept(anyList());
        List<Document> chunks = chunks(5);
        var writer = new SpringAiDocLoaderApplication().vectorStoreWriter(store, settings(3, 1), mock(LoadedFileState.class));

        writer.apply(Flux.just(file(chunks.subList(0, 2)), file(List.of()), file(chunks.subList(2, 5)))).block(TIMEOUT);

        assertThat(writes).containsExactly(chunks.subList(0, 3), chunks.subList(3, 5));
    }

    @Test
    void keepsWritesWithinConfiguredConcurrency() throws Exception {
        VectorStore store = mock(VectorStore.class);
        CountDownLatch firstTwoStarted = new CountDownLatch(2);
        CountDownLatch releaseWrites = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        doAnswer(_ -> {
            maximumActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            firstTwoStarted.countDown();
            try {
                if (!releaseWrites.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Writes not released");
                completed.incrementAndGet();
            } finally {
                active.decrementAndGet();
            }
            return null;
        }).when(store).accept(anyList());
        var writer = new SpringAiDocLoaderApplication().vectorStoreWriter(store, settings(2, 2), mock(LoadedFileState.class));
        var completion = writer.apply(Flux.just(file(chunks(8)))).toFuture();

        try {
            assertThat(firstTwoStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(active.get()).isEqualTo(2);
        } finally {
            releaseWrites.countDown();
        }
        completion.get(10, TimeUnit.SECONDS);

        assertThat(maximumActive.get()).isEqualTo(2);
        assertThat(completed.get()).isEqualTo(4);
    }

    @Test
    void doesNotWriteEmptyInput() {
        VectorStore store = mock(VectorStore.class);
        var writer = new SpringAiDocLoaderApplication().vectorStoreWriter(store, settings(3, 2), mock(LoadedFileState.class));

        writer.apply(Flux.just(file(List.of()))).block(TIMEOUT);

        verifyNoInteractions(store);
    }

    @Test
    void propagatesWriteFailures() {
        VectorStore store = mock(VectorStore.class);
        IllegalStateException failure = new IllegalStateException("Embedding unavailable");
        doThrow(failure).when(store).accept(anyList());
        var writer = new SpringAiDocLoaderApplication().vectorStoreWriter(store, settings(3, 1), mock(LoadedFileState.class));

        assertThatThrownBy(() -> writer.apply(Flux.just(file(chunks(2)))).block(TIMEOUT)).isSameAs(failure);
    }

    @Test
    void bindsWriterSettingsAndRejectsInvalidConfigurationAtStartup() {
        var runner = new ApplicationContextRunner().withUserConfiguration(WriterConfiguration.class);
        runner.withPropertyValues("ingestion.writer.batch-size=16", "ingestion.writer.concurrency=1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(WriterProperties.class).getBatchSize()).isEqualTo(16);
                    assertThat(context.getBean(WriterProperties.class).getConcurrency()).isEqualTo(1);
                });
        for (String invalid : List.of("ingestion.writer.batch-size=0", "ingestion.writer.concurrency=-1")) {
            runner.withPropertyValues(invalid).run(context -> assertThat(context).hasFailed());
        }
    }

    private WriterProperties settings(int batchSize, int concurrency) {
        WriterProperties properties = new WriterProperties();
        properties.setBatchSize(batchSize);
        properties.setConcurrency(concurrency);
        return properties;
    }

    private SpringAiDocLoaderApplication.FileDocuments file(List<Document> documents) {
        return new SpringAiDocLoaderApplication.FileDocuments(
                new LoadedFileState.Checkpoint(java.util.UUID.randomUUID().toString(), "hash", "settings", "source.java"),
                documents);
    }

    private List<Document> chunks(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> new Document("chunk " + index)).toList();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WriterProperties.class)
    static class WriterConfiguration {
    }
}
