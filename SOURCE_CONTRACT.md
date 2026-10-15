# Source and document contract

Roadmap section 3 is implemented. The Spring Cloud Function composition is now:

```text
fileTreeSupplier -> documentReader -> createDocuments -> vectorStoreWriter
```

Discovery validates and redacts files before emitting them. `documentReader` returns structured `SourceUnit`s, not Spring AI `Document`s. Each unit contains decoded source text, an exclusive character range, a chunk kind, source metadata, and optional semantic boundaries. Java emits AST units with optional local symbol resolution as documented in [JAVA_CHUNKING.md](JAVA_CHUNKING.md). WSDL, XSD, Maven, and configured XML readers emit namespace-aware semantic units as documented in [XML_CHUNKING.md](XML_CHUNKING.md). Remaining readers emit whole-file units with `chunk_kind=file_excerpt`. Java symbol units require both `symbol_id` and `signature`, including parameter types to distinguish overloads.

`SourceDocumentFactory` slices units verbatim, prefers complete lines, and splits oversized lines without breaking CRLF or Unicode surrogate pairs. It repeats a small factual context header on each part. The budget includes that header:

```yaml
ingestion:
  chunks:
    max-characters: 1200
    java-max-tokens: 800
  qdrant:
    payload-indexes: true
```

`max-characters` is the full-file character budget for non-Java readers. Java uses a separate conservative token bound including the header and prefers AST statement/declaration boundaries; see [JAVA_CHUNKING.md](JAVA_CHUNKING.md) for approximation and serving-limit validation. A header that leaves insufficient space for source causes an explicit error; increase the budget for repositories with long paths/type names. Whitespace-only sources emit no documents.

## Metadata

The factory preserves applicable discovery/reader fields and adds typed, flat chunk fields:

| Fields | Meaning |
| --- | --- |
| `repository_id`, `module_id`, `relative_path`, `language`, `file_kind`, `source_set` | Portable identity and filtering; no checkout path in the vector payload |
| `revision`, `dirty_worktree`, `revision_status` | Git HEAD and repository-wide status sampled when the repository scan starts. `revision_status` is `available`, `unborn`, `unversioned`, or `unavailable`; unknown values are omitted rather than invented |
| `file_hash` | SHA-256 of the original bytes read, including BOM; captured before redaction |
| `chunk_hash` | SHA-256 of the exact header plus excerpt that is stored and embedded |
| `chunk_id`, `parent_id`, `chunk_kind`, `part_index`, `part_count` | Deterministic UUID point ID, logical parent, retrieval unit kind, and zero-based part number/count |
| `start_line`, `end_line` | Inclusive, one-based lines in the decoded source; header lines never count |
| `start_offset`, `end_offset` | Zero-based UTF-16 character offsets, with an exclusive end, matching Java `String.substring` |
| `parser_version`, `chunker_version`, `document_format_version` | Non-Java: `full-file-reader-v1`, `line-character-v1`; Java: `javaparser-3.28.0-java-reader-v1`, `java-statement-token-v1`; format: `source-document-v1` |
| `parse_status`, `resolution_status`, `generated`, `content_origin`, `location_basis` | Validation/provenance; Java reports `parsed` or `fallback`, and `resolved`, `partial` or `not_attempted` resolution; full-file readers report `validated` for XML/YAML or `text_only` otherwise |

Applicable Java (`package`, `type_fqn`, `symbol_id`, `signature`, `annotations`, `visibility`), XML/SOAP (`namespace_uri`, `element_qname`, `xml_path`, `service`, `port_type`, `operation`, `soap_action`), and Maven fields remain reader-owned. The factory does not fabricate semantic fields. Java AST extraction replaces package hints and adds type/member metadata. Discovery provides root namespaces and declared Maven coordinates. `declared_modules` remains in the discovery inventory and is omitted from individual vector payloads. Relationship facts remain reader-owned metadata; stale relationship reconciliation remains phase 8.

## Identity and parent lookup

Logical file identity uses the configured repository ID, module ID, and portable relative path. A symbol parent additionally uses the overload-specific symbol ID with parameter types for methods/constructors. Java qualifies explicit imports, local types and known `java.lang` types; ambiguous external/wildcard types remain lexical as documented in [JAVA_CHUNKING.md](JAVA_CHUNKING.md). The readable `signature` header can change (for example when a parameter is renamed) without changing symbol identity. Chunk identity uses this parent, chunk kind, and part index. Length-prefixed key components prevent delimiter ambiguity; namespaced Java name-based UUIDs are valid Qdrant point IDs. `Document.id` equals `chunk_id`. Content hashes and checkout directories do not affect logical IDs.

An unchanged rerun uses the same IDs. Content edits update hashes and overwrite the same point IDs for surviving parts. Changed split boundaries can leave obsolete points; removal of obsolete parts, deleted files, and failed-run reconciliation belongs to phase 8. Prefer a fresh collection when migrating an existing random-ID index to this contract.

Offsets and line numbers refer to the decoded source text carried by the current ingestion unit. `parent_id` identifies a logical unit without inferring an enclosing file or symbol from text.

For `location_basis=original_source`, display `repository_id:relative_path:start_line-end_line` with the revision and file hash. For `location_basis=sanitized_snapshot`, label the citation as sanitized content: configuration serialization changes formatting, so these numbers cannot be used to locate the original-file lines. `content_origin=sanitized_source` makes this distinction filterable.

## Embedding and Qdrant behavior

The document text explicitly contains an allowlisted header (repository, module, file, and applicable package/type/signature/annotations, namespace/contract facts, and Maven coordinates), a blank line, and the unmodified source slice. Hashes, timestamps, internal versions, IDs, and relationship lists are excluded from the header. A formatter on newly created documents also returns that text for every metadata mode.

Verified against the installed Spring AI **2.0.1** source JARs: `OllamaEmbeddingModel.embed(Document)` uses `getText()`, and the batch path used by `QdrantVectorStore` extracts text through `EmbeddingModel.getEmbeddingContent`. Qdrant persists `Document.getText()` as `doc_content`, preserves metadata as payload, and requires UUID document IDs. A test exercises the installed Ollama model against a mocked API and asserts that the embedding request is exactly the explicit header plus source. Arbitrary metadata is not automatically embedded. Retrieval consumers should pass `Document.getText()` to the LLM and generate citations from metadata; content formatters are ephemeral and are not restored when Qdrant constructs a retrieved document.

Java ingestion configures `spring.ai.ollama.embedding.truncate=false`. A compatibility adapter preserves this and the configured model when Spring AI 2.0.1 merges Qdrant's generic batch options; see [JAVA_CHUNKING.md](JAVA_CHUNKING.md). The embedding test verifies that this policy reaches the API request.

When both `ingestion.qdrant.payload-indexes=true` and `spring.ai.vectorstore.qdrant.initialize-schema=true`, an ordered startup runner creates keyword indexes for `repository_id`, `module_id`, `language`, and `chunk_kind` in the configured Qdrant collection before ingestion starts. Index requests wait for completion and use a 30-second RPC timeout. If schema initialization is disabled, provision these indexes externally or explicitly enable schema initialization. Tests mock Qdrant; no live Qdrant/Ollama service is required.

Run `./gradlew.bat test` on Windows. Tests cover pipeline composition, exact excerpts and source lines, CRLF/bare-CR/Unicode splitting, overload and split-parent identities, repeated runs and edits, original-byte hashes and Git provenance, sanitized content, embedding text, and payload-index requests.
