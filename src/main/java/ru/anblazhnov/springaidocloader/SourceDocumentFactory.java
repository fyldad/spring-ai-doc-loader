package ru.anblazhnov.springaidocloader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;

final class SourceDocumentFactory {
    static final String VERSION = "source-document-v2";
    static final String CHUNKER_VERSION = "line-character-v1";
    private final int maxCharacters;
    private final int javaMaxTokens;
    private final SourceSnapshotStore snapshots;

    SourceDocumentFactory(ChunkingProperties properties) {
        properties.validate();
        maxCharacters = properties.getMaxCharacters();
        javaMaxTokens = properties.getJavaMaxTokens();
        snapshots = new SourceSnapshotStore(properties.getSnapshotDirectory());
    }

    List<Document> create(SourceUnit unit) {
        if (unit.text().isBlank()) return List.of();
        boolean semanticJava = Boolean.TRUE.equals(unit.metadata().get("semantic_java"));
        boolean semanticXml = Boolean.TRUE.equals(unit.metadata().get("semantic_xml"));
        String header = semanticJava ? javaHeader(unit.metadata()) : semanticXml ? xmlHeader(unit.metadata()) : header(unit.metadata());
        int budget = semanticJava ? javaMaxTokens - ConservativeTokenBudget.count(header) : maxCharacters - header.length();
        if (budget < (semanticJava ? 6 : 2)) throw new IllegalArgumentException("Source context header exceeds chunk budget");
        String sourceReference = snapshots.save(unit.fileId(), unit.source());
        String parentReference = snapshots.save(unit.parentId(), unit.text());
        String snapshotHash = SourceIdentity.hash(unit.source());
        String javaContext = javaContext(unit.metadata());
        String javaContextReference = javaContext.isBlank() ? null : snapshots.save(
                SourceIdentity.uuid("java-context", unit.parentId()), javaContext);
        String relationships = relationshipText(unit.metadata());
        String relationshipReference = relationships.isBlank() ? null : snapshots.save(
                SourceIdentity.uuid("relationships", unit.parentId()), relationships);
        String contractContext = String.valueOf(unit.metadata().getOrDefault("contract_context", ""));
        String contractReference = contractContext.isBlank() ? null : snapshots.save(
                SourceIdentity.uuid("contract-context", unit.parentId()), contractContext);
        int[] lineStarts = lineStarts(unit.source());
        List<Document> documents = new ArrayList<>();
        int start = unit.startOffset();
        while (start < unit.endOffset()) {
            int end;
            if (semanticJava) {
                int hardEnd = ConservativeTokenBudget.end(unit.source(), start, unit.endOffset(), budget);
                end = hardEnd;
                if (hardEnd < unit.endOffset()) {
                    int semanticEnd = start;
                    for (int candidate : unit.boundaries()) {
                        if (candidate > hardEnd) break;
                        if (candidate > start) semanticEnd = candidate;
                    }
                    end = semanticEnd > start ? semanticEnd : boundary(unit.source(), start, unit.endOffset(), hardEnd - start);
                }
            }
            else {
                int hardEnd = (int) Math.min(unit.endOffset(), (long) start + budget);
                int semanticEnd = start;
                if (semanticXml && hardEnd < unit.endOffset()) for (int candidate : unit.boundaries()) {
                    if (candidate > hardEnd) break;
                    if (candidate > start) semanticEnd = candidate;
                }
                end = semanticEnd > start ? semanticEnd : boundary(unit.source(), start, unit.endOffset(), budget);
            }
            String excerpt = unit.source().substring(start, end);
            int part = documents.size();
            String id = SourceIdentity.uuid("chunk", unit.parentId(), unit.chunkKind(), Integer.toString(part));
            Map<String, Object> metadata = new LinkedHashMap<>(unit.metadata());
            // Module relationships belong in the inventory, not in every retrieval payload.
            metadata.remove("declared_modules");
            metadata.remove("semantic_java");
            metadata.remove("imports");
            metadata.remove("member_signatures");
            metadata.remove("semantic_xml");
            metadata.remove("relationships");
            metadata.remove("contract_context");
            if (relationshipReference != null) metadata.put("relationships_snapshot", relationshipReference);
            if (contractReference != null) metadata.put("contract_context_snapshot", contractReference);
            if (javaContextReference != null) metadata.put("java_context_snapshot", javaContextReference);
            metadata.put("chunk_id", id);
            metadata.put("parent_id", unit.parentId());
            metadata.put("chunk_kind", unit.chunkKind());
            metadata.put("part_index", part);
            metadata.put("chunk_hash", SourceIdentity.hash(header + excerpt));
            metadata.put("snapshot_hash", snapshotHash);
            metadata.put("source_snapshot", sourceReference);
            metadata.put("parent_snapshot", parentReference);
            metadata.put("start_line", lineAt(lineStarts, start));
            metadata.put("end_line", lineAt(lineStarts, end - 1));
            metadata.put("start_offset", start);
            metadata.put("end_offset", end);
            metadata.put("chunker_version", semanticJava ? "java-statement-token-v1" : semanticXml ? "xml-element-character-v1" : CHUNKER_VERSION);
            if (semanticJava) {
                int estimate = ConservativeTokenBudget.count(header + excerpt);
                if (estimate > javaMaxTokens) throw new IllegalStateException("Java chunk exceeds configured token bound");
                metadata.put("token_estimate", estimate);
                metadata.put("token_budget", javaMaxTokens);
                metadata.put("token_counter", ConservativeTokenBudget.VERSION);
                metadata.put("split_basis", unit.boundaries().contains(end) || end == unit.endOffset() ? "semantic" : "line_or_character");
            }
            metadata.put("document_format_version", VERSION);
            Document document = new Document(id, header + excerpt, metadata);
            // Do not reintroduce bookkeeping when a caller asks for formatted embedding/LLM content.
            document.setContentFormatter((source, mode) -> source.getText());
            documents.add(document);
            start = end;
        }
        for (Document document : documents) document.getMetadata().put("part_count", documents.size());
        return List.copyOf(documents);
    }

    static String header(Map<String, Object> metadata) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("repository_id", "Repository");
        labels.put("module_id", "Module");
        labels.put("relative_path", "File");
        labels.put("package", "Package");
        labels.put("type_fqn", "Type");
        labels.put("signature", "Signature");
        labels.put("annotations", "Annotations");
        labels.put("overview", "Members");
        labels.put("namespace_uri", "Namespace");
        labels.put("element_qname", "Element");
        labels.put("service", "Service");
        labels.put("port_type", "Port type");
        labels.put("operation", "Operation");
        labels.put("soap_action", "SOAP action");
        labels.put("group_id", "Group");
        labels.put("artifact_id", "Artifact");
        labels.put("version", "Version");
        labels.put("resolved_version", "Local property version");
        labels.put("dependency_scope", "Declared scope");
        labels.put("profile", "Declared profile");
        labels.put("dependency_model", "Dependency model");
        labels.put("contract_context", "Contract context");
        StringBuilder header = new StringBuilder();
        labels.forEach((key, label) -> {
            Object value = metadata.get(key);
            if (value != null && !value.toString().isBlank()) {
                header.append(label).append(": ").append(value.toString().replaceAll("[\\r\\n]+", " ")).append('\n');
            }
        });
        return header.append('\n').toString();
    }

    private String javaHeader(Map<String, Object> metadata) {
        Map<String, Object> compact = new LinkedHashMap<>(metadata);
        for (String field : List.of("signature", "annotations", "overview", "contract_context")) {
            Object value = compact.get(field);
            if (value != null) compact.put(field, ConservativeTokenBudget.abbreviate(value.toString(), Math.max(32, javaMaxTokens / 5)));
        }
        return header(compact);
    }

    private String xmlHeader(Map<String, Object> metadata) {
        Map<String, Object> compact = new LinkedHashMap<>(metadata);
        for (String field : List.of("namespace_uri", "element_qname", "service", "port_type", "operation", "soap_action",
                "group_id", "artifact_id", "version", "resolved_version", "profile")) {
            if (compact.containsKey(field)) compact.put(field, abbreviateCharacters(compact.get(field).toString(), Math.max(8, maxCharacters / 6)));
        }
        // Reserve half the character budget for the exact source; full context lives in a snapshot.
        compact.remove("contract_context");
        String essential = header(compact);
        if (essential.length() >= maxCharacters - 2) {
            compact.keySet().removeIf(key -> !List.of("repository_id", "module_id", "relative_path").contains(key));
            essential = header(compact);
        }
        int available = Math.max(0, maxCharacters / 2 - essential.length() - "Contract context: \n".length());
        if (available > 0 && metadata.containsKey("contract_context")) compact.put("contract_context", abbreviateCharacters(metadata.get("contract_context").toString(), available));
        return header(compact);
    }

    private static String abbreviateCharacters(String value, int limit) {
        if (value.length() <= limit) return value;
        int end = Math.max(0, limit - 1);
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end) + "…";
    }

    private static String relationshipText(Map<String, Object> metadata) {
        return metadata.get("relationships") instanceof List<?> values ? String.join("\n", values.stream().map(Object::toString).toList()) : "";
    }

    private static String javaContext(Map<String, Object> metadata) {
        StringBuilder context = new StringBuilder();
        for (String field : List.of("imports", "inheritance", "member_signatures")) {
            if (metadata.get(field) instanceof List<?> values && !values.isEmpty()) {
                context.append(field).append(":\n");
                values.forEach(value -> context.append(value).append('\n'));
            }
        }
        return context.toString();
    }

    private static int boundary(String source, int start, int limit, int budget) {
        int end = (int) Math.min(limit, (long) start + budget);
        if (end < limit) {
            if (Character.isHighSurrogate(source.charAt(end - 1)) && Character.isLowSurrogate(source.charAt(end))) end--;
            if (source.charAt(end - 1) == '\r' && source.charAt(end) == '\n') end--;
            for (int index = end - 1; index >= start; index--) {
                if (source.charAt(index) == '\n' || source.charAt(index) == '\r') {
                    end = index + 1;
                    break;
                }
            }
        }
        return end;
    }

    private static int[] lineStarts(String source) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int index = 0; index < source.length(); index++) {
            if (source.charAt(index) == '\r') {
                if (index + 1 < source.length() && source.charAt(index + 1) == '\n') index++;
                starts.add(index + 1);
            }
            else if (source.charAt(index) == '\n') starts.add(index + 1);
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int lineAt(int[] starts, int offset) {
        int result = Arrays.binarySearch(starts, offset);
        return result >= 0 ? result + 1 : -result - 1;
    }
}
