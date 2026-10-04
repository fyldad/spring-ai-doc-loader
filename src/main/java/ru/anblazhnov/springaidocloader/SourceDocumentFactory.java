package ru.anblazhnov.springaidocloader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;

final class SourceDocumentFactory {
    static final String VERSION = "source-document-v1";
    static final String CHUNKER_VERSION = "line-character-v1";
    private final int maxCharacters;
    private final SourceSnapshotStore snapshots;

    SourceDocumentFactory(ChunkingProperties properties) {
        properties.validate();
        maxCharacters = properties.getMaxCharacters();
        snapshots = new SourceSnapshotStore(properties.getSnapshotDirectory());
    }

    List<Document> create(SourceUnit unit) {
        if (unit.text().isBlank()) return List.of();
        String header = header(unit.metadata());
        int budget = maxCharacters - header.length();
        if (budget < 2) throw new IllegalArgumentException("Source context header exceeds chunk character budget");
        String sourceReference = snapshots.save(unit.fileId(), unit.source());
        String parentReference = snapshots.save(unit.parentId(), unit.text());
        String snapshotHash = SourceIdentity.hash(unit.source());
        int[] lineStarts = lineStarts(unit.source());
        List<Document> documents = new ArrayList<>();
        int start = unit.startOffset();
        while (start < unit.endOffset()) {
            int end = boundary(unit.source(), start, unit.endOffset(), budget);
            String excerpt = unit.source().substring(start, end);
            int part = documents.size();
            String id = SourceIdentity.uuid("chunk", unit.parentId(), unit.chunkKind(), Integer.toString(part));
            Map<String, Object> metadata = new LinkedHashMap<>(unit.metadata());
            // Module relationships belong in the inventory, not in every retrieval payload.
            metadata.remove("declared_modules");
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
            metadata.put("chunker_version", CHUNKER_VERSION);
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
        labels.put("namespace_uri", "Namespace");
        labels.put("element_qname", "Element");
        labels.put("service", "Service");
        labels.put("port_type", "Port type");
        labels.put("operation", "Operation");
        labels.put("soap_action", "SOAP action");
        labels.put("group_id", "Group");
        labels.put("artifact_id", "Artifact");
        labels.put("version", "Version");
        StringBuilder header = new StringBuilder();
        labels.forEach((key, label) -> {
            Object value = metadata.get(key);
            if (value != null && !value.toString().isBlank()) {
                header.append(label).append(": ").append(value.toString().replaceAll("[\\r\\n]+", " ")).append('\n');
            }
        });
        return header.append('\n').toString();
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
