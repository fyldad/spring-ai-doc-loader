package ru.anblazhnov.springaidocloader;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.function.context.FunctionCatalog;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@SpringBootApplication
public class SpringAiDocLoaderApplication {

    private static final Logger log = LoggerFactory.getLogger(SpringAiDocLoaderApplication.class);

    static void main(String[] args) {
        SpringApplication.run(SpringAiDocLoaderApplication.class, args);
    }

    @Bean
    @ConditionalOnProperty(name = "file.supplier.enabled", havingValue = "true", matchIfMissing = true)
    ApplicationRunner go(FunctionCatalog catalog) {
        Function<Object, Object> function = catalog.lookup(null);
        return _ -> Flux.from((Publisher<?>) function.apply(null))
                .subscribe(
                        null,
                        error -> log.error("Document loading failed", error),
                        () -> log.info("Document loading complete"));
    }

    @Bean
    Function<Flux<DiscoveredFile>, Flux<FileUnits>> documentReader(DiscoveryProperties properties,
            XmlParsingProperties xmlProperties, JavaParsingProperties javaProperties, ChunkingProperties chunks,
            LoadedFileState loadedState) {
        JavaSourceReader javaReader = new JavaSourceReader(javaProperties, chunks);
        ProjectDocumentReaders readers = new ProjectDocumentReaders(properties);

        return fileFlux ->
                fileFlux.filter(file -> {
                    boolean loaded = loadedState.isLoaded(loadedState.checkpoint(file));
                    if (loaded) log.info("Skipping unchanged file: {}", file.path());
                    return !loaded;
                }).map(file -> {

                    log.info("reading file: {}", file.path());

                    XmlSourceReader xmlReader = new XmlSourceReader(xmlProperties, file);

                    List<SourceUnit> units = file.kind() == DiscoveredFile.FileKind.JAVA
                            ? javaReader.read(file)
                            : SourceText.isXml(file.kind()) ? xmlReader.read(file) : List.of(readers.read(file));
                    return new FileUnits(loadedState.checkpoint(file), units);

                });
    }

    @Bean
    Function<Flux<FileUnits>, Flux<FileDocuments>> splitDocuments(ChunkingProperties properties) {

        var splitter = TokenTextSplitter.builder().withChunkSize(properties.getMaxTokens()).build();
        return documentFlux ->
                documentFlux
                        .map(file -> {
                            List<Document> documents = new ArrayList<>(file.units().stream()
                                .flatMap(unit -> splitter.split(new Document(unit.source(), unit.metadata())).stream())
                                .toList());
                            // Stable IDs let interrupted files retry without duplicating their successful chunks.
                            for (int index = 0; index < documents.size(); index++) {
                                Document document = documents.get(index);
                                documents.set(index, new Document(SourceIdentity.uuid("chunk", file.checkpoint().key(),
                                        file.checkpoint().fileHash(), file.checkpoint().settingsHash(), Integer.toString(index)),
                                        document.getText(), document.getMetadata()));
                            }
                            return new FileDocuments(file.checkpoint(), documents);
                        })
                        .subscribeOn(Schedulers.boundedElastic());
    }

    @Bean
    Function<Flux<FileDocuments>, Mono<Void>> vectorStoreWriter(
            VectorStore vectorStore, WriterProperties properties, LoadedFileState loadedState) {

        properties.validate();
        int batchSize = properties.getBatchSize();
        int concurrency = properties.getConcurrency();
        return documents -> Mono.defer(() -> {
            long started = System.nanoTime();
            LongAdder storedChunks = new LongAdder();
            LongAdder storedBatches = new LongAdder();
            log.info("Starting vector store writer: batch size {}, concurrency {}", batchSize, concurrency);
            return documents
                    .concatMapIterable(file -> {
                        FileProgress progress = new FileProgress(file.checkpoint(), file.documents().size(), loadedState);
                        if (file.documents().isEmpty()) loadedState.markLoaded(file.checkpoint());
                        return file.documents().stream().map(document -> new PendingChunk(document, progress)).toList();
                    }, 1)
                    .buffer(batchSize)
                    .flatMap(batch -> writeBatch(vectorStore, batch.stream().map(PendingChunk::document).toList())
                            .doOnSuccess(_ -> {
                                storedChunks.add(batch.size());
                                storedBatches.increment();
                                batch.forEach(chunk -> chunk.progress().stored());
//                            }), concurrency, 1)
                            }))
                    .then()
                    .doFinally(signal -> log.info(
                            "Vector store writer finished ({}): stored {} chunks in {} batches, elapsed {} ms",
                            signal, storedChunks.sum(), storedBatches.sum(),
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
        });
    }

    Mono<Void> writeBatch(
            VectorStore vectorStore,
            List<Document> batch) {

        return Mono.fromRunnable(() -> {
                    if (batch != null && !batch.isEmpty()) {
                        long started = System.nanoTime();
                        vectorStore.accept(batch);
                        log.info("Stored {} document chunks in {} ms (embedding and Qdrant write)",
                                batch.size(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    record FileUnits(LoadedFileState.Checkpoint checkpoint, List<SourceUnit> units) { }
    record FileDocuments(LoadedFileState.Checkpoint checkpoint, List<Document> documents) { }
    private record PendingChunk(Document document, FileProgress progress) { }

    private static final class FileProgress {
        private final LoadedFileState.Checkpoint checkpoint;
        private final AtomicInteger remaining;
        private final LoadedFileState state;

        FileProgress(LoadedFileState.Checkpoint checkpoint, int chunks, LoadedFileState state) {
            this.checkpoint = checkpoint;
            this.remaining = new AtomicInteger(chunks);
            this.state = state;
        }

        void stored() {
            if (remaining.decrementAndGet() == 0) state.markLoaded(checkpoint);
        }
    }

}
