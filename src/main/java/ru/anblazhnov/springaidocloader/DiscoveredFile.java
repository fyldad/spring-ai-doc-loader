package ru.anblazhnov.springaidocloader;

import java.nio.file.Path;
import java.util.Map;

record DiscoveredFile(Path path, FileKind kind, String text, Map<String, Object> metadata) {
    enum FileKind {
        JAVA("java"), POM("xml"), WSDL("xml"), XSD("xml"), XML("xml"),
        YAML("yaml"), PROPERTIES("properties"), PROSE("text"), OTHER("unknown");

        final String language;

        FileKind(String language) { this.language = language; }

        static FileKind fromFilename(Path path) {
            String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            if (name.equals("pom.xml")) return POM;
            if (name.endsWith(".java")) return JAVA;
            if (name.endsWith(".wsdl")) return WSDL;
            if (name.endsWith(".xsd")) return XSD;
            if (name.endsWith(".xml")) return XML;
            if (name.endsWith(".yaml") || name.endsWith(".yml")) return YAML;
            if (name.endsWith(".properties")) return PROPERTIES;
            if (name.endsWith(".md") || name.endsWith(".txt")) return PROSE;
            return OTHER;
        }
    }
}
