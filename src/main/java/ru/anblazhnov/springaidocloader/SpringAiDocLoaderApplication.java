package ru.anblazhnov.springaidocloader;

import java.util.List;
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
    Function<Flux<DiscoveredFile>, Flux<List<SourceUnit>>> documentReader(DiscoveryProperties properties,
                                                                          XmlParsingProperties xmlProperties, JavaParsingProperties javaProperties, ChunkingProperties chunks) {
        JavaSourceReader javaReader = new JavaSourceReader(javaProperties, chunks);
        ProjectDocumentReaders readers = new ProjectDocumentReaders(properties);

        return fileFlux ->
                fileFlux.map(file -> {

                    log.info("reading file: {}", file.path());

                    XmlSourceReader xmlReader = new XmlSourceReader(xmlProperties, file);

                    return file.kind() == DiscoveredFile.FileKind.JAVA
                            ? javaReader.read(file)
                            : SourceText.isXml(file.kind()) ? xmlReader.read(file) : List.of(readers.read(file));

                });
    }

    @Bean
    Function<Flux<List<SourceUnit>>, Flux<List<Document>>> splitDocuments(ChunkingProperties properties) {

        var splitter = TokenTextSplitter.builder().withChunkSize(properties.getMaxTokens()).build();
        return documentFlux ->
                documentFlux
                        .map(units -> units.stream()
                                .flatMap(unit -> splitter.split(new Document(unit.source(), unit.metadata())).stream())
                                .toList())
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
                    if (batch != null && !batch.isEmpty()) {
                        vectorStore.accept(batch);
                        log.info("Stored {} document chunks", batch.size());
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

}
