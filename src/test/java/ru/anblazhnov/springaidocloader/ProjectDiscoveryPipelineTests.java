package ru.anblazhnov.springaidocloader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.reactivestreams.Publisher;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.function.context.FunctionCatalog;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

@SpringBootTest
@DirtiesContext
class ProjectDiscoveryPipelineTests {
    @Autowired FunctionCatalog catalog;
    @Autowired DiscoveryProperties properties;
    @MockitoBean VectorStore vectorStore;

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void composesDiscoverySourceUnitsDocumentsAndWriterWithoutEmbeddingServices(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("service.wsdl"), "<definitions xmlns='http://schemas.xmlsoap.org/wsdl/' name='CustomerService'/>");
        Files.writeString(root.resolve("application.yaml"), "password: never-embed-this\nservice: customer\n");
        properties.setRepositories(List.of(new DiscoveryProperties.Repository("customer", root)));
        properties.setReport(root.resolve("discovery.csv"));
        Function<Object, Object> pipeline = catalog.lookup("fileTreeSupplier|documentReader|createDocuments|vectorStoreWriter");

        Flux.from((Publisher<?>) pipeline.apply(null)).blockLast(Duration.ofSeconds(10));

        ArgumentCaptor<List<Document>> batches = ArgumentCaptor.forClass((Class) List.class);
        verify(vectorStore, atLeastOnce()).accept(batches.capture());
        List<Document> documents = batches.getAllValues().stream().flatMap(List::stream).toList();
        assertThat(documents).isNotEmpty();
        assertThat(documents).allSatisfy(document -> assertThat(document.getMetadata()).containsEntry("repository_id", "customer"));
        SourceSnapshotStore snapshots = new SourceSnapshotStore(Path.of("build/test-source-snapshots"));
        assertThat(documents).allSatisfy(document -> {
            assertThat(document.getId()).isEqualTo(document.getMetadata().get("chunk_id"));
            assertThat(document.getMetadata()).containsKeys("parent_id", "start_line", "end_line", "file_hash", "chunk_hash");
            assertThat(document.getText()).startsWith("Repository: customer\n").endsWith(snapshots.excerpt(document.getMetadata()));
            assertThat(snapshots.read((String) document.getMetadata().get("parent_snapshot"))).isNotEmpty();
        });
        assertThat(documents).anySatisfy(document -> assertThat(document.getMetadata()).containsEntry("file_kind", "wsdl"));
        assertThat(documents).anySatisfy(document -> {
            assertThat(document.getMetadata()).containsEntry("reader", "yaml");
            assertThat(document.getText()).contains("REDACTED").doesNotContain("never-embed-this");
        });
        assertThat(Files.readString(properties.getReport())).doesNotContain("never-embed-this");
    }

    @Test
    void bindsExplicitRepositoriesAndDiscoveryOptions(@TempDir Path root) {
        new ApplicationContextRunner().withUserConfiguration(FileSourceConfiguration.class)
                .withPropertyValues("file.supplier.repositories[0].id=stable-id",
                        "file.supplier.repositories[0].root=" + root,
                        "file.supplier.max-file-size=4096", "file.supplier.excluded-paths[0]=private/**")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    DiscoveryProperties bound = context.getBean(DiscoveryProperties.class);
                    assertThat(bound.getRepositories()).containsExactly(new DiscoveryProperties.Repository("stable-id", root));
                    assertThat(bound.getMaxFileSize()).isEqualTo(4096);
                    assertThat(bound.getExcludedPaths()).containsExactly("private/**");
                });
    }
}
