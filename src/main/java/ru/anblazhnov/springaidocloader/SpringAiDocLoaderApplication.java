package ru.anblazhnov.springaidocloader;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.function.context.FunctionCatalog;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@SpringBootApplication
public class SpringAiDocLoaderApplication {

    private static final Logger log = LoggerFactory.getLogger(SpringAiDocLoaderApplication.class);

    @Value("${file.supplier.directory}")
    private String directory;

    static void main(String[] args) {
        SpringApplication.run(SpringAiDocLoaderApplication.class, args);
    }

    @Bean
    ApplicationRunner go(FunctionCatalog catalog) {
        Function<Object, Object> function = catalog.lookup(null);
        return _ -> Flux.from((Publisher<?>) function.apply(null))
                .subscribe(
                        null,
                        error -> log.error("Document loading failed", error),
                        () -> log.info("Document loading complete"));
    }

    @Bean
    Function<Flux<File>, Flux<Document>> documentReader() {
        return files -> files.concatMap(file -> Mono.fromCallable(() -> readDocument(file))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(error -> {
                    log.warn("Skipping unreadable file: {}", file, error);
                    return Mono.empty();
                }), 1);
    }

    @Bean
    Function<Flux<Document>, Flux<List<Document>>> splitter() {
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(300)
                .build();
        return resource -> resource
                .map(splitter::split)
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Bean
    Function<Flux<List<Document>>, Mono<Void>> vectorStoreWriter(
            VectorStore vectorStore) {

        return documents -> documents
//                .buffer(batchSize)
                .filter(batch -> !batch.isEmpty())
                .concatMap(batch -> writeBatch(
                        vectorStore, batch), 1)
                .then();
    }

    Mono<Void> writeBatch(
            VectorStore vectorStore,
            List<Document> batch) {

        return Mono.fromRunnable(() -> {
            if (batch == null || batch.isEmpty()) {
                vectorStore.accept(batch);
                log.info("Stored {} document chunks", batch.size());
            }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private Document readDocument(File file) {
        try {
            Path path = file.toPath();
            log.info("Loading file: {}", path);
            return new Document(Files.readString(path), getMetaData(path.toString()));
        }
        catch (IOException error) {
            throw new UncheckedIOException("Cannot read file " + file, error);
        }
    }

    private Map<String, Object> getMetaData(String path) {
        return Map.of(
                "scope", "iflex",
                "module", path.replace(directory, "").split("\\\\")[1]
        );
    }

}
