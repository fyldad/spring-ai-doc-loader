# Source and document contract

Roadmap section 3 is implemented. The Spring Cloud Function composition is now:

```text
fileTreeSupplier -> documentReader -> createDocuments -> vectorStoreWriter
```

Discovery still validates and redacts files before emitting them. `documentReader` returns a `SourceUnit`, not a Spring AI `Document`. Each unit contains a full decoded source snapshot, an exclusive character range, a chunk kind, and source metadata. Current readers emit whole-file units with `chunk_kind=file_excerpt`. Future AST/XML readers can emit ranges into the same snapshot and add the applicable Java, SOAP, or Maven fields without changing the document factory. Java symbol units require both `symbol_id` and `signature`; include qualified parameter types in symbol IDs to distinguish overloads. AST extraction and symbol resolution are not implemented in this phase.

`SourceDocumentFactory` slices units verbatim, prefers complete lines, and splits oversized lines without breaking CRLF or Unicode surrogate pairs. It repeats a small factual context header on each part. The budget includes that header:

```yaml
ingestion:
  chunks:
    max-characters: 1200
    snapshot-directory: .ingestion/source-snapshots
  qdrant:
    payload-indexes: true
```

This is a character budget, not a model token count or a guarantee against embedding truncation. Model tokenization and semantic splitting are phase 4 work. A header that leaves fewer than two characters for source causes an explicit error; increase the budget for repositories with long paths/signatures. Whitespace-only sources emit no documents.

## Metadata

The factory preserves applicable discovery/reader fields and adds typed, flat chunk fields:

| Fields | Meaning |
| --- | --- |
| `repository_id`, `module_id`, `relative_path`, `language`, `file_kind`, `source_set` | Portable identity and filtering; no checkout path in the vector payload |
| `revision`, `dirty_worktree`, `revision_status` | Git HEAD and repository-wide status sampled when the repository scan starts. `revision_status` is `available`, `unborn`, `unversioned`, or `unavailable`; unknown values are omitted rather than invented |
| `file_hash` | SHA-256 of the original bytes read, including BOM; captured before redaction |
| `snapshot_hash` | SHA-256 of the UTF-8 encoding of decoded/sanitized snapshot text |
| `chunk_hash` | SHA-256 of the exact header plus excerpt that is stored and embedded |
| `chunk_id`, `parent_id`, `chunk_kind`, `part_index`, `part_count` | Deterministic UUID point ID, logical parent, retrieval unit kind, and zero-based part number/count |
| `start_line`, `end_line` | Inclusive, one-based lines in the source snapshot; header lines never count |
| `start_offset`, `end_offset` | Zero-based UTF-16 character offsets, with an exclusive end, matching Java `String.substring` |
| `source_snapshot`, `parent_snapshot` | Portable references to the full source and the parent unit excerpt outside Qdrant |
| `parser_version`, `chunker_version`, `document_format_version` | `full-file-reader-v1`, `line-character-v1`, `source-document-v1` |
| `parse_status`, `resolution_status`, `generated`, `content_origin`, `location_basis` | Validation/provenance; current full-file readers report `validated` for XML/YAML or `text_only` otherwise, and `not_attempted` for resolution |

Applicable Java (`package`, `type_fqn`, `symbol_id`, `signature`, `annotations`, `visibility`), XML/SOAP (`namespace_uri`, `element_qname`, `xml_path`, `service`, `port_type`, `operation`, `soap_action`), and Maven fields remain reader-owned. The factory does not fabricate semantic fields. Discovery currently provides package hints, root namespaces, and declared Maven coordinates. `declared_modules` remains in the discovery inventory and is omitted from individual vector payloads; relationship extraction/persistence comes in later phases.

## Identity and parent lookup

Logical file identity uses the configured repository ID, module ID, and portable relative path. A symbol parent additionally uses the overload-specific symbol ID, which must incorporate qualified parameter types for methods/constructors. The readable `signature` header can change (for example when a parameter is renamed) without changing symbol identity. Chunk identity uses this parent, chunk kind, and part index. Length-prefixed key components prevent delimiter ambiguity; namespaced Java name-based UUIDs are valid Qdrant point IDs. `Document.id` equals `chunk_id`. Content hashes and checkout directories do not affect logical IDs.

An unchanged rerun uses the same IDs. Content edits update hashes and overwrite the same point IDs for surviving parts. Changed split boundaries can leave obsolete points; removal of obsolete parts, deleted files, and failed-run reconciliation belongs to phase 8. Prefer a fresh collection when migrating an existing random-ID index to this contract.

The snapshot store writes full sources and parent excerpts to `<snapshot-directory>/<logical-parent-uuid>/<sha256>.txt` in UTF-8, using atomic replacement. Existing content hashes remain available after edits. It stores only sanitized text for transformed configuration; original secrets are not copied into the snapshot store. Snapshot lookup validates the reference and content hash. The default `.ingestion/source-snapshots` directory is ignored by Git and survives Gradle `clean`. Keep this directory with the vector collection; source and parent lookup fail if the referenced artifacts are missing. The configured snapshot subtree is excluded from discovery, even when it is inside an indexed repository.

Given a retrieved document, read `source_snapshot` through `SourceSnapshotStore.read`, then slice the returned string at `[start_offset, end_offset)` (or use `SourceSnapshotStore.excerpt`). This reproduces the stored excerpt exactly. Read `parent_snapshot` to obtain the complete parent unit; `parent_id` identifies that unit without inferring an enclosing file or symbol from text. Current file parents are stored outside the vector store, so vector lookup by `parent_id` is not required.

For `location_basis=original_source`, display `repository_id:relative_path:start_line-end_line` with the revision and file hash. The retained decoded text supplies the exact indexed excerpt even after the worktree changes. For `location_basis=sanitized_snapshot`, label the citation as a sanitized snapshot and include its reference: configuration serialization changes formatting, so these numbers cannot be used to locate the original-file lines. `content_origin=sanitized_source` makes this distinction filterable.

## Embedding and Qdrant behavior

The document text explicitly contains an allowlisted header (repository, module, file, and applicable package/type/signature/annotations, namespace/contract facts, and Maven coordinates), a blank line, and the unmodified source slice. Hashes, timestamps, internal versions, IDs, and relationship lists are excluded from the header. A formatter on newly created documents also returns that text for every metadata mode.

Verified against the installed Spring AI **2.0.1** source JARs: `OllamaEmbeddingModel.embed(Document)` uses `getText()`, and the batch path used by `QdrantVectorStore` extracts text through `EmbeddingModel.getEmbeddingContent`. Qdrant persists `Document.getText()` as `doc_content`, preserves metadata as payload, and requires UUID document IDs. A test exercises the installed Ollama model against a mocked API and asserts that the embedding request is exactly the explicit header plus source. Arbitrary metadata is not automatically embedded. Retrieval consumers should pass `Document.getText()` to the LLM and generate citations from metadata; content formatters are ephemeral and are not restored when Qdrant constructs a retrieved document.

When both `ingestion.qdrant.payload-indexes=true` and `spring.ai.vectorstore.qdrant.initialize-schema=true`, an ordered startup runner creates keyword indexes for `repository_id`, `module_id`, `language`, and `chunk_kind` in the configured Qdrant collection before ingestion starts. Index requests wait for completion and use a 30-second RPC timeout. If schema initialization is disabled, provision these indexes externally or explicitly enable schema initialization. Tests mock Qdrant; no live Qdrant/Ollama service is required.

Run `./gradlew.bat test` on Windows. Tests cover pipeline composition, exact excerpts and source lines, CRLF/bare-CR/Unicode splitting, overload and split-parent identities, repeated runs and edits, original-byte hashes and Git provenance, sanitized snapshots, snapshot exclusion/integrity, embedding text, and payload-index requests.
