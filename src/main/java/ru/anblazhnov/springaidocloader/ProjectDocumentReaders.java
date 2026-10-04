package ru.anblazhnov.springaidocloader;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.ai.document.Document;

final class ProjectDocumentReaders {
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\p{javaJavaIdentifierPart}.]+)\\s*;");
    private final ConfigurationRedactor redactor;

    ProjectDocumentReaders(DiscoveryProperties properties) {
        redactor = new ConfigurationRedactor(properties.getSensitiveKeyPattern());
    }

    // Each route currently retains full-file text. AST/semantic extraction belongs to phases 4/5.
    DiscoveredFile prepare(DiscoveredFile file) throws Exception {
        Map<String, Object> metadata = new LinkedHashMap<>(file.metadata());
        String text = switch (file.kind()) {
            case JAVA -> javaSource(file, metadata);
            case POM -> pom(file, metadata);
            case WSDL -> contract(file, metadata, "wsdl");
            case XSD -> contract(file, metadata, "xsd");
            case XML -> xml(file, metadata);
            case YAML -> configuration(file, metadata, true);
            case PROPERTIES -> configuration(file, metadata, false);
            case PROSE -> prose(file, metadata);
            case OTHER -> throw new IllegalArgumentException("Unsupported file kind");
        };
        return new DiscoveredFile(file.path(), file.kind(), text, Map.copyOf(metadata));
    }

    Document read(DiscoveredFile file) {
        return new Document(file.text(), file.metadata());
    }

    private String javaSource(DiscoveredFile file, Map<String, Object> metadata) {
        metadata.put("reader", "java");
        String withoutComments = file.text().replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//[^\\r\\n]*", "");
        var match = PACKAGE.matcher(withoutComments);
        if (match.find()) metadata.put("package", match.group(1));
        return file.text();
    }

    private String pom(DiscoveredFile file, Map<String, Object> metadata) throws Exception {
        metadata.put("reader", "pom");
        metadata.put("configuration_redacted", true);
        return redactor.xml(file.text());
    }

    private String contract(DiscoveredFile file, Map<String, Object> metadata, String reader) {
        metadata.put("reader", reader);
        return file.text();
    }

    private String xml(DiscoveredFile file, Map<String, Object> metadata) throws Exception {
        metadata.put("reader", "xml");
        metadata.put("configuration_redacted", true);
        return redactor.xml(file.text());
    }

    private String configuration(DiscoveredFile file, Map<String, Object> metadata, boolean yaml) throws Exception {
        metadata.put("reader", yaml ? "yaml" : "properties");
        metadata.put("configuration_redacted", true);
        return yaml ? redactor.yaml(file.text()) : redactor.properties(file.text());
    }

    private String prose(DiscoveredFile file, Map<String, Object> metadata) {
        metadata.put("reader", "prose");
        return file.text();
    }
}
