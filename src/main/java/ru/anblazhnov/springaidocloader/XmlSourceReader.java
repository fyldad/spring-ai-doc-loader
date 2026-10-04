package ru.anblazhnov.springaidocloader;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.w3c.dom.Element;

/** All resolution uses the admitted discovery inventory: no parser/network/file-system fetching. */
final class XmlSourceReader {
    static final String VERSION = "xml-semantic-v1";
    static final String WSDL = "http://schemas.xmlsoap.org/wsdl/";
    static final String XSD = "http://www.w3.org/2001/XMLSchema";
    private final XmlParsingProperties properties;
    private final Map<Path, Parsed> inventory = new LinkedHashMap<>();
    private final Map<Path, String> failures = new HashMap<>();
    private final Map<Path, Scope> scopes = new HashMap<>();

    private record Parsed(DiscoveredFile file, LocatedXml xml) { }
    private record Declaration(Parsed parsed, Element element, String namespace, String kind) {
        String qname() { return LocatedXml.qname(namespace, element.getAttribute("name")); }
    }
    private record Scope(List<Parsed> files, List<String> diagnostics) { }

    XmlSourceReader(XmlParsingProperties properties, List<DiscoveredFile> files) {
        properties.validate(); this.properties = properties;
        for (DiscoveredFile file : files) if (SourceText.isXml(file.kind())) {
            Path key = file.path().toAbsolutePath().normalize();
            try { inventory.put(key, new Parsed(file, new LocatedXml(file.text()))); }
            catch (Exception error) { failures.put(key, error.getClass().getSimpleName()); }
        }
    }

    List<SourceUnit> read(DiscoveredFile file) {
        if (!SourceText.isXml(file.kind())) return List.of(new SourceUnit(file.text(), 0, file.text().length(), "file_excerpt", file.metadata()));
        Parsed parsed = inventory.get(file.path().toAbsolutePath().normalize());
        if (parsed == null) return fallback(file, "xml_fallback", "fallback",
                failures.getOrDefault(file.path().toAbsolutePath().normalize(), "Unavailable source"));
        try {
            return switch (file.kind()) {
                case WSDL -> wsdl(parsed);
                case XSD -> schema(parsed);
                case POM -> maven(parsed);
                case XML -> other(parsed);
                default -> throw new IllegalArgumentException("Not XML");
            };
        }
        catch (RuntimeException error) { return fallback(file, "xml_fallback", "fallback", error.getClass().getSimpleName()); }
    }

    private List<SourceUnit> fallback(DiscoveredFile file, String kind, String status, String diagnostic) {
        Map<String, Object> metadata = new LinkedHashMap<>(file.metadata());
        metadata.put("parser_version", VERSION); metadata.put("parse_status", status);
        metadata.put("parse_diagnostic", diagnostic); metadata.put("resolution_status", "not_attempted");
        return List.of(new SourceUnit(file.text(), 0, file.text().length(), kind, metadata));
    }

    private Scope scope(Parsed source) {
        Path key = source.file().path().toAbsolutePath().normalize();
        return scopes.computeIfAbsent(key, ignored -> {
            record Pending(Parsed file, int depth) { }
            List<Parsed> files = new ArrayList<>(); List<String> diagnostics = new ArrayList<>();
            Set<Path> visited = new HashSet<>(); var pending = new ArrayDeque<Pending>();
            pending.add(new Pending(source, 0)); visited.add(key);
            while (!pending.isEmpty()) {
                Pending next = pending.remove(); files.add(next.file());
                for (Element element : LocatedXml.descendants(next.file().xml().root)) {
                    boolean wsdlImport = WSDL.equals(LocatedXml.ns(element)) && "import".equals(element.getLocalName());
                    boolean schemaImport = XSD.equals(LocatedXml.ns(element)) && Set.of("import", "include", "redefine").contains(element.getLocalName());
                    if (!wsdlImport && !schemaImport) continue;
                    if ("redefine".equals(element.getLocalName())) { diagnostics.add("unsupported_schema_redefine"); continue; }
                    String location = element.getAttribute(wsdlImport ? "location" : "schemaLocation");
                    if (location.isBlank()) { diagnostics.add("import_without_location"); continue; }
                    try {
                        URI uri = URI.create(location);
                        if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getQuery() != null || uri.getFragment() != null || location.contains("\\")) {
                            diagnostics.add("blocked_nonlocal_import"); continue;
                        }
                        Path relative = Path.of(uri.getPath());
                        if (relative.isAbsolute()) { diagnostics.add("blocked_absolute_import"); continue; }
                        Path target = next.file().file().path().toAbsolutePath().getParent().resolve(relative).normalize();
                        Parsed imported = inventory.get(target);
                        if (imported == null || !imported.file().metadata().get("repository_id").equals(source.file().metadata().get("repository_id"))) {
                            diagnostics.add("import_not_in_repository_inventory"); continue;
                        }
                        Element importedRoot = imported.xml().root;
                        if (wsdlImport ? !WSDL.equals(LocatedXml.ns(importedRoot)) || !"definitions".equals(importedRoot.getLocalName())
                                : !XSD.equals(LocatedXml.ns(importedRoot)) || !"schema".equals(importedRoot.getLocalName())) {
                            diagnostics.add("import_kind_mismatch"); continue;
                        }
                        String expected = element.getAttribute("namespace");
                        if ((!expected.isBlank() && !expected.equals(importedRoot.getAttribute("targetNamespace")))
                                || schemaImport && !"import".equals(element.getLocalName()) && !importedRoot.getAttribute("targetNamespace").equals(schemaNamespace(element))) {
                            // Chameleon includes need context-specific identities; retain an explicit unresolved diagnostic.
                            diagnostics.add("import_namespace_mismatch_or_chameleon_include"); continue;
                        }
                        if (visited.contains(target)) { diagnostics.add("import_revisit_or_cycle"); continue; }
                        if (next.depth() >= properties.getMaxImportDepth() || visited.size() >= properties.getMaxImportFiles()) {
                            diagnostics.add("import_limit"); continue;
                        }
                        visited.add(target); pending.add(new Pending(imported, next.depth() + 1));
                    }
                    catch (RuntimeException error) { diagnostics.add("invalid_import_location"); }
                }
            }
            return new Scope(List.copyOf(files), diagnostics.stream().distinct().toList());
        });
    }

    private List<Declaration> declarations(Parsed source, String namespace, String kind, String qname) {
        List<Declaration> result = new ArrayList<>();
        for (Parsed parsed : scope(source).files()) for (Element element : LocatedXml.descendants(parsed.xml().root)) {
            if (!namespace.equals(LocatedXml.ns(element)) || !kind.equals(element.getLocalName()) || !element.hasAttribute("name")) continue;
            boolean global = namespace.equals(XSD) ? element.getParentNode() instanceof Element parent && "schema".equals(parent.getLocalName()) && XSD.equals(LocatedXml.ns(parent))
                    : element.getParentNode() == parsed.xml().root;
            if (!global) continue;
            String targetNamespace = namespace.equals(XSD) ? schemaNamespace(element) : parsed.xml().root.getAttribute("targetNamespace");
            Declaration declaration = new Declaration(parsed, element, targetNamespace, kind);
            if (qname == null || declaration.qname().equals(qname)) result.add(declaration);
        }
        return result;
    }

    private Declaration find(Parsed source, String namespace, String kind, String qname) {
        List<Declaration> matches = declarations(source, namespace, kind, qname);
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private Map<String, Object> base(Parsed parsed, Element element, String kind, String semanticName, String namespace) {
        Map<String, Object> metadata = new LinkedHashMap<>(parsed.file().metadata());
        metadata.put("parser_version", VERSION); metadata.put("parse_status", "parsed");
        metadata.put("resolution_status", "not_attempted");
        if (!scope(parsed).diagnostics().isEmpty()) metadata.put("resolution_diagnostic", String.join("; ", scope(parsed).diagnostics()));
        metadata.put("semantic_xml", true); metadata.put("namespace_uri", namespace);
        metadata.put("element_qname", LocatedXml.qname(namespace, element.hasAttribute("name") && (kind.startsWith("xsd_") || kind.startsWith("wsdl_"))
                ? element.getAttribute("name") : element.getLocalName()));
        metadata.put("xml_path", LocatedXml.path(element));
        metadata.put("symbol_id", kind + ":" + LocatedXml.qname(namespace, semanticName));
        return metadata;
    }

    private SourceUnit unit(Parsed parsed, Element element, String kind, Map<String, Object> metadata, boolean overview) {
        LocatedXml.Range range = parsed.xml().range(element);
        List<Integer> boundaries = LocatedXml.descendants(element).stream().flatMap(value -> {
            LocatedXml.Range child = parsed.xml().range(value);
            return java.util.stream.Stream.of(child.start(), child.openEnd(), child.end());
        }).toList();
        return new SourceUnit(parsed.file().text(), range.start(), overview ? range.openEnd() : range.end(), kind, metadata, boundaries);
    }

    private String parent(Declaration declaration) {
        Map<String, Object> metadata = base(declaration.parsed(), declaration.element(), "xsd_" + declaration.kind(),
                declaration.element().getAttribute("name"), declaration.namespace());
        return unit(declaration.parsed(), declaration.element(), "xsd_" + declaration.kind(), metadata, false).parentId();
    }

    private List<SourceUnit> wsdl(Parsed parsed) {
        Element root = parsed.xml().root;
        if (!WSDL.equals(LocatedXml.ns(root)) || !"definitions".equals(root.getLocalName()))
            return fallback(parsed.file(), "wsdl_unsupported", "unsupported_version", "WSDL 2.0 is unsupported; WSDL 1.1 extraction only");
        List<SourceUnit> units = new ArrayList<>(); String ns = root.getAttribute("targetNamespace");
        Map<String, Object> overview = base(parsed, root, "wsdl_overview", root.getAttribute("name"), ns);
        overview.put("wsdl_version", "1.1");
        overview.put("contract_context", "Services: " + names(root, WSDL, "service") + "; Port types: " + names(root, WSDL, "portType")
                + "; Bindings: " + names(root, WSDL, "binding"));
        units.add(unit(parsed, root, "wsdl_overview", overview, true));
        for (Element service : LocatedXml.children(root, WSDL, "service")) {
            Map<String, Object> metadata = base(parsed, service, "wsdl_service", service.getAttribute("name"), ns);
            metadata.put("service", service.getAttribute("name"));
            metadata.put("contract_context", LocatedXml.children(service, WSDL, "port").stream().map(port -> port.getAttribute("name") + " -> "
                    + LocatedXml.reference(port, port.getAttribute("binding"))).toList().toString());
            units.add(unit(parsed, service, "wsdl_service", metadata, false));
        }
        for (Element port : LocatedXml.children(root, WSDL, "portType")) {
            for (Element operation : LocatedXml.children(port, WSDL, "operation")) {
                String name = operation.getAttribute("name"); String portQName = LocatedXml.qname(ns, port.getAttribute("name"));
                Map<String, Object> metadata = base(parsed, operation, "wsdl_operation", port.getAttribute("name") + "/" + name, ns);
                String input = messageRef(operation, "input"); String output = messageRef(operation, "output");
                // Input/output names disambiguate WSDL 1.1 overloaded operations.
                String inputName = messageName(operation, "input"); String outputName = messageName(operation, "output");
                metadata.put("symbol_id", metadata.get("symbol_id") + "/" + input + "/" + output + "/" + inputName + "/" + outputName);
                metadata.put("wsdl_version", "1.1"); metadata.put("port_type", portQName); metadata.put("operation", name);
                metadata.put("resolution_status", scope(parsed).diagnostics().stream().allMatch(value -> value.equals("import_revisit_or_cycle")) ? "resolved_local" : "partial");
                metadata.put("input_message", input); metadata.put("output_message", output);
                List<String> facts = new ArrayList<>(); List<String> links = new ArrayList<>();
                messageFacts(parsed, input, "Request", facts, links); messageFacts(parsed, output, "Response", facts, links);
                for (Element fault : LocatedXml.children(operation, WSDL, "fault")) {
                    String ref = LocatedXml.reference(fault, fault.getAttribute("message"));
                    messageFacts(parsed, ref, "Fault " + fault.getAttribute("name"), facts, links);
                }
                List<String> bindings = new ArrayList<>(); List<String> actions = new ArrayList<>();
                for (Declaration binding : declarations(parsed, WSDL, "binding", null)) {
                    if (!portQName.equals(LocatedXml.reference(binding.element(), binding.element().getAttribute("type")))) continue;
                    for (Element bound : LocatedXml.children(binding.element(), WSDL, "operation")) {
                        if (!name.equals(bound.getAttribute("name"))) continue;
                        long overloads = LocatedXml.children(port, WSDL, "operation").stream().filter(value -> name.equals(value.getAttribute("name"))).count();
                        if (overloads > 1 && (!inputName.equals(messageName(bound, "input")) || !outputName.equals(messageName(bound, "output")))) {
                            metadata.put("resolution_status", "partial");
                            continue;
                        }
                        bindings.add(binding.qname());
                        for (Element soap : LocatedXml.children(bound)) if (Set.of("http://schemas.xmlsoap.org/wsdl/soap/", "http://schemas.xmlsoap.org/wsdl/soap12/").contains(LocatedXml.ns(soap)) && "operation".equals(soap.getLocalName())) actions.add(soap.getAttribute("soapAction"));
                    }
                }
                metadata.put("soap_action", String.join(", ", actions));
                List<String> services = new ArrayList<>();
                facts.add("Bindings: " + bindings);
                for (Declaration service : declarations(parsed, WSDL, "service", null)) for (Element endpoint : LocatedXml.children(service.element(), WSDL, "port")) {
                    if (bindings.contains(LocatedXml.reference(endpoint, endpoint.getAttribute("binding")))) {
                        services.add(service.qname());
                        facts.add("Service: " + service.qname() + "; Port: " + endpoint.getAttribute("name"));
                    }
                }
                if (!services.isEmpty()) metadata.put("service", String.join(", ", services.stream().distinct().toList()));
                metadata.put("contract_context", String.join("; ", facts)); metadata.put("relationships", links);
                if (facts.stream().anyMatch(value -> value.contains("unresolved"))) metadata.put("resolution_status", "partial");
                units.add(unit(parsed, operation, "wsdl_operation", metadata, false));
            }
        }
        for (Element element : LocatedXml.children(root)) if (Set.of("message", "binding", "import", "documentation").contains(element.getLocalName()) && WSDL.equals(LocatedXml.ns(element))) {
            var metadata = base(parsed, element, "wsdl_" + element.getLocalName(), element.getAttribute("name").isBlank() ? LocatedXml.path(element) : element.getAttribute("name"), ns);
            units.add(unit(parsed, element, "wsdl_" + element.getLocalName(), metadata, false));
        }
        units.addAll(schemaDeclarations(parsed));
        return List.copyOf(units);
    }

    private static String messageRef(Element operation, String role) {
        Element child = LocatedXml.children(operation, WSDL, role).stream().findFirst().orElse(null);
        return child == null ? "" : LocatedXml.reference(child, child.getAttribute("message"));
    }

    private static String messageName(Element operation, String role) {
        return LocatedXml.children(operation, WSDL, role).stream().findFirst().map(element -> element.getAttribute("name")).orElse("");
    }

    private void messageFacts(Parsed source, String qname, String role, List<String> facts, List<String> links) {
        if (qname.isBlank()) return;
        Declaration message = find(source, WSDL, "message", qname);
        if (message == null) { facts.add(role + " message unresolved: " + qname); return; }
        facts.add(role + " message: " + qname);
        for (Element part : LocatedXml.children(message.element(), WSDL, "part")) {
            String kind = part.hasAttribute("element") ? "element" : "type";
            String ref = LocatedXml.reference(part, part.getAttribute(kind));
            Declaration declaration = kind.equals("element") ? find(source, XSD, "element", ref) : find(source, XSD, "complexType", ref);
            if (declaration == null && kind.equals("type")) declaration = find(source, XSD, "simpleType", ref);
            facts.add(role + " " + part.getAttribute("name") + " " + kind + ": " + ref + (declaration == null && !ref.startsWith("{" + XSD + "}") ? " (unresolved)" : ""));
            if (declaration != null) links.add("uses_schema|" + parent(declaration) + "|declared|" + ref);
        }
    }

    private List<SourceUnit> schema(Parsed parsed) {
        Element root = parsed.xml().root;
        var metadata = base(parsed, root, "xsd_overview", "schema", root.getAttribute("targetNamespace"));
        metadata.put("contract_context", "elementFormDefault=" + defaultValue(root.getAttribute("elementFormDefault"), "unqualified")
                + "; attributeFormDefault=" + defaultValue(root.getAttribute("attributeFormDefault"), "unqualified"));
        List<SourceUnit> units = new ArrayList<>(); units.add(unit(parsed, root, "xsd_overview", metadata, true));
        units.addAll(schemaDeclarations(parsed));
        return List.copyOf(units);
    }

    private List<SourceUnit> schemaDeclarations(Parsed parsed) {
        List<SourceUnit> units = new ArrayList<>();
        for (Element schema : LocatedXml.descendants(parsed.xml().root)) if (XSD.equals(LocatedXml.ns(schema)) && "schema".equals(schema.getLocalName())) {
            for (Element element : LocatedXml.children(schema)) {
                if (!XSD.equals(LocatedXml.ns(element))) continue;
                String kind = "xsd_" + element.getLocalName();
                String name = element.hasAttribute("name") ? element.getAttribute("name") : LocatedXml.path(element);
                var metadata = base(parsed, element, kind, name, schema.getAttribute("targetNamespace"));
                List<String> facts = new ArrayList<>(); List<String> links = new ArrayList<>();
                for (Element value : LocatedXml.descendants(element)) {
                    if (!XSD.equals(LocatedXml.ns(value))) continue;
                    if (Set.of("element", "attribute").contains(value.getLocalName())) {
                        String form = defaultValue(value.getAttribute("form"), defaultValue(schema.getAttribute(value.getLocalName() + "FormDefault"), "unqualified"));
                        boolean global = value.getParentNode() == schema;
                        String field = value.hasAttribute("ref") ? LocatedXml.reference(value, value.getAttribute("ref"))
                                : LocatedXml.qname(global || form.equals("qualified") ? schema.getAttribute("targetNamespace") : "", value.getAttribute("name"));
                        facts.add(value.getLocalName() + " " + field + " type=" + LocatedXml.reference(value, value.getAttribute("type"))
                                + (value.getLocalName().equals("element") ? " occurs=" + defaultValue(value.getAttribute("minOccurs"), "1") + ".." + defaultValue(value.getAttribute("maxOccurs"), "1")
                                : " use=" + defaultValue(value.getAttribute("use"), "optional"))
                                + (value.hasAttribute("nillable") ? " nillable=" + value.getAttribute("nillable") : ""));
                    }
                    if (Set.of("sequence", "choice", "all").contains(value.getLocalName())) facts.add(value.getLocalName() + " occurs=" + defaultValue(value.getAttribute("minOccurs"), "1") + ".." + defaultValue(value.getAttribute("maxOccurs"), "1"));
                    if (value.hasAttribute("base")) facts.add("base=" + LocatedXml.reference(value, value.getAttribute("base")));
                    if (value.hasAttribute("value")) facts.add(value.getLocalName() + "=" + value.getAttribute("value"));
                    if ("documentation".equals(value.getLocalName())) facts.add("Documentation: " + value.getTextContent().strip());
                    for (String attribute : List.of("type", "base", "ref", "itemType", "memberTypes")) if (value.hasAttribute(attribute)) {
                        for (String lexical : value.getAttribute(attribute).strip().split("\\s+")) {
                            String ref = LocatedXml.reference(value, lexical);
                            Declaration target = find(parsed, XSD, attribute.equals("ref") ? value.getLocalName() : "complexType", ref);
                            if (target == null && !attribute.equals("ref")) target = find(parsed, XSD, "simpleType", ref);
                            if (target != null) links.add("uses_schema|" + parent(target) + "|declared|" + ref);
                            else if (!ref.startsWith("{" + XSD + "}")) facts.add("unresolved reference=" + ref);
                        }
                    }
                }
                metadata.put("resolution_status", scope(parsed).diagnostics().stream().allMatch(value -> value.equals("import_revisit_or_cycle")) ? "resolved_local" : "partial");
                metadata.put("contract_context", String.join("; ", facts)); metadata.put("relationships", links.stream().distinct().toList());
                if (facts.stream().anyMatch(value -> value.startsWith("unresolved"))) metadata.put("resolution_status", "partial");
                units.add(unit(parsed, element, kind, metadata, false));
            }
        }
        return units;
    }

    private List<SourceUnit> maven(Parsed parsed) {
        Element root = parsed.xml().root; String ns = LocatedXml.ns(root);
        List<SourceUnit> units = new ArrayList<>();
        var overview = base(parsed, root, "maven_overview", "project", ns);
        // Discovery may inherit coordinates. Extraction records only this POM's declaration.
        for (String key : List.of("group_id", "artifact_id", "version", "packaging")) overview.remove(key);
        overview.put("group_id", value(root, "groupId")); overview.put("artifact_id", value(root, "artifactId"));
        overview.put("version", value(root, "version")); overview.put("packaging", defaultValue(value(root, "packaging"), "jar"));
        overview.put("dependency_model", "declared"); overview.put("maven_resolution_policy", properties.getMavenResolutionPolicy());
        overview.put("resolution_status", "declared"); overview.put("active_profiles", "none; profiles indexed as declarations");
        overview.put("contract_context", "Parent: " + coordinates(XmlSupport.child(root, "parent")) + "; Modules: "
                + childrenText(XmlSupport.child(root, "modules")) + "; Properties: " + childrenText(XmlSupport.child(root, "properties")));
        units.add(unit(parsed, root, "maven_overview", overview, true));
        Map<String, String> localProperties = new LinkedHashMap<>();
        Element props = XmlSupport.child(root, "properties");
        if (props != null) for (Element property : LocatedXml.children(props)) localProperties.put(property.getLocalName(), property.getTextContent().strip());
        for (Element element : LocatedXml.descendants(root)) {
            if (!ns.equals(LocatedXml.ns(element)) || element == root) continue;
            String name = element.getLocalName();
            if (!Set.of("parent", "modules", "properties", "dependencies", "dependency", "plugin", "configuration", "profile", "repositories", "pluginRepositories").contains(name)) continue;
            // Avoid mistaking arbitrary plugin configuration elements for Maven declarations.
            if (hasAncestor(element, "configuration")) continue;
            String kind = "maven_" + name;
            boolean dependency = "dependency".equals(name);
            boolean management = hasAncestor(element, "dependencyManagement") || hasAncestor(element, "pluginManagement");
            if (management) kind += "_management";
            String identity = LocatedXml.path(element); // Stable position within each declared block, no effective-model guesses.
            var metadata = base(parsed, element, kind, identity, ns);
            for (String key : List.of("group_id", "artifact_id", "version", "packaging")) metadata.remove(key);
            metadata.put("dependency_model", "declared"); metadata.put("maven_resolution_policy", properties.getMavenResolutionPolicy());
            metadata.put("resolution_status", "declared"); metadata.put("dependency_management", management);
            String profile = name.equals("profile") ? value(element, "id") : ancestorValue(element, "profile", "id");
            metadata.put("profile", profile.isBlank() ? "base" : profile); metadata.put("active_profiles", "none");
            if (dependency || name.equals("plugin") || name.equals("parent")) {
                metadata.put("group_id", value(element, "groupId")); metadata.put("artifact_id", value(element, "artifactId"));
                String version = value(element, "version"); metadata.put("declared_version", version); metadata.put("version", version);
                if (dependency) metadata.put("dependency_scope", defaultValue(value(element, "scope"), "compile"));
                if (properties.getMavenResolutionPolicy().equals("local-properties") && profile.isBlank() && !version.isBlank()) {
                    String resolved = interpolate(version, localProperties);
                    if (!resolved.contains("${")) { metadata.put("resolved_version", resolved); metadata.put("resolution_status", "local_properties"); }
                }
                if (version.isBlank()) metadata.put("resolution_status", "version_not_declared");
            }
            List<String> placeholders = Pattern.compile("\\$\\{[^}]+}").matcher(element.getTextContent()).results().map(result -> result.group()).distinct().toList();
            if (!placeholders.isEmpty()) {
                metadata.put("declared_placeholders", placeholders);
                List<String> unresolved = placeholders.stream().filter(placeholder -> !properties.getMavenResolutionPolicy().equals("local-properties")
                        || !profile.isBlank() || interpolate(placeholder, localProperties).contains("${")).toList();
                if (!unresolved.isEmpty()) metadata.put("unresolved_placeholders", unresolved);
            }
            metadata.put("contract_context", "Declaration in profile " + metadata.get("profile") + "; dependencyManagement=" + management
                    + (metadata.containsKey("resolved_version") ? "; Local property version: " + metadata.get("resolved_version") : ""));
            units.add(unit(parsed, element, kind, metadata, false));
        }
        return List.copyOf(units);
    }

    private List<SourceUnit> other(Parsed parsed) {
        List<SourceUnit> units = new ArrayList<>();
        for (Element element : LocatedXml.descendants(parsed.xml().root)) if (properties.getElementNames().contains(element.getLocalName())) {
            var metadata = base(parsed, element, "xml_element", LocatedXml.path(element), LocatedXml.ns(element));
            metadata.put("contract_context", "ID: " + element.getAttribute("id") + "; Name: " + element.getAttribute("name"));
            units.add(unit(parsed, element, "xml_element", metadata, false));
        }
        if (units.isEmpty()) {
            Element root = parsed.xml().root;
            units.add(unit(parsed, root, "xml_document", base(parsed, root, "xml_document", LocatedXml.path(root), LocatedXml.ns(root)), false));
        }
        return List.copyOf(units);
    }

    private static String schemaNamespace(Element element) {
        for (Element current = element; current != null; current = current.getParentNode() instanceof Element parent ? parent : null)
            if (XSD.equals(LocatedXml.ns(current)) && "schema".equals(current.getLocalName())) return current.getAttribute("targetNamespace");
        return "";
    }
    private static String names(Element parent, String ns, String kind) { return LocatedXml.children(parent, ns, kind).stream().map(value -> value.getAttribute("name")).toList().toString(); }
    private static String defaultValue(String value, String fallback) { return value.isBlank() ? fallback : value; }
    private static String value(Element element, String name) { return element == null ? "" : XmlSupport.value(element, name); }
    private static String coordinates(Element element) { return value(element, "groupId") + ":" + value(element, "artifactId") + ":" + value(element, "version"); }
    private static String childrenText(Element element) { return element == null ? "" : LocatedXml.children(element).stream().map(child -> child.getLocalName() + "=" + child.getTextContent().strip()).toList().toString(); }
    private static boolean hasAncestor(Element element, String name) {
        for (var node = element.getParentNode(); node instanceof Element parent; node = parent.getParentNode()) if (LocatedXml.ns(parent).equals(LocatedXml.ns(element)) && name.equals(parent.getLocalName())) return true;
        return false;
    }
    private static String ancestorValue(Element element, String name, String child) {
        for (var node = element.getParentNode(); node instanceof Element parent; node = parent.getParentNode()) if (LocatedXml.ns(parent).equals(LocatedXml.ns(element)) && name.equals(parent.getLocalName())) return value(parent, child);
        return "";
    }
    private static String interpolate(String value, Map<String, String> properties) {
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < 16 && seen.add(value); i++) {
            String previous = value;
            for (var entry : properties.entrySet()) value = value.replace("${" + entry.getKey() + "}", entry.getValue());
            if (value.equals(previous) || value.length() > 8192) return previous;
        }
        return value;
    }
}
