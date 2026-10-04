package ru.anblazhnov.springaidocloader;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Element;

final class MavenInventory {
    private final DiscoveryProperties.Repository repository;
    private final DiscoveryProperties properties;
    private final RepositoryRules rules;
    private final Map<Path, Module> modules = new HashMap<>();

    MavenInventory(DiscoveryProperties.Repository repository, DiscoveryProperties properties, RepositoryRules rules) {
        this.repository = repository;
        this.properties = properties;
        this.rules = rules;
    }

    Map<String, Object> metadata(Path path) {
        Module module = closest(path);
        String relativeModule = RepositoryRules.portable(repository.root().relativize(module.root()));
        if (relativeModule.isEmpty()) relativeModule = ".";
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("repository_id", repository.id());
        metadata.put("relative_path", RepositoryRules.portable(repository.root().relativize(path)));
        metadata.put("module_id", repository.id() + ":" + relativeModule);
        metadata.put("module", relativeModule);
        metadata.put("module_path", relativeModule);
        metadata.put("source_set", module.sourceSet(path));
        metadata.put("build_system", module.status().equals("none") ? "unknown" : "maven");
        metadata.put("inventory_status", module.status());
        metadata.putAll(module.coordinates());
        // Locate the containing Maven module independently of <parent>, which may be external.
        Path parent = module.root().getParent();
        while (parent != null && parent.startsWith(repository.root())) {
            if (hasPom(parent)) {
                String parentPath = RepositoryRules.portable(repository.root().relativize(parent));
                metadata.put("parent_module_id", repository.id() + ":" + (parentPath.isEmpty() ? "." : parentPath));
                break;
            }
            parent = parent.getParent();
        }
        return metadata;
    }

    private Module closest(Path path) {
        Path directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? path : path.getParent();
        while (directory != null && directory.startsWith(repository.root())) {
            if (hasPom(directory)) return modules.computeIfAbsent(directory, this::readModule);
            if (directory.equals(repository.root())) break;
            directory = directory.getParent();
        }
        return modules.computeIfAbsent(repository.root(), root -> Module.defaults(root, "none"));
    }

    private boolean hasPom(Path directory) {
        Path pom = directory.resolve("pom.xml");
        if (!Files.isRegularFile(pom, LinkOption.NOFOLLOW_LINKS)) return false;
        try { return rules.exclusion(pom, false) == null; }
        catch (Exception error) { return false; }
    }

    private Module readModule(Path directory) {
        try {
            Path path = directory.resolve("pom.xml");
            Element project = XmlSupport.parse(SourceText.read(path, properties).text()).getDocumentElement();
            if (XmlSupport.classify(project, path) != DiscoveredFile.FileKind.POM) return Module.defaults(directory, "invalid_pom");
            Map<String, Object> coordinates = new LinkedHashMap<>();
            Element parent = XmlSupport.child(project, "parent");
            for (String name : List.of("groupId", "artifactId", "version", "packaging")) {
                String value = XmlSupport.value(project, name);
                if (value.isEmpty() && (name.equals("groupId") || name.equals("version"))) value = XmlSupport.value(parent, name);
                if (name.equals("packaging") && value.isEmpty()) value = "jar";
                if (!value.isEmpty()) coordinates.put(snakeCase(name), value);
            }
            if (parent != null) {
                coordinates.put("maven_parent", XmlSupport.value(parent, "groupId") + ":"
                        + XmlSupport.value(parent, "artifactId") + ":" + XmlSupport.value(parent, "version"));
            }
            List<String> children = values(XmlSupport.child(project, "modules"), "module");
            if (!children.isEmpty()) coordinates.put("declared_modules", children);
            Element build = XmlSupport.child(project, "build");
            return new Module(directory, coordinates,
                    roots(directory, XmlSupport.value(build, "sourceDirectory"), "src/main/java"),
                    roots(directory, XmlSupport.value(build, "testSourceDirectory"), "src/test/java"),
                    resourceRoots(directory, XmlSupport.child(build, "resources"), "resource", "src/main/resources"),
                    resourceRoots(directory, XmlSupport.child(build, "testResources"), "testResource", "src/test/resources"),
                    "declared");
        }
        catch (Exception error) {
            // The discovery reader records the POM's read/parse failure; do not invent coordinates.
            return Module.defaults(directory, "unresolved_pom");
        }
    }

    private static String snakeCase(String name) {
        return name.replace("Id", "_id");
    }

    private static List<String> values(Element parent, String name) {
        List<String> values = new ArrayList<>();
        if (parent != null) {
            for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element element && name.equals(element.getLocalName())) values.add(element.getTextContent().trim());
            }
        }
        return values;
    }

    private static List<Path> roots(Path root, String value, String fallback) {
        if (value.isEmpty()) value = fallback;
        value = value.replace("${project.basedir}", root.toString()).replace("${basedir}", root.toString());
        if (value.contains("${")) return List.of(); // No effective-model/profile resolution in discovery.
        Path path = root.resolve(value).normalize();
        return path.startsWith(root) ? List.of(path) : List.of();
    }

    private static List<Path> resourceRoots(Path root, Element resources, String tag, String fallback) {
        if (resources == null) return roots(root, "", fallback);
        List<Path> paths = new ArrayList<>();
        for (var node = resources.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && tag.equals(element.getLocalName())) {
                paths.addAll(roots(root, XmlSupport.value(element, "directory"), fallback));
            }
        }
        return paths;
    }

    private record Module(Path root, Map<String, Object> coordinates, List<Path> sources, List<Path> tests,
            List<Path> resources, List<Path> testResources, String status) {
        static Module defaults(Path root, String status) {
            return new Module(root, Map.of(), List.of(root.resolve("src/main/java")), List.of(root.resolve("src/test/java")),
                    List.of(root.resolve("src/main/resources")), List.of(root.resolve("src/test/resources")), status);
        }

        String sourceSet(Path path) {
            if (tests.stream().anyMatch(path::startsWith)) return "test_java";
            if (sources.stream().anyMatch(path::startsWith)) return "main_java";
            if (testResources.stream().anyMatch(path::startsWith)) return "test_resources";
            if (resources.stream().anyMatch(path::startsWith)) return "main_resources";
            return "other";
        }
    }
}
