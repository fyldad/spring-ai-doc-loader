# Java AST extraction and semantic chunks

Roadmap step 4 is implemented in the existing Spring Cloud Function composition:

```text
fileTreeSupplier -> documentReader -> createDocuments -> vectorStoreWriter
```

Discovery still decodes files and supplies repository/module/revision metadata. `documentReader` parses each indexed Java snapshot once with **JavaParser and JavaSymbolSolver 3.28.0**, then emits multiple `SourceUnit`s. Other file kinds retain the existing full-file readers. Java source is sliced from the decoded snapshot using parser ranges; AST rendering is used only for descriptive metadata, never as the cited excerpt.

## Units and context

| Chunk kind | Content |
| --- | --- |
| `java_file_context` | License/header, package and imports; also package-info/module-info files |
| `java_type_overview` | Original JavaDoc, annotations, declaration, inheritance/permits clause and record components, plus a bounded factual member-signature header |
| `java_method` | Complete method with its JavaDoc and annotations when it fits |
| `java_constructor` | Ordinary or compact record constructor with JavaDoc/annotations |
| `java_fields` | Consecutive related fields, preserving JAXB/validation/serialization annotations and comments |
| `java_enum_constants` | Enum entries and their annotations |
| `java_member` | Annotation members and initializer blocks |
| `java_fallback` | Complete malformed/unsupported source split into bounded, line-aware excerpts |

Named top-level/member classes, interfaces, annotations, records and enums get distinct `type_fqn`s. Local and anonymous declarations remain in their enclosing executable excerpt rather than receiving invented global names. Initializer blocks have distinct ordinal identities.

Metadata includes `package`, `type_fqn`, `type_kind`, `symbol_id`, `signature`, `annotations`, `visibility`, `inheritance`, and `enclosing_type_id` where applicable. Consecutive field groups with different access levels report `visibility=mixed`; interface members account for implicit public visibility. The type overview's `parent_id` equals member chunks' `enclosing_type_id`. Member `parent_id` continues to identify the complete method/field-group snapshot, so all split parts share one parent.

Method and constructor identities include parameter types, arrays and varargs (`[]`), with generic arguments erased. Explicit imports, local member types and known `java.lang` types are qualified without depending on symbol resolution. Parameter names do not affect identity. Wildcard imports and unknown external types remain lexical; the optional resolver supplies additional facts without changing IDs. Fully qualifying a previously ambiguous lexical type can change identity. Complete call graphs, inherited-type disambiguation and effective build classpaths remain later work.

The header shows a bounded member summary and repeats the signature/annotations on split chunks. Long header fields are abbreviated while complete signatures and source remain available in metadata/snapshots. Full imports, inheritance and member-signature lists are retained in a content-addressed `java_context_snapshot` outside Qdrant. Read it with `SourceSnapshotStore.read`; the type overview points to the full signature inventory. Those large imports/member lists are removed from vector payloads.

The existing source contract applies unchanged to citations: offsets refer to the original decoded source, end offsets are exclusive, lines are one-based/inclusive, and generated headers never shift source locations. `source_snapshot` and `parent_snapshot` reproduce the exact indexed source and complete parent excerpt.

## Configuration and budgets

```yaml
ingestion:
  java:
    language-level: JAVA_25
    resolve-symbols: false
    source-roots: []
    dependency-jars: []
  chunks:
    java-max-tokens: 800
    max-characters: 1200 # applies to non-Java full-file chunks
    snapshot-directory: .ingestion/source-snapshots
spring:
  ai:
    ollama:
      embedding:
        model: bge-m3
        truncate: false
```

`java-max-tokens` includes the repeated context header and must be at least 128. It uses `utf8-nfkc-bound-v1`: the larger UTF-8 byte length of the original and NFKC-normalized input, plus two special tokens. This is deliberately pessimistic for byte/subword tokenizers, not an exact BGE token count. English/Russian source, supplementary Unicode, text blocks and normalization expansion are covered by tests. A configured bound of 800 will commonly hold substantially fewer than 800 actual model tokens. Tune 400–800-token experiments against the real model and benchmark; a future exact tokenizer can replace the bound. Other tokenizers with different normalization/special-token policies need validation. No live Ollama token-count measurement or retrieval-quality comparison was performed for this change.

Every generated Java document records `token_counter`, `token_estimate` and `token_budget`, and is checked against the bound. Ollama truncation is explicitly disabled, so a serving-limit mismatch fails visibly instead of silently dropping the end of an excerpt. Keep the configured budget below the deployed model's actual input limit. A header that consumes the entire budget raises an explicit error; increase the budget for long repository paths/type names.

Spring AI 2.0.1's Ollama merger drops provider defaults when Qdrant supplies generic batch options. `OllamaEmbeddingCompatibility` adapts those options before invoking the model, retaining the configured model/truncation policy and other Ollama defaults. Explicit provider options remain caller-controlled. A mocked API test exercises the generic batch path and verifies `bge-m3`, `truncate=false`, the configured context limit and the exact document text. This compatibility adapter is specific to the pinned Spring AI version and should be reviewed on upgrades.

Oversized methods prefer AST statement/block boundaries and retain every character of the parent range across consecutive parts. Consecutive fields split at declaration boundaries. A single statement, annotation or declaration that exceeds the hard bound is split at lines, then safe character boundaries, without separating CRLF or a Unicode surrogate pair. `split_basis` distinguishes semantic boundaries from `line_or_character` cuts. Short methods stay intact. Overlap is zero pending evidence from the retrieval benchmark.

## Resolution and failures

Source-only parsing is the default. To enrich types, enable `resolve-symbols` and explicitly configure local Java source directories and dependency JARs, for example a module's `src/main/java` and already available Maven-cache JARs. Paths are local parser inputs, not payload fields. Repositories are never built, dependencies are never downloaded, and effective Maven/Gradle classpaths are not inferred. JDK types are available through reflection. Source roots use the configured language level; dependency resolution may parse referenced local sources in addition to the indexed snapshot.

The resolver progressively enriches declared inheritance, field types, method return types and parameter types. `resolved_types` contains successful qualified resolutions, and `resolution_status` is `resolved`, `partial`, or `not_attempted` for those declared types; it does not assert that a method's calls or all runtime dependencies were resolved. Missing roots/JARs are recorded in `resolution_diagnostic`; missing types leave the AST chunks searchable with partial resolution. The reader opens JARs through streams so configured JARs are not held open on Windows.

Parse errors, unsupported syntax and extraction failures emit `java_fallback` with `parse_status=fallback`, a bounded `parse_diagnostic`, the original snapshot and accurate locations. Logs identify the affected repository/path. Java failures are recorded on indexed chunks after discovery, so the discovery CSV's `included` status still means accepted by discovery, not successfully parsed/stored.

The parser's language level is configurable using JavaParser enum names. Java 25 fixtures cover records/pattern matching, sealed types and flexible constructor bodies. Preview support is limited to the selected JavaParser release and language-level validator; unsupported/removed features fall back visibly. Test each target repository's preview syntax before relying on semantic extraction.

## Verification

Run `./gradlew.bat test`. Tests cover the real function composition and Java fixtures for overloaded methods, constructors/compact constructors, records, enums, interfaces/nested types, generic/nested parameter identities, JavaDoc/annotations, unique initializer IDs, CRLF/bare-CR/tab/Unicode citations, large-method statement boundaries, very large individual statements, parser fallback, and local source/JAR resolution with missing dependencies. Qdrant and Ollama are mocked; ingestion services are not required.

Chunk identities now differ from the old whole-file Java index. Stale-point reconciliation remains roadmap step 8; use a fresh collection or an explicit existing-index cleanup when migrating.
