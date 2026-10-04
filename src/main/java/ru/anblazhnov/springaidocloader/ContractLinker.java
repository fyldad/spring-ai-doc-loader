package ru.anblazhnov.springaidocloader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded annotation/name matches are suggestions, never claims of full Java type resolution. */
final class ContractLinker {
    private static final int MAX_MATCHES = 32;
    static List<SourceUnit> link(List<SourceUnit> units) {
        Map<String, List<SourceUnit>> schemas = new LinkedHashMap<>();
        Map<String, List<SourceUnit>> operations = new LinkedHashMap<>();
        Map<String, List<SourceUnit>> javaTypes = new LinkedHashMap<>();
        Map<String, List<SourceUnit>> javaMethods = new LinkedHashMap<>();
        for (SourceUnit unit : units) {
            String repository = unit.value("repository_id");
            if (List.of("xsd_element", "xsd_complexType", "xsd_simpleType").contains(unit.chunkKind()))
                schemas.computeIfAbsent(repository + "|" + unit.value("element_qname"), ignored -> new ArrayList<>()).add(unit);
            if (unit.chunkKind().equals("wsdl_operation"))
                operations.computeIfAbsent(repository + "|" + unit.value("operation"), ignored -> new ArrayList<>()).add(unit);
            if (unit.chunkKind().equals("java_type_overview"))
                javaTypes.computeIfAbsent(repository + "|" + unit.value("type_fqn"), ignored -> new ArrayList<>()).add(unit);
            if (unit.chunkKind().equals("java_method"))
                javaMethods.computeIfAbsent(repository + "|" + unit.value("type_fqn"), ignored -> new ArrayList<>()).add(unit);
        }
        Map<String, List<String>> links = new LinkedHashMap<>();
        Map<String, List<String>> contexts = new LinkedHashMap<>();
        for (SourceUnit unit : units) {
            if (!"java".equals(unit.value("language"))) continue;
            String repository = unit.value("repository_id");
            if (unit.metadata().containsKey("soap_schema_qname")) {
                List<SourceUnit> candidates = schemas.getOrDefault(repository + "|" + unit.value("soap_schema_qname"), List.of());
                String kind = String.valueOf(unit.metadata().getOrDefault("soap_schema_kind", "element"));
                for (SourceUnit target : candidates.stream().limit(MAX_MATCHES).toList()) if (kind.equals("element") == target.chunkKind().equals("xsd_element"))
                    connect(unit, target, "maps_to_schema", links, contexts);
            }
            if (unit.chunkKind().equals("java_method") && unit.metadata().containsKey("soap_operation")) {
                for (SourceUnit target : operations.getOrDefault(repository + "|" + unit.value("soap_operation"), List.of()).stream().limit(MAX_MATCHES).toList()) {
                    if (!unit.metadata().containsKey("soap_namespace") || !unit.value("soap_namespace").equals(target.value("namespace_uri"))) continue;
                    if (unit.metadata().containsKey("soap_port_type") && !LocatedXml.qname(unit.value("soap_namespace"), unit.value("soap_port_type")).equals(target.value("port_type"))) continue;
                    connect(unit, target, "maps_to_operation", links, contexts);
                }
            }
            if (unit.chunkKind().equals("java_type_overview") && unit.metadata().containsKey("soap_endpoint_interface")) {
                for (SourceUnit target : javaTypes.getOrDefault(repository + "|" + unit.value("soap_endpoint_interface"), List.of()).stream().limit(MAX_MATCHES).toList())
                    connect(unit, target, "implements_service_interface", links, contexts);
                for (SourceUnit implementation : javaMethods.getOrDefault(repository + "|" + unit.value("type_fqn"), List.of())) {
                    String methodKey = implementation.value("symbol_id").substring(implementation.value("symbol_id").indexOf('#'));
                    for (SourceUnit declaration : javaMethods.getOrDefault(repository + "|" + unit.value("soap_endpoint_interface"), List.of()).stream().limit(MAX_MATCHES).toList()) {
                        if (!declaration.value("symbol_id").endsWith(methodKey) || !declaration.metadata().containsKey("soap_operation") || !declaration.metadata().containsKey("soap_namespace")) continue;
                        connect(implementation, declaration, "implements_service_method", links, contexts);
                        for (SourceUnit operation : operations.getOrDefault(repository + "|" + declaration.value("soap_operation"), List.of()).stream().limit(MAX_MATCHES).toList()) {
                            if (!declaration.value("soap_namespace").equals(operation.value("namespace_uri"))) continue;
                            if (declaration.metadata().containsKey("soap_port_type") && !LocatedXml.qname(declaration.value("soap_namespace"), declaration.value("soap_port_type")).equals(operation.value("port_type"))) continue;
                            connect(implementation, operation, "maps_to_operation", links, contexts);
                        }
                    }
                }
            }
        }
        List<SourceUnit> result = new ArrayList<>();
        for (SourceUnit unit : units) {
            if (!links.containsKey(unit.parentId())) { result.add(unit); continue; }
            Map<String, Object> metadata = new LinkedHashMap<>(unit.metadata());
            List<String> relationships = new ArrayList<>();
            if (metadata.get("relationships") instanceof List<?> existing) existing.forEach(value -> relationships.add(value.toString()));
            relationships.addAll(links.get(unit.parentId())); metadata.put("relationships", relationships.stream().distinct().toList());
            metadata.put("contract_context", metadata.getOrDefault("contract_context", "") + "; " + String.join("; ", contexts.get(unit.parentId())));
            result.add(new SourceUnit(unit.source(), unit.startOffset(), unit.endOffset(), unit.chunkKind(), metadata, unit.boundaries()));
        }
        return List.copyOf(result);
    }

    private static void connect(SourceUnit source, SourceUnit target, String relation,
            Map<String, List<String>> links, Map<String, List<String>> contexts) {
        links.computeIfAbsent(source.parentId(), ignored -> new ArrayList<>()).add(relation + "|" + target.parentId() + "|inferred|annotation_literal");
        links.computeIfAbsent(target.parentId(), ignored -> new ArrayList<>()).add("mapped_from_java|" + source.parentId() + "|inferred|annotation_literal");
        String targetIdentity = "java".equals(target.value("language")) ? target.value("symbol_id") : target.value("element_qname");
        contexts.computeIfAbsent(source.parentId(), ignored -> new ArrayList<>()).add("Inferred related source: " + targetIdentity + " in " + target.value("relative_path"));
        contexts.computeIfAbsent(target.parentId(), ignored -> new ArrayList<>()).add("Inferred Java link: " + source.value("symbol_id") + " in " + source.value("relative_path"));
    }
}
