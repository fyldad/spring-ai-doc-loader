package ru.anblazhnov.springaidocloader;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.function.context.FunctionCatalog;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

@SpringBootApplication
public class SpringAiDocLoaderApplication {

    private static final Logger log = LoggerFactory.getLogger(SpringAiDocLoaderApplication.class);

    static void main(String[] args) {
        SpringApplication.run(SpringAiDocLoaderApplication.class, args);
    }

    @Bean
    ApplicationRunner go(FunctionCatalog catalog) {
        Function<Object, Object> function = catalog.lookup(null);
        return _ -> Flux.from((Publisher<?>) function.apply(null))
                .subscribe(null, error -> log.error("Document loading failed", error));
    }

    @Bean
    Function<Flux<File>, Flux<Document>> documentReader() {
        return resource -> resource
                .map(file -> {
                    try {
                        Path path = file.toPath();
                        log.info("loading file: {}", path);
                        return new Document(Files.readString(path));
                    }
                    catch (IOException error) {
                        throw new UncheckedIOException("Cannot read file " + file, error);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic());
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
    Function<Flux<List<Document>>, Flux<List<Document>>> scopeDeterminer() {
//        too expensive to call llm on every document
        return resource -> resource
                .map(documents -> {
                    documents.forEach(document -> document.getMetadata().put("scope", "iflex"));
                    return documents;
                });
    }

    @Bean
    Consumer<Flux<List<Document>>> vectorStoreConsumer(VectorStore vectorStore) {
        return flux -> flux
                .filter(documents -> !documents.isEmpty())
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(vectorStore)
                .doOnError(e -> log.error("Error saving to vector store", e))
                .subscribe();
    }

}
