package ru.anblazhnov.springaidocloader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class FileTreeWalkerTests {

    @Test
    void walksMatchingFilesAndPrunesExcludedDirectories(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("project/src"));
        Path generated = Files.createDirectories(root.resolve("project/build/generated"));
        Path javaFile = Files.writeString(source.resolve("Example.java"), "class Example {}");
        Path markdownFile = Files.writeString(root.resolve("README.md"), "docs");
        Files.writeString(source.resolve("notes.txt"), "ignored");
        Files.writeString(generated.resolve("Generated.java"), "class Generated {}");

        Set<Path> files = new HashSet<>();
        try (FileTreeWalker walker = new FileTreeWalker(
                root, Pattern.compile(".*\\.(java|md)"), Set.of("build"))) {
            Path file;
            while ((file = walker.next()) != null) {
                files.add(file);
            }
        }

        assertThat(files).containsExactlyInAnyOrder(javaFile, markdownFile);
    }
}
