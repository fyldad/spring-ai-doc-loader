package ru.anblazhnov.springaidocloader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class SourceProvenanceTests {
    @TempDir Path temporary;

    @Test
    void recordsGitRevisionDirtyStateAndRawByteHashIncludingBom() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("repository"));
        Path path = root.resolve("Example.java");
        byte[] bytes = "class Example {}".getBytes(StandardCharsets.UTF_16);
        Files.write(path, bytes);
        DiscoveryProperties properties = properties(root);
        try (Git git = Git.init().setDirectory(root.toFile()).call()) {
            git.add().addFilepattern("Example.java").call();
            String head = git.commit().setMessage("Initial source").setAuthor("Fixture", "fixture@example.org")
                    .setCommitter("Fixture", "fixture@example.org").call().name();
            DiscoveredFile clean = discover(properties, temporary.resolve("snapshots")).getFirst();
            assertThat(clean.metadata()).containsEntry("revision", head).containsEntry("dirty_worktree", false)
                    .containsEntry("revision_status", "available").containsEntry("file_hash", SourceIdentity.hash(bytes));
            assertThat(clean.text()).isEqualTo("class Example {}");
            Files.writeString(path, "class Example { int changed; }");
            DiscoveredFile dirty = discover(properties, temporary.resolve("snapshots")).getFirst();
            assertThat(dirty.metadata()).containsEntry("revision", head).containsEntry("dirty_worktree", true);
            assertThat(dirty.metadata().get("file_hash")).isNotEqualTo(clean.metadata().get("file_hash"));
        }
    }

    @Test
    void persistsSanitizedConfigurationWithHonestLocationsAndSkipsSnapshotSubtrees() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("repository"));
        Files.writeString(root.resolve("application.yaml"), "# original comment\npassword: never-store-this\nservice: customer\n");
        Path snapshotDirectory = root.resolve("retained-sources");
        ChunkingProperties chunks = new ChunkingProperties();
        chunks.setSnapshotDirectory(snapshotDirectory);
        DiscoveryProperties properties = properties(root);
        DiscoveredFile file = discover(properties, snapshotDirectory).getFirst();
        var document = new SourceDocumentFactory(chunks).create(new ProjectDocumentReaders(properties).read(file)).getFirst();
        assertThat(document.getMetadata()).containsEntry("content_origin", "sanitized_source")
                .containsEntry("location_basis", "sanitized_snapshot").containsEntry("start_line", 1)
                .containsEntry("revision_status", "unversioned").doesNotContainKeys("revision", "dirty_worktree");
        String sanitized = new SourceSnapshotStore(snapshotDirectory).excerpt(document.getMetadata());
        assertThat(sanitized).contains("REDACTED").doesNotContain("never-store-this", "original comment");
        try (var snapshots = Files.walk(snapshotDirectory)) {
            for (Path path : snapshots.filter(Files::isRegularFile).toList()) {
                assertThat(Files.readString(path)).doesNotContain("never-store-this");
            }
        }
        assertThat(discover(properties, snapshotDirectory)).hasSize(1);
        assertThat(Files.readString(properties.getReport())).contains("source_snapshots");
    }

    private DiscoveryProperties properties(Path root) {
        DiscoveryProperties properties = new DiscoveryProperties();
        properties.setRepositories(List.of(new DiscoveryProperties.Repository("fixture", root)));
        properties.setReport(temporary.resolve("report.csv"));
        return properties;
    }

    private List<DiscoveredFile> discover(DiscoveryProperties properties, Path snapshots) throws Exception {
        List<DiscoveredFile> files = new ArrayList<>();
        try (var discovery = new ProjectDiscovery(properties, snapshots)) {
            DiscoveredFile file;
            while ((file = discovery.next()) != null) files.add(file);
        }
        return files;
    }
}
