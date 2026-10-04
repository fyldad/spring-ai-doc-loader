package ru.anblazhnov.springaidocloader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import static org.assertj.core.api.Assertions.assertThat;

class JavaSourceReaderTests {
    @TempDir Path temporary;

    @Test
    void extractsTypesOverloadsConstructorsFieldsAndOriginalJavaDoc() {
        String source = """
                package example;
                import java.util.List;
                /** Endpoint docs. */
                @Deprecated
                public class Endpoint extends Object implements Runnable {
                    /** JAXB name. */
                    @XmlElement(name = "customer") @NotNull private String customer;
                    @XmlElement(name = "age") private int age;
                    /** Constructor docs. */
                    public Endpoint(String customer) { this.customer = customer; }
                    /** Load integer. */
                    @Deprecated
                    public void load(int count) { age += count; }
                    /** Load string. */
                    public void load(String id) { customer = id; }
                    public void load(List<String> values) { customer = values.getFirst(); }
                    public void run() {}
                    public interface Nested { String value(); }
                }
                """;
        var units = reader().read(file(source));
        assertThat(units).allSatisfy(unit -> assertThat(unit.metadata()).containsEntry("parse_status", "parsed").containsEntry("package", "example"));
        SourceUnit overview = find(units, "example.Endpoint");
        assertThat(overview.text()).startsWith("/** Endpoint docs. */").endsWith("{");
        assertThat(overview.metadata()).containsEntry("visibility", "public")
                .containsEntry("inheritance", List.of("extends Object", "implements Runnable"));
        assertThat((List<?>) overview.metadata().get("member_signatures")).anySatisfy(value -> assertThat(value.toString()).contains("load(int count)"));
        SourceUnit fields = units.stream().filter(unit -> unit.chunkKind().equals("java_fields")).findFirst().orElseThrow();
        assertThat(fields.text()).contains("/** JAXB name. */", "@NotNull", "@XmlElement(name = \"age\")");
        assertThat(fields.text()).doesNotContain("Constructor docs");
        assertThat(find(units, "example.Endpoint#<init>(java.lang.String)").text()).startsWith("/** Constructor docs. */");
        SourceUnit integer = find(units, "example.Endpoint#load(int)");
        SourceUnit string = find(units, "example.Endpoint#load(java.lang.String)");
        assertThat(integer.text()).isEqualTo("/** Load integer. */\n    @Deprecated\n    public void load(int count) { age += count; }");
        assertThat(integer.parentId()).isNotEqualTo(string.parentId());
        assertThat(find(units, "example.Endpoint#load(java.util.List)").text()).contains("List<String>");
        assertThat(find(units, "example.Endpoint.Nested#value()").text()).contains("String value();");
        List<Document> documents = documents(units);
        verifyLocations(source, documents);
        var methodDocuments = documents.stream().filter(document -> integer.metadata().get("symbol_id").equals(document.getMetadata().get("symbol_id"))).toList();
        assertThat(methodDocuments).hasSize(1);
        assertThat(methodDocuments.getFirst().getMetadata()).containsEntry("start_line", 11).containsEntry("end_line", 13);
        Document typeDocument = documents.stream().filter(document -> "example.Endpoint".equals(document.getMetadata().get("symbol_id"))).findFirst().orElseThrow();
        String context = snapshots().read((String) typeDocument.getMetadata().get("java_context_snapshot"));
        assertThat(context).contains("import java.util.List;", "String customer", "void load(int count)", "interface Nested");
        assertThat(typeDocument.getMetadata()).doesNotContainKeys("imports", "member_signatures");
    }

    @Test
    void supportsRecordsCompactConstructorsEnumsSealedTypesAndJava25Syntax() {
        String source = """
                package example;
                sealed interface Shape permits Circle {}
                record Circle(@NotNull double radius) implements Shape {
                    /** Validate radius. */
                    public Circle { if (radius < 0) throw new IllegalArgumentException(); }
                }
                enum State { @Deprecated OLD, NEW; public boolean active() { return this == NEW; } }
                class Preview {
                    void start() { var result = switch (new Circle(1)) { case Circle(double radius) -> radius; }; }
                    Preview(int value) { if (value < 0) throw new IllegalArgumentException(); super(); }
                }
                """;
        var units = reader().read(file(source));
        assertThat(units).allSatisfy(unit -> assertThat(unit.metadata()).containsEntry("parse_status", "parsed"));
        assertThat(find(units, "example.Circle").text()).contains("@NotNull double radius");
        assertThat(find(units, "example.Circle#<init>(double)").text()).startsWith("/** Validate radius. */");
        assertThat(find(units, "example.State#enum_constants").text()).isEqualTo("@Deprecated OLD, NEW");
        assertThat(find(units, "example.Shape").text()).contains("permits Circle");
        verifyLocations(source, documents(units));
    }

    @Test
    void handlesTabsCrLfBareCrUnicodeTextBlocksAndVarargsWithExactRanges() {
        String source = "package example;\r\nclass Endpoint {\r\n\t/** Привет 😀 */\r\n\t@Deprecated\r\n\tString load(String... ids) {\r\n\t\treturn \"\"\"\r\n\t\t\t😀 Привет\r\n\t\t\t\"\"\";\r\n\t}\r\n}\r\n";
        var units = reader().read(file(source));
        SourceUnit method = find(units, "example.Endpoint#load(java.lang.String[])");
        assertThat(method.text()).startsWith("/** Привет 😀 */").endsWith("\t}");
        verifyLocations(source, documents(units));
        var bareCr = reader().read(file(source.replace("\r\n", "\r")));
        assertThat(bareCr).allSatisfy(unit -> assertThat(unit.metadata()).containsEntry("parse_status", "parsed"));
        verifyLocations(source.replace("\r\n", "\r"), documents(bareCr));
    }

    @Test
    void splitsLargeMethodsAtStatementsAndKeepsParentAndEverySourceCharacter() {
        String source = "package example; class Endpoint {\n/** Long method. */\nvoid load() {\n"
                + "    consume(\"Привет 😀\");\n".repeat(90) + "}\n}\n";
        SourceUnit method = find(reader().read(file(source)), "example.Endpoint#load()");
        var documents = factory(600).create(method);
        assertThat(documents).hasSizeGreaterThan(3);
        StringBuilder reconstructed = new StringBuilder();
        for (Document document : documents) {
            var metadata = document.getMetadata();
            assertThat(metadata).containsEntry("parent_id", method.parentId()).containsEntry("split_basis", "semantic");
            assertThat((int) metadata.get("token_estimate")).isLessThanOrEqualTo(600);
            assertThat(ConservativeTokenBudget.count(document.getText())).isLessThanOrEqualTo(600);
            assertThat(document.getText()).contains("Signature: void load()");
            reconstructed.append(snapshots().excerpt(metadata));
        }
        assertThat(reconstructed.toString()).isEqualTo(method.text());
        verifyLocations(source, documents);
    }

    @Test
    void boundsHugeStatementsAnnotationsAndLongSignaturesWithoutDroppingSource() {
        String source = "class Endpoint {\n@Name(\"" + "😀".repeat(300) + "\")\nvoid load(String " + "identifier".repeat(60)
                + ") { consume(\"" + "😀Привет".repeat(300) + "\"); }\n}";
        SourceUnit method = find(reader().read(file(source)), "Endpoint#load(java.lang.String)");
        List<Document> documents = factory(800).create(method);
        StringBuilder reconstructed = new StringBuilder();
        assertThat(documents).hasSizeGreaterThan(1);
        for (Document document : documents) {
            String excerpt = snapshots().excerpt(document.getMetadata());
            assertThat(Character.isLowSurrogate(excerpt.charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(excerpt.charAt(excerpt.length() - 1))).isFalse();
            assertThat(ConservativeTokenBudget.count(document.getText())).isLessThanOrEqualTo(800);
            reconstructed.append(excerpt);
        }
        assertThat(reconstructed.toString()).isEqualTo(method.text());
    }

    @Test
    void retainsMalformedAndUnsupportedJavaAsSearchableFallback() {
        for (String source : List.of("class Broken { void load( {\n// searchable clue\n}", "class Broken { String x = STR.\"unsupported\\{name}\"; }")) {
            var units = reader().read(file(source));
            assertThat(units).hasSize(1);
            assertThat(units.getFirst().metadata()).containsEntry("parse_status", "fallback").containsKey("parse_diagnostic");
            assertThat(units.getFirst().text()).isEqualTo(source);
            verifyLocations(source, documents(units));
        }
    }

    @Test
    void identitiesSurviveParameterRenamesAndResolutionConfiguration() {
        String source = "package example; import java.util.List; class Endpoint { void load(List<String> values) {} }";
        SourceUnit method = find(reader().read(file(source)), "example.Endpoint#load(java.util.List)");
        SourceUnit renamed = find(reader().read(file(source.replace("values", "items"))), "example.Endpoint#load(java.util.List)");
        assertThat(renamed.parentId()).isEqualTo(method.parentId());
        assertThat(factory(800).create(renamed).getFirst().getId()).isEqualTo(factory(800).create(method).getFirst().getId());
        JavaParsingProperties properties = new JavaParsingProperties();
        properties.setResolveSymbols(true);
        SourceUnit resolved = find(new JavaSourceReader(properties, chunks(800)).read(file(source)), "example.Endpoint#load(java.util.List)");
        assertThat(resolved.parentId()).isEqualTo(method.parentId());
        assertThat(resolved.metadata()).containsEntry("resolution_status", "resolved");
    }

    @Test
    void resolvesSourceRootAndLocalJarWithoutBuildingAndDegradesWhenDependenciesAreMissing() throws Exception {
        Path root = temporary.resolve("sources");
        Files.createDirectories(root.resolve("external"));
        Files.writeString(root.resolve("external/Request.java"), "package external; public class Request {}");
        Path jar = temporary.resolve("dependency.jar");
        Path compiled = temporary.resolve("compiled");
        Files.createDirectories(compiled);
        assertThat(javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", compiled.toString(), root.resolve("external/Request.java").toString())).isZero();
        try (var output = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new java.util.jar.JarEntry("external/Request.class"));
            output.write(Files.readAllBytes(compiled.resolve("external/Request.class")));
            output.closeEntry();
        }
        for (boolean useJar : List.of(false, true)) {
            JavaParsingProperties properties = new JavaParsingProperties();
            properties.setResolveSymbols(true);
            if (useJar) properties.setDependencyJars(List.of(jar));
            else properties.setSourceRoots(List.of(root));
            var units = new JavaSourceReader(properties, chunks(800)).read(file("import external.Request; class Endpoint { void load(Request request) {} void missing(Unknown value) {} }"));
            SourceUnit resolved = find(units, "Endpoint#load(external.Request)");
            assertThat(resolved.metadata()).containsEntry("resolution_status", "resolved").containsEntry("resolved_types", List.of("external.Request", "void"));
            assertThat(find(units, "Endpoint#missing(Unknown)").metadata()).containsEntry("resolution_status", "partial").containsEntry("parse_status", "parsed");
        }
        JavaParsingProperties properties = new JavaParsingProperties();
        properties.setResolveSymbols(true);
        properties.setDependencyJars(List.of(temporary.resolve("absent.jar")));
        assertThat(new JavaSourceReader(properties, chunks(800)).read(file("class Endpoint { void load() {} }")))
                .allSatisfy(unit -> assertThat(unit.metadata()).containsEntry("parse_status", "parsed").containsKey("resolution_diagnostic"));
    }

    @Test
    void countsNormalizationExpansionConservatively() {
        assertThat(ConservativeTokenBudget.count("ﷺ"))
                .isGreaterThanOrEqualTo(java.text.Normalizer.normalize("ﷺ", java.text.Normalizer.Form.NFKC).getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    @Test
    void distinguishesInitializersAndNestedParameterTypesAndAvoidsLocalTypeCollisions() {
        String source = """
                package example;
                class Endpoint<T> {
                    static { first(); }
                    static { second(); }
                    { first(); }
                    interface Nested {}
                    void load(Nested value) {}
                    void load(T value) {}
                    void first() { class Local { class Inner {} } }
                    void second() { class Local { class Inner {} } }
                }
                """;
        var units = reader().read(file(source));
        assertThat(units).allSatisfy(unit -> assertThat(unit.metadata()).containsEntry("parse_status", "parsed"));
        assertThat(find(units, "example.Endpoint#load(example.Endpoint.Nested)").text()).contains("Nested value");
        assertThat(find(units, "example.Endpoint#load(T)").text()).contains("T value");
        assertThat(find(units, "example.Endpoint#first()").text()).contains("class Inner");
        assertThat(units.stream().filter(unit -> unit.chunkKind().equals("java_type_overview"))).hasSize(2);
        List<Document> documents = documents(units);
        assertThat(documents.stream().map(Document::getId)).doesNotHaveDuplicates();
        assertThat(documents).filteredOn(document -> document.getMetadata().get("chunk_kind").equals("java_member")).hasSize(3);
    }

    private JavaSourceReader reader() { return new JavaSourceReader(new JavaParsingProperties(), chunks(800)); }
    private ChunkingProperties chunks(int tokens) {
        ChunkingProperties properties = new ChunkingProperties();
        properties.setJavaMaxTokens(tokens);
        properties.setSnapshotDirectory(temporary.resolve("snapshots"));
        return properties;
    }
    private SourceDocumentFactory factory(int tokens) { return new SourceDocumentFactory(chunks(tokens)); }
    private SourceSnapshotStore snapshots() { return new SourceSnapshotStore(temporary.resolve("snapshots")); }
    private List<Document> documents(List<SourceUnit> units) { return units.stream().flatMap(unit -> factory(800).create(unit).stream()).toList(); }
    private SourceUnit find(List<SourceUnit> units, String symbol) {
        return units.stream().filter(unit -> symbol.equals(unit.metadata().get("symbol_id"))).findFirst().orElseThrow(() -> new AssertionError("Missing " + symbol + " in " + units));
    }
    private DiscoveredFile file(String source) {
        return new DiscoveredFile(temporary.resolve("Endpoint.java"), DiscoveredFile.FileKind.JAVA, source, Map.of(
                "repository_id", "repo", "module_id", "repo:.", "relative_path", "Endpoint.java", "language", "java",
                "file_kind", "java", "source_set", "main_java", "file_hash", SourceIdentity.hash(source), "content_origin", "source", "location_basis", "original_source"));
    }
    private void verifyLocations(String source, List<Document> documents) {
        for (Document document : documents) {
            Map<String, Object> metadata = document.getMetadata();
            int start = (int) metadata.get("start_offset");
            int end = (int) metadata.get("end_offset");
            assertThat(snapshots().excerpt(metadata)).isEqualTo(source.substring(start, end));
            assertThat((int) metadata.get("start_line")).isEqualTo(lineAt(source, start));
            assertThat((int) metadata.get("end_line")).isEqualTo(lineAt(source, end - 1));
            assertThat(document.getText()).endsWith(source.substring(start, end));
        }
    }
    private int lineAt(String source, int offset) {
        var matcher = java.util.regex.Pattern.compile("\\r\\n|\\r|\\n").matcher(source);
        int line = 1;
        while (matcher.find() && matcher.end() <= offset) line++;
        return line;
    }
}
