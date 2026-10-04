package ru.anblazhnov.springaidocloader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingProperties;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.ollama.api.OllamaApi.EmbeddingsRequest;
import org.springframework.ai.ollama.api.OllamaApi.EmbeddingsResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SourceDocumentFactoryTests {
    @TempDir Path temporary;

    @Test
    void retainsExactSourceAndLineRangesWithoutCountingHeaders() {
        String source = "package example;\r\n" + "// Привет 😀\r\n".repeat(30) + "class Endpoint {}\r\n";
        SourceDocumentFactory factory = factory(240);
        List<Document> documents = factory.create(unit(source));
        SourceSnapshotStore snapshots = snapshots();
        assertThat(documents).hasSizeGreaterThan(1);
        StringBuilder reconstructed = new StringBuilder();
        int lastOffset = 0;
        for (int index = 0; index < documents.size(); index++) {
            Document document = documents.get(index);
            Map<String, Object> metadata = document.getMetadata();
            String excerpt = snapshots.excerpt(metadata);
            int start = (int) metadata.get("start_offset");
            int end = (int) metadata.get("end_offset");
            assertThat(start).isEqualTo(lastOffset);
            assertThat(metadata).containsEntry("part_index", index).containsEntry("part_count", documents.size());
            assertThat(((Number) metadata.get("start_line")).longValue()).isEqualTo(1 + source.substring(0, start).chars().filter(c -> c == '\n').count());
            assertThat(((Number) metadata.get("end_line")).longValue()).isEqualTo(1 + source.substring(0, end - 1).chars().filter(c -> c == '\n').count());
            assertThat(document.getText()).hasSizeLessThanOrEqualTo(240).endsWith(excerpt);
            assertThat(UUID.fromString(document.getId()).toString()).isEqualTo(metadata.get("chunk_id"));
            assertThat(snapshots.read((String) metadata.get("parent_snapshot"))).isEqualTo(source);
            reconstructed.append(excerpt);
            lastOffset = end;
        }
        assertThat(reconstructed.toString()).isEqualTo(source);
        assertThat(documents.getFirst().getMetadata()).containsEntry("start_line", 1);
    }

    @Test
    void handlesBareCarriageReturnsLongLinesAndUnicodeWithoutDroppingCharacters() {
        String source = "first\rsecond\r" + "😀".repeat(200) + "\rlast";
        List<Document> documents = factory(180).create(unit(source));
        assertThat(documents).hasSizeGreaterThan(2);
        StringBuilder reconstructed = new StringBuilder();
        for (Document document : documents) {
            String excerpt = snapshots().excerpt(document.getMetadata());
            assertThat(Character.isLowSurrogate(excerpt.charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(excerpt.charAt(excerpt.length() - 1))).isFalse();
            reconstructed.append(excerpt);
        }
        assertThat(reconstructed.toString()).isEqualTo(source);
        assertThat(documents.get(1).getMetadata()).containsEntry("start_line", 3);
    }

    @Test
    void preservesCrLfAndSurrogatePairsWithOnlyTwoCharactersLeftAfterTheHeader() {
        String source = "\r\n\r\n😀x\r\ny";
        Map<String, Object> metadata = new LinkedHashMap<>(unit(source).metadata());
        metadata.put("signature", "x".repeat(200));
        SourceUnit unit = new SourceUnit(source, 0, source.length(), "file_excerpt", metadata);
        List<Document> documents = factory(SourceDocumentFactory.header(metadata).length() + 2).create(unit);
        assertThat(documents).extracting(document -> snapshots().excerpt(document.getMetadata()))
                .containsExactly("\r\n", "\r\n", "😀", "x", "\r\n", "y");
    }

    @Test
    void separatesLogicalIdentityFromContentAndRetainsPriorSnapshots() {
        SourceUnit original = unit("class Endpoint { int value = 1; }");
        Document first = factory(1200).create(original).getFirst();
        Document repeat = factory(1200).create(original).getFirst();
        Document edited = factory(1200).create(unit("class Endpoint { int value = 2; }")).getFirst();
        assertThat(repeat.getId()).isEqualTo(first.getId());
        assertThat(repeat.getMetadata()).isEqualTo(first.getMetadata());
        assertThat(edited.getId()).isEqualTo(first.getId());
        assertThat(edited.getMetadata().get("file_hash")).isNotEqualTo(first.getMetadata().get("file_hash"));
        assertThat(edited.getMetadata().get("chunk_hash")).isNotEqualTo(first.getMetadata().get("chunk_hash"));
        assertThat(snapshots().excerpt(first.getMetadata())).isEqualTo(original.text());
        Map<String, Object> otherRepo = new LinkedHashMap<>(original.metadata());
        otherRepo.put("repository_id", "other");
        assertThat(factory(1200).create(new SourceUnit(original.source(), 0, original.source().length(), "file_excerpt", otherRepo))
                .getFirst().getId()).isNotEqualTo(first.getId());
        Map<String, Object> otherModule = new LinkedHashMap<>(original.metadata());
        otherModule.put("module_id", "repo:other");
        assertThat(factory(1200).create(new SourceUnit(original.source(), 0, original.source().length(), "file_excerpt", otherModule))
                .getFirst().getId()).isNotEqualTo(first.getId());
        ChunkingProperties relocated = new ChunkingProperties();
        relocated.setSnapshotDirectory(temporary.resolve("other-checkout"));
        Document moved = new SourceDocumentFactory(relocated).create(original).getFirst();
        assertThat(moved.getId()).isEqualTo(first.getId());
        assertThat(moved.getMetadata()).isEqualTo(first.getMetadata());
    }

    @Test
    void linksSplitSymbolsToDistinctOverloadParentsAndFileSnapshots() {
        String source = "class Endpoint {\nvoid load(int count) {}\nvoid load(String id) {}\n}\n";
        Document integer = factory(1200).create(symbol(source, "void load(int count) {}", "example.Endpoint#load(int)")).getFirst();
        Document string = factory(1200).create(symbol(source, "void load(String id) {}", "example.Endpoint#load(java.lang.String)")).getFirst();
        assertThat(integer.getMetadata().get("parent_id")).isNotEqualTo(string.getMetadata().get("parent_id"));
        assertThat(integer.getMetadata()).containsEntry("start_line", 2).containsEntry("end_line", 2);
        assertThat(string.getMetadata()).containsEntry("start_line", 3).containsEntry("end_line", 3);
        assertThat(snapshots().read((String) integer.getMetadata().get("source_snapshot"))).isEqualTo(source);
        assertThat(snapshots().read((String) integer.getMetadata().get("parent_snapshot"))).isEqualTo("void load(int count) {}");
        SourceUnit method = symbol(source, "void load(int count) {}", "example.Endpoint#load(int)");
        Map<String, Object> renamed = new LinkedHashMap<>(method.metadata());
        renamed.put("signature", "void load(int renamed)");
        Document changedHeader = factory(1200).create(new SourceUnit(source, method.startOffset(), method.endOffset(), "method", renamed)).getFirst();
        assertThat(changedHeader.getId()).isEqualTo(integer.getId());
        assertThat(changedHeader.getMetadata().get("chunk_hash")).isNotEqualTo(integer.getMetadata().get("chunk_hash"));
        SourceUnit large = symbol("void load(int count) {\n" + "count++;\n".repeat(40) + "}",
                "void load(int count) {\n" + "count++;\n".repeat(40) + "}", "example.Endpoint#load(int)");
        List<Document> parts = factory(240).create(large);
        assertThat(parts).hasSizeGreaterThan(1).allSatisfy(part ->
                assertThat(part.getMetadata()).containsEntry("parent_id", integer.getMetadata().get("parent_id")));
    }

    @Test
    void sendsOnlyExplicitContextAndSourceToInstalledOllamaEmbeddingModel() {
        Document document = factory(1200).create(unit("class Endpoint {}")).getFirst();
        for (MetadataMode mode : MetadataMode.values()) {
            assertThat(document.getFormattedContent(mode)).isEqualTo(document.getText());
        }
        OllamaApi api = mock(OllamaApi.class);
        when(api.embed(any(EmbeddingsRequest.class))).thenReturn(new EmbeddingsResponse("bge-m3", List.of(new float[] {1, 2}), 0L, 0L, 1));
        OllamaEmbeddingProperties properties = new OllamaEmbeddingProperties();
        properties.setModel("bge-m3");
        properties.setTruncate(false);
        OllamaEmbeddingModel delegate = OllamaEmbeddingModel.builder().ollamaApi(api)
                .options(OllamaEmbeddingOptions.builder().model("bge-m3").truncate(false).numCtx(2048).build()).build();
        EmbeddingModel model = (EmbeddingModel) OllamaEmbeddingCompatibility.adapt(delegate, properties);
        model.embed(List.of(document), EmbeddingOptions.builder().build(), documents -> List.of(documents));
        var request = org.mockito.ArgumentCaptor.forClass(EmbeddingsRequest.class);
        verify(api).embed(request.capture());
        assertThat(request.getValue().input()).containsExactly(document.getText());
        assertThat(request.getValue().truncate()).isFalse();
        assertThat(request.getValue().model()).isEqualTo("bge-m3");
        assertThat(request.getValue().options()).containsEntry("num_ctx", 2048);
        assertThat(document.getText()).startsWith("Repository: repo\nModule: repo:.\nFile: Endpoint.java\n")
                .doesNotContain("file_hash", "chunk_hash", "parser_version", document.getId());
    }

    @Test
    void excludesBlankSourcesAndRejectsInvalidRangesHeadersAndSnapshotReferences() {
        assertThat(factory(1200).create(unit(" \n"))).isEmpty();
        assertThatThrownBy(() -> new SourceUnit("text", 0, 5, "file_excerpt", unit("text").metadata()))
                .isInstanceOf(IllegalArgumentException.class);
        Map<String, Object> metadata = new LinkedHashMap<>(unit("text").metadata());
        metadata.put("signature", "x".repeat(1200));
        assertThatThrownBy(() -> factory(240).create(new SourceUnit("text", 0, 4, "method", metadata)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("header");
        assertThatThrownBy(() -> snapshots().read("../outside.txt")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void detectsModifiedSnapshots() throws Exception {
        Document document = factory(1200).create(unit("class Endpoint {}")).getFirst();
        String reference = (String) document.getMetadata().get("source_snapshot");
        Files.writeString(temporary.resolve(reference), "tampered");
        assertThatThrownBy(() -> snapshots().excerpt(document.getMetadata())).isInstanceOf(java.io.UncheckedIOException.class)
                .hasMessageContaining("hash mismatch");
    }

    private SourceUnit symbol(String source, String excerpt, String symbolId) {
        Map<String, Object> metadata = new LinkedHashMap<>(unit(source).metadata());
        metadata.put("symbol_id", symbolId);
        metadata.put("signature", symbolId.substring(symbolId.indexOf('#') + 1));
        metadata.put("type_fqn", "example.Endpoint");
        int start = source.indexOf(excerpt);
        return new SourceUnit(source, start, start + excerpt.length(), "method", metadata);
    }

    private SourceUnit unit(String source) {
        return new SourceUnit(source, 0, source.length(), "file_excerpt", Map.of(
                "repository_id", "repo", "module_id", "repo:.", "relative_path", "Endpoint.java",
                "language", "java", "file_kind", "java", "source_set", "main_java", "file_hash", SourceIdentity.hash(source),
                "content_origin", "source", "location_basis", "original_source"));
    }

    private SourceDocumentFactory factory(int budget) {
        ChunkingProperties properties = new ChunkingProperties();
        properties.setMaxCharacters(budget);
        properties.setSnapshotDirectory(temporary);
        return new SourceDocumentFactory(properties);
    }

    private SourceSnapshotStore snapshots() { return new SourceSnapshotStore(temporary); }
}
