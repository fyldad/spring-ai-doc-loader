package ru.anblazhnov.springaidocloader;

import java.time.Duration;

import com.google.common.util.concurrent.Futures;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Points.UpdateResult;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class QdrantPayloadIndexesTests {
    @Test
    void createsKeywordIndexesForTheConfiguredCollection() throws Exception {
        QdrantClient client = mock(QdrantClient.class);
        when(client.createPayloadIndexAsync(anyString(), anyString(), eq(PayloadSchemaType.Keyword), isNull(), eq(true), isNull(), any(Duration.class)))
                .thenReturn(Futures.immediateFuture(UpdateResult.getDefaultInstance()));
        new QdrantPayloadIndexes().payloadIndexes(client, "fixture-collection").run(new DefaultApplicationArguments());
        for (String field : QdrantPayloadIndexes.FIELDS) {
            verify(client).createPayloadIndexAsync("fixture-collection", field, PayloadSchemaType.Keyword,
                    null, true, null, Duration.ofSeconds(30));
        }
        verifyNoMoreInteractions(client);
    }

    @Test
    void doesNotContactQdrantWhenSchemaInitializationIsDisabled() {
        new ApplicationContextRunner().withUserConfiguration(QdrantPayloadIndexes.class)
                .withPropertyValues("ingestion.qdrant.payload-indexes=true", "spring.ai.vectorstore.qdrant.initialize-schema=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("payloadIndexes"));
    }
}
