package ru.anblazhnov.springaidocloader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class LoadedFileStateTests {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    @TempDir Path temporary;

    @Test
    void skipsSuccessfullyStoredFilesAfterRestartAndReloadsChangedContent() {
        LoadedFileState state = state("settings");
        DiscoveredFile original = source("notes.txt", "original source");
        load(mock(VectorStore.class), state, original);

        LoadedFileState restarted = state("settings");
        VectorStore unchangedStore = mock(VectorStore.class);
        load(unchangedStore, restarted, original);
        verifyNoInteractions(unchangedStore);

        DiscoveredFile changed = source("notes.txt", "changed source");
        assertThat(restarted.isLoaded(restarted.checkpoint(changed))).isFalse();
        load(mock(VectorStore.class), restarted, changed);

        LoadedFileState afterChange = state("settings");
        assertThat(afterChange.isLoaded(afterChange.checkpoint(changed))).isTrue();
        assertThat(afterChange.isLoaded(afterChange.checkpoint(original))).isFalse();
        assertThat(state("different-model").isLoaded(state("different-model").checkpoint(changed))).isFalse();
    }

    @Test
    void leavesPartiallyStoredFilesPendingWhileKeepingCompletedFiles() {
        LoadedFileState state = state("settings");
        var completed = documents(state, "small.txt", 1);
        var partial = documents(state, "large.txt", 4);
        VectorStore store = mock(VectorStore.class);
        AtomicInteger calls = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("Qdrant unavailable");
        doAnswer(_ -> {
            if (calls.incrementAndGet() == 3) throw failure;
            return null;
        }).when(store).accept(anyList());
        var writer = new SpringAiDocLoaderApplication().vectorStoreWriter(store, settings(2, 1), state);

        assertThatThrownBy(() -> writer.apply(Flux.just(completed, partial)).block(TIMEOUT)).isSameAs(failure);

        LoadedFileState restarted = state("settings");
        assertThat(restarted.isLoaded(completed.checkpoint())).isTrue();
        assertThat(restarted.isLoaded(partial.checkpoint())).isFalse();
    }

    @Test
    void waitsForEveryConcurrentBatchBeforeSavingAFile() throws Exception {
        LoadedFileState state = state("settings");
        var file = documents(state, "large.txt", 4);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        VectorStore store = mock(VectorStore.class);
        doAnswer(invocation -> {
            List<Document> batch = invocation.getArgument(0);
            if (batch.getFirst().getText().equals("chunk 0")) {
                firstStarted.countDown();
                if (!releaseFirst.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("First batch not released");
            }
            return null;
        }).when(store).accept(anyList());
        var application = new SpringAiDocLoaderApplication() {
            @Override
            reactor.core.publisher.Mono<Void> writeBatch(VectorStore vectorStore, List<Document> batch) {
                return super.writeBatch(vectorStore, batch).doOnSuccess(_ -> {
                    if (batch.getFirst().getText().equals("chunk 2")) secondFinished.countDown();
                });
            }
        };
        var completion = application.vectorStoreWriter(store, settings(2, 2), state)
                .apply(Flux.just(file)).toFuture();
        try {
            assertThat(firstStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(secondFinished.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(state("settings").isLoaded(file.checkpoint())).isFalse();
        } finally {
            releaseFirst.countDown();
        }
        completion.get(10, TimeUnit.SECONDS);
        assertThat(state("settings").isLoaded(file.checkpoint())).isTrue();
    }

    @Test
    void givesRetriesTheSameDocumentIds() {
        LoadedFileState state = state("settings");
        DiscoveredFile source = source("notes.txt", "source to retry");
        List<String> firstIds = new ArrayList<>();
        List<String> retryIds = new ArrayList<>();
        VectorStore failingStore = mock(VectorStore.class);
        doAnswer(invocation -> {
            List<Document> batch = invocation.getArgument(0);
            firstIds.addAll(batch.stream().map(Document::getId).toList());
            throw new IllegalStateException("Write failed");
        }).when(failingStore).accept(anyList());
        assertThatThrownBy(() -> load(failingStore, state, source)).isInstanceOf(IllegalStateException.class);
        VectorStore retryStore = mock(VectorStore.class);
        doAnswer(invocation -> {
            List<Document> batch = invocation.getArgument(0);
            retryIds.addAll(batch.stream().map(Document::getId).toList());
            return null;
        }).when(retryStore).accept(anyList());

        load(retryStore, state("settings"), source);

        assertThat(retryIds).isNotEmpty().containsExactlyElementsOf(firstIds);
    }

    @Test
    void toleratesInterruptedAppendsAndCanSaveFurtherCheckpoints() throws Exception {
        LoadedFileState state = state("settings");
        var first = state.checkpoint(source("first.txt", "first"));
        state.markLoaded(first);
        Files.writeString(temporary.resolve("loaded.txt"), "v1\tinterrupted", StandardOpenOption.APPEND);
        LoadedFileState restarted = state("settings");
        assertThat(restarted.isLoaded(first)).isTrue();
        var second = restarted.checkpoint(source("second.txt", "second"));
        restarted.markLoaded(second);

        LoadedFileState afterRestart = state("settings");
        assertThat(afterRestart.isLoaded(first)).isTrue();
        assertThat(afterRestart.isLoaded(second)).isTrue();
    }

    @Test
    void recordsFilesThatProduceNoChunks() {
        LoadedFileState state = state("settings");
        var file = documents(state, "empty.txt", 0);
        VectorStore store = mock(VectorStore.class);

        new SpringAiDocLoaderApplication().vectorStoreWriter(store, settings(2, 2), state)
                .apply(Flux.just(file)).block(TIMEOUT);

        verifyNoInteractions(store);
        assertThat(state("settings").isLoaded(file.checkpoint())).isTrue();
    }

    @Test
    void excludesTheStateFileFromDiscoveryEvenWithoutGitignore() throws Exception {
        Path root = temporary.resolve("repo");
        Files.createDirectories(root);
        Files.writeString(root.resolve("notes.txt"), "source");
        LoadedFileState state = new LoadedFileState(root.resolve("loaded.txt"), "settings");
        state.markLoaded(state.checkpoint(source("notes.txt", "source")));
        DiscoveryProperties discovery = new DiscoveryProperties();
        discovery.setRepositories(List.of(new DiscoveryProperties.Repository("repo", root)));
        discovery.setRespectGitignore(false);
        discovery.setReport(temporary.resolve("report.csv"));

        var files = new FileSourceConfiguration().fileTreeSupplier(discovery, state).get().collectList().block(TIMEOUT);

        assertThat(files).extracting(file -> file.path().getFileName().toString()).containsExactly("notes.txt");
    }

    @Test
    void invalidatesOnModelDestinationAndChunkingChangesButNotWriterTuning() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.ollama.embedding.model", "bge-m3")
                .withProperty("spring.ai.vectorstore.qdrant.collection-name", "code");
        ChunkingProperties chunks = new ChunkingProperties();
        String initial = fingerprint(environment, chunks);
        environment.setProperty("ingestion.writer.concurrency", "4");
        environment.setProperty("ingestion.writer.batch-size", "16");
        assertThat(fingerprint(environment, chunks)).isEqualTo(initial);
        environment.setProperty("spring.ai.ollama.embedding.model", "different-model");
        assertThat(fingerprint(environment, chunks)).isNotEqualTo(initial);
        environment.setProperty("spring.ai.ollama.embedding.model", "bge-m3");
        environment.setProperty("spring.ai.vectorstore.qdrant.collection-name", "other-code");
        assertThat(fingerprint(environment, chunks)).isNotEqualTo(initial);
        environment.setProperty("spring.ai.vectorstore.qdrant.collection-name", "code");
        chunks.setMaxTokens(800);
        assertThat(fingerprint(environment, chunks)).isNotEqualTo(initial);
    }

    private String fingerprint(MockEnvironment environment, ChunkingProperties chunks) {
        return LoadedFileState.settingsHash(environment, new DiscoveryProperties(), chunks,
                new JavaParsingProperties(), new XmlParsingProperties());
    }

    private void load(VectorStore store, LoadedFileState state, DiscoveredFile file) {
        var application = new SpringAiDocLoaderApplication();
        var chunks = new ChunkingProperties();
        var units = application.documentReader(new DiscoveryProperties(), new XmlParsingProperties(),
                new JavaParsingProperties(), chunks, state).apply(Flux.just(file));
        application.vectorStoreWriter(store, settings(2, 2), state)
                .apply(application.splitDocuments(chunks).apply(units)).block(TIMEOUT);
    }

    private LoadedFileState state(String settings) {
        return new LoadedFileState(temporary.resolve("loaded.txt"), SourceIdentity.hash(settings));
    }

    private DiscoveredFile source(String name, String text) {
        return new DiscoveredFile(temporary.resolve(name), DiscoveredFile.FileKind.PROSE, text,
                Map.of("repository_id", "repo", "module_id", "repo:.", "relative_path", name,
                        "language", "text", "file_kind", "prose", "source_set", "other",
                        "file_hash", SourceIdentity.hash(text)));
    }

    private SpringAiDocLoaderApplication.FileDocuments documents(LoadedFileState state, String name, int count) {
        return new SpringAiDocLoaderApplication.FileDocuments(state.checkpoint(source(name, "source")),
                java.util.stream.IntStream.range(0, count).mapToObj(index -> new Document("chunk " + index)).toList());
    }

    private WriterProperties settings(int batchSize, int concurrency) {
        WriterProperties properties = new WriterProperties();
        properties.setBatchSize(batchSize);
        properties.setConcurrency(concurrency);
        return properties;
    }
}
