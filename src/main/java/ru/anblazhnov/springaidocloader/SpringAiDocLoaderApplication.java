package ru.anblazhnov.springaidocloader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
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

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

@SpringBootApplication
public class SpringAiDocLoaderApplication {

    static void main(String[] args) {
        SpringApplication.run(SpringAiDocLoaderApplication.class, args);
    }

    @Bean
    ApplicationRunner go(FunctionCatalog catalog) {
        Runnable function = catalog.lookup(null);
        return _ -> function.run();
    }

    @Bean
    Function<Flux<byte[]>, Flux<Document>> documentReader() {
        return resource -> resource
                .map(bytes -> new Document(new String(bytes)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Bean
    Function<Flux<Document>, Flux<List<Document>>> splitter() {
        TokenTextSplitter splitter = TokenTextSplitter.builder().build();
        return resource -> resource
                .map(splitter::split)
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Bean
    Function<Flux<List<Document>>, Flux<List<Document>>> scopeDeterminer() {
//        too expensive to call llm on every document
        return resource -> resource
                .map(documents -> {
                    documents.forEach(document -> document.getMetadata().put("scope", "java"));
                    return documents;
                });
    }

    @Bean
    Consumer<Flux<List<Document>>> vectorStoreConsumer(VectorStore vectorStore) {
        return flux -> flux
                .doOnNext(vectorStore)
                .subscribe();
    }

}
