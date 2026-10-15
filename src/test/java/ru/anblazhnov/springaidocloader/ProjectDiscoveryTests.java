package ru.anblazhnov.springaidocloader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectDiscoveryTests {
    @TempDir Path temporary;

    @Test
    void discoversMavenReactorAndRoutesContractsByNamespace() throws Exception {
        Path root = temporary.resolve("reactor");
        write(root, "pom.xml", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <groupId>example</groupId><artifactId>parent</artifactId><version>1</version>
                  <packaging>pom</packaging><modules><module>service</module></modules>
                </project>
                """);
        write(root, "service/pom.xml", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <parent><groupId>example</groupId><artifactId>parent</artifactId><version>1</version></parent>
                  <artifactId>soap-service</artifactId>
                  <build><sourceDirectory>java-code</sourceDirectory>
                    <resources><resource><directory>contracts</directory></resource></resources>
                  </build>
                </project>
                """);
        write(root, "service/java-code/example/Endpoint.java", "/* package bogus; */\npackage example; class Endpoint {}");
        write(root, "service/src/test/java/example/EndpointTest.java", "package example; class EndpointTest {}");
        write(root, "service/contracts/service.xml", "<w:definitions xmlns:w='http://schemas.xmlsoap.org/wsdl/'/>");
        write(root, "service/contracts/service.wsdl", "<w:description xmlns:w='http://www.w3.org/ns/wsdl'/>");
        write(root, "service/contracts/schema.xsd", "<x:schema xmlns:x='http://www.w3.org/2001/XMLSchema'/>");
        write(root, "service/contracts/other.xml", "<config xmlns='urn:config'/>");
        write(root, "service/contracts/application.yml", "password: yaml-secret\nservice: customer\n");
        write(root, "service/contracts/application.properties", "password=properties-secret\nservice=customer\n");
        write(root, "README.md", "Project documentation");

        DiscoveryProperties properties = properties(root);
        List<DiscoveredFile> files = discover(properties);
        assertThat(files).hasSize(11);
        DiscoveredFile java = find(files, "Endpoint.java");
        assertThat(java.metadata()).containsEntry("repository_id", "stable-repository")
                .containsEntry("module_id", "stable-repository:service")
                .containsEntry("parent_module_id", "stable-repository:.")
                .containsEntry("artifact_id", "soap-service").containsEntry("group_id", "example")
                .containsEntry("version", "1").containsEntry("package", "example")
                .containsEntry("relative_path", "service/java-code/example/Endpoint.java")
                .containsEntry("source_set", "main_java").containsEntry("reader", "java");
        assertThat(find(files, "EndpointTest.java").metadata()).containsEntry("source_set", "test_java");
        assertThat(find(files, "service.xml").kind()).isEqualTo(DiscoveredFile.FileKind.WSDL);
        assertThat(find(files, "service.xml").metadata()).containsEntry("source_set", "main_resources")
                .containsEntry("namespace_uri", "http://schemas.xmlsoap.org/wsdl/").containsEntry("reader", "wsdl");
        assertThat(find(files, "service.wsdl").kind()).isEqualTo(DiscoveredFile.FileKind.WSDL);
        assertThat(find(files, "schema.xsd").kind()).isEqualTo(DiscoveredFile.FileKind.XSD);
        assertThat(find(files, "other.xml").kind()).isEqualTo(DiscoveredFile.FileKind.XML);
        assertThat(find(files, "application.yml").metadata()).containsEntry("reader", "yaml");
        assertThat(find(files, "application.yml").text()).contains("REDACTED").doesNotContain("yaml-secret");
        assertThat(find(files, "application.properties").metadata()).containsEntry("reader", "properties");
        assertThat(find(files, "application.properties").text()).contains("REDACTED").doesNotContain("properties-secret");
        assertThat(find(files, "README.md").metadata()).containsEntry("module_id", "stable-repository:.");
        String report = Files.readString(properties.getReport());
        assertThat(report).contains("repository_id,module_id,relative_path,source_set,file_kind")
                .contains("service/contracts/service.wsdl", "service/contracts/schema.xsd", "supported_source");
    }

    @Test
    void respectsNestedIgnoreRulesNegationsLocalExcludesAndConfiguredPaths() throws Exception {
        Path root = temporary.resolve("repo");
        write(root, ".gitignore", "*.java\n!Keep.java\nignored/\n");
        write(root, ".git/info/exclude", "local.properties\n");
        write(root, "nested/.gitignore", "!Reincluded.java\n");
        write(root, "Skip.java", "class Skip {}");
        write(root, "Keep.java", "class Keep {}");
        write(root, "nested/Reincluded.java", "class Reincluded {}");
        write(root, "nested/Skip.java", "class Skip {}");
        write(root, "ignored/Keep.java", "class Keep {}");
        write(root, "target/Keep.java", "class Keep {}");
        write(root, "private/config.yaml", "value: safe");
        write(root, "local.properties", "value=safe");
        DiscoveryProperties properties = properties(root);
        properties.setExcludedPaths(List.of("private/**"));

        List<DiscoveredFile> files = discover(properties);
        assertThat(files).extracting(file -> file.metadata().get("relative_path"))
                .containsExactlyInAnyOrder("Keep.java", "nested/Reincluded.java");
        assertThat(Files.readString(properties.getReport())).contains("gitignore", "configured_directory", "configured_path")
                .contains("local.properties").doesNotContain("ignored/Keep.java");
        properties.setRespectGitignore(false);
        assertThat(discover(properties)).extracting(file -> file.metadata().get("relative_path"))
                .contains("Skip.java", "nested/Skip.java", "local.properties", "ignored/Keep.java");
    }

    @Test
    void keepsTrackedFilesEvenWhenTheyMatchAnIgnoreRule() throws Exception {
        Path root = temporary.resolve("tracked-repo");
        Files.createDirectories(root);
        try (var git = org.eclipse.jgit.api.Git.init().setDirectory(root.toFile()).call()) {
            write(root, "ignored/Tracked.java", "class Tracked {}");
            git.add().addFilepattern("ignored/Tracked.java").call();
            write(root, ".gitignore", "ignored/\n");
            write(root, "ignored/Untracked.java", "class Untracked {}");
            write(root, "ignored/.gitignore", "!Untracked.java\n");
        }
        assertThat(discover(properties(root))).extracting(file -> file.metadata().get("relative_path"))
                .containsExactly("ignored/Tracked.java");
    }

    @Test
    void skipsSymbolicLinksToFilesAndDirectories() throws Exception {
        Path root = temporary.resolve("links");
        write(root, "Real.java", "class Real {}");
        try {
            Files.createSymbolicLink(root.resolve("Alias.java"), root.resolve("Real.java"));
            Files.createSymbolicLink(root.resolve("loop"), root);
        }
        catch (java.io.IOException | UnsupportedOperationException error) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symlink creation is unavailable on this host");
        }
        DiscoveryProperties properties = properties(root);
        assertThat(discover(properties)).extracting(file -> file.path().getFileName().toString()).containsExactly("Real.java");
        assertThat(Files.readString(properties.getReport())).contains("Alias.java", "loop", "symbolic_link");
    }

    @Test
    void recordsSizeEncodingXmlAndYamlFailuresAndContinues() throws Exception {
        Path root = temporary.resolve("repo");
        write(root, "Good.java", "class Good {}");
        write(root, "huge.java", "x".repeat(1025));
        write(root, "broken.xml", "<root>");
        write(root, "entity.xml", "<!DOCTYPE root [<!ENTITY secret SYSTEM 'file:///secret'>]><root>&secret;</root>");
        write(root, "broken.yaml", "password: [\n");
        write(root, "recursive.yaml", "thing: &loop [*loop]\n");
        Files.write(root.resolve("bad.java"), new byte[] {(byte) 0xc3, 0x28});
        write(root, "binary.java", "abc\0def");
        DiscoveryProperties properties = properties(root);
        properties.setMaxFileSize(1024);

        assertThat(discover(properties)).extracting(file -> file.path().getFileName().toString()).containsExactly("Good.java");
        String report = Files.readString(properties.getReport());
        assertThat(report).contains("file_too_large", "read_or_parse_failed", "SAXParseException", "MalformedInputException")
                .contains("recursive.yaml", "binary.java", "broken.yaml").doesNotContain("password: [", "file:///secret");
    }

    @Test
    void detectsBomAndXmlDeclaredEncoding() throws Exception {
        Path root = temporary.resolve("repo");
        write(root, "placeholder.md", "text");
        Files.write(root.resolve("Utf16.java"), "package example; // Привет".getBytes(StandardCharsets.UTF_16));
        Files.write(root.resolve("legacy.xml"), "<?xml version='1.0' encoding='ISO-8859-1'?><root>café</root>"
                .getBytes(StandardCharsets.ISO_8859_1));
        List<DiscoveredFile> files = discover(properties(root));
        assertThat(find(files, "Utf16.java").text()).contains("Привет");
        assertThat(find(files, "Utf16.java").metadata()).containsEntry("encoding", "UTF-16BE");
        assertThat(find(files, "legacy.xml").text()).contains("café");
        assertThat(find(files, "legacy.xml").metadata()).containsEntry("encoding", "ISO-8859-1");
    }

    @Test
    void identifiesGeneratedStubsAndCanExcludeThem() throws Exception {
        Path root = temporary.resolve("repo");
        write(root, "src/main/java/Endpoint.java", "class Endpoint {}");
        write(root, "src/main/java/Stub.java", "@javax.annotation.Generated(\"wsimport\") class Stub {}");
        write(root, "generated-sources/Other.java", "class Other {}");
        DiscoveryProperties properties = properties(root);
        List<DiscoveredFile> included = discover(properties);
        assertThat(find(included, "Stub.java").metadata()).containsEntry("generated", true);
        assertThat(find(included, "Other.java").metadata()).containsEntry("generated", true);
        properties.setExcludeGenerated(true);
        assertThat(discover(properties)).extracting(file -> file.path().getFileName().toString()).containsExactly("Endpoint.java");
        assertThat(Files.readString(properties.getReport())).contains("generated_source");
    }

    @Test
    void keepsExplicitIdentityAcrossCheckoutPathsAndRejectsOverlappingRoots() throws Exception {
        Path first = temporary.resolve("first");
        Path second = temporary.resolve("second");
        write(first, "src/main/java/Example.java", "class Example {}");
        write(second, "src/main/java/Example.java", "class Example {}");
        assertThat(discover(properties(first)).getFirst().metadata()).isEqualTo(discover(properties(second)).getFirst().metadata());
        DiscoveryProperties properties = properties(first);
        properties.setRepositories(List.of(new DiscoveryProperties.Repository("one", first),
                new DiscoveryProperties.Repository("two", first.resolve("src"))));
        assertThatThrownBy(() -> new ProjectDiscovery(properties)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overlap");
    }

    @Test
    void supportsLegacyFolderOfProjects() throws Exception {
        Path folder = temporary.resolve("projects");
        write(folder, "one/pom.xml", "<project><artifactId>one</artifactId></project>");
        write(folder, "two/src/main/java/Other.java", "class Other {}");
        DiscoveryProperties properties = properties(folder);
        properties.setRepositories(List.of());
        properties.setDirectory(folder);
        assertThat(discover(properties)).extracting(file -> file.metadata().get("repository_id"))
                .containsExactlyInAnyOrder("one", "two");
    }

    @Test
    void closesTheReportOnReactiveCancellation() throws Exception {
        Path root = temporary.resolve("repo");
        write(root, "One.java", "class One {}");
        write(root, "Two.java", "class Two {}");
        DiscoveryProperties properties = properties(root);
        new FileSourceConfiguration().fileTreeSupplier(properties).get().take(1).blockLast();
        // A move fails on Windows if the report writer is still open.
        Files.move(properties.getReport(), temporary.resolve("closed.csv"));
    }

    private DiscoveryProperties properties(Path root) {
        DiscoveryProperties properties = new DiscoveryProperties();
        properties.setRepositories(List.of(new DiscoveryProperties.Repository("stable-repository", root)));
        properties.setReport(temporary.resolve("report.csv"));
        return properties;
    }

    private static List<DiscoveredFile> discover(DiscoveryProperties properties) throws Exception {
        List<DiscoveredFile> files = new ArrayList<>();
        try (ProjectDiscovery discovery = new ProjectDiscovery(properties)) {
            DiscoveredFile file;
            while ((file = discovery.next()) != null) files.add(file);
        }
        return files;
    }

    private static DiscoveredFile find(List<DiscoveredFile> files, String name) {
        return files.stream().filter(file -> file.path().getFileName().toString().equals(name)).findFirst().orElseThrow();
    }

    private static Path write(Path root, String relative, String text) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, text, StandardCharsets.UTF_8);
    }
}
