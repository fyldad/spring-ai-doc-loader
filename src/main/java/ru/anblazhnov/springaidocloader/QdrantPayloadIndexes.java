package ru.anblazhnov.springaidocloader;

import java.time.Duration;
import java.util.List;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"ingestion.qdrant.payload-indexes", "spring.ai.vectorstore.qdrant.initialize-schema"}, havingValue = "true")
class QdrantPayloadIndexes {
    static final List<String> FIELDS = List.of("repository_id", "module_id", "language", "chunk_kind");

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    ApplicationRunner payloadIndexes(QdrantClient client,
            @Value("${spring.ai.vectorstore.qdrant.collection-name:vector_store}") String collection) {
        // Vector store schema initialization finishes during bean creation, before runners.
        return _ -> {
            for (String field : FIELDS) {
                client.createPayloadIndexAsync(collection, field, PayloadSchemaType.Keyword,
                        null, true, null, Duration.ofSeconds(30)).get();
            }
        };
    }
}
