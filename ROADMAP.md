# Roadmap: Java project ingestion for LLM retrieval

The goal is to answer questions about Java implementations, SOAP contracts, and project dependencies with relevant source excerpts and precise file/line citations. Prioritize structural chunks and trustworthy context, then measure whether a different embedding model improves retrieval.

## Baseline when this roadmap was written

The project currently uses Spring Cloud Function composition:

`fileTreeSupplier -> documentReader -> splitter -> vectorStoreWriter`

- `FileTreeWalker` recursively discovers files and skips configured directories.
- `application.yaml` includes `.java`, `.xml`, and `.md`. It does **not** include `.wsdl` or `.xsd`.
- `documentReader` reads each file as plain text; `TokenTextSplitter` requests 300-token chunks without understanding Java or XML structure.
- Metadata contains a hardcoded `scope` and a module inferred from Windows path segments. It does not identify source files, symbols, or line ranges.
- Embeddings use Ollama's `bge-m3`; vectors are stored in Qdrant.
- There is no explicit stable chunk identity, ingestion manifest, or stale-chunk cleanup.

The following phases are proposed work; they are not implemented by this document.

## 1. Establish a retrieval benchmark before changing the pipeline

**Priority: first.** Select representative repositories: a Maven reactor with multiple modules, a SOAP service, and code using the newest Java syntax that needs support.

- Create 30–50 realistic questions with manually identified relevant files, symbols, or contract elements. Include exact identifier searches and natural-language questions.
- Cover method behavior, interface implementations, callers, annotations, SOAP request/response types, fault definitions, and Maven dependencies. Include questions whose answers are absent.
- Example questions: “Where is `CustomerPort#getCustomer` implemented?”, “Which request field is mandatory?”, and “Which module declares the SOAP client dependency?”
- Include English and Russian queries if both will be used in practice.
- Save the current chunking and `bge-m3` results as the baseline. Measure Recall@5/10, reciprocal rank of the first relevant result, retrieval latency, indexing throughput, and index size. Record hardware and model versions.
- Keep a held-out subset for the final decision. Compare extraction/chunking changes with the embedding model fixed, then compare models with the corpus and chunks fixed.

**Done when:** a repeatable evaluation command produces a report with per-question results. This requires a small retrieval harness; the current application only loads documents.

## 2. Discover projects and classify files correctly

**Status: implemented.** See [DISCOVERY.md](DISCOVERY.md) for configuration, report semantics, and discovery limits. Java AST parsing is implemented in phase 4; semantic XML extraction remains phase 5.

**Priority: foundational.** Replace the assumption that the first directory segment is the module with an explicit project inventory.

- Configure repository roots and stable repository IDs. Derive portable relative paths with `Path.relativize`, normalizing stored separators to `/`.
- Identify Maven roots, parent/child modules, `src/main/java`, `src/test/java`, and resource roots. Distinguish the repository, Maven module, and Java package.
- Include `.wsdl` and `.xsd`. include `.yaml`, `.yml`, `.properties`.
- Route `pom.xml`, WSDL, XSD, other XML, Java, and prose to separate readers. Detect XML type using its root element and namespace as well as the filename.
- Respect repository ignore rules and configurable exclusions. Keep generated SOAP stubs identifiable; exclude or down-rank them where the authoritative WSDL/XSD already covers the same information.
- Bound file size, handle encoding deliberately, and retain parse/read failure records. Preserve the walker's current avoidance of symlinks unless following them is explicitly needed.
- Filter credentials and sensitive configuration values before indexing configuration files.

**Done when:** a discovery report lists each file's repository, module, source set, file kind, and inclusion/exclusion reason; WSDL/XSD fixtures are included.

## 3. Introduce a source and metadata contract

**Status: implemented.** See [SOURCE_CONTRACT.md](SOURCE_CONTRACT.md) for structured units, stable identities, source snapshots, location semantics, document text, and payload indexes. Java readers now extract AST units; semantic XML fields remain phase 5. Configuration locations explicitly refer to sanitized snapshots when redaction changes formatting. Stale-part reconciliation remains phase 8.

**Priority: before AST readers.** Separate an extracted source unit from the final Spring AI `Document`. Readers should return structured units; a shared document factory should create consistently formatted retrieval chunks.

Recommended fields, populated only where applicable:

| Group | Fields | Purpose |
| --- | --- | --- |
| Source identity | `repository_id`, `module_id`, `relative_path`, `language`, `file_kind`, `source_set` | Filtering and source attribution |
| Revision | `revision`, `file_hash`, `chunk_hash`, `dirty_worktree` | Identify exactly which content was indexed |
| Chunk identity | `chunk_id`, `parent_id`, `chunk_kind`, `part_index` | Deduplication and parent/child retrieval |
| Source location | `start_line`, `end_line`, optionally character offsets | Accurate source excerpts and citations |
| Java | `package`, `type_fqn`, `symbol_id`, `signature`, `annotations`, `visibility` | Symbol lookup and meaningful context |
| XML/SOAP | `namespace_uri`, `element_qname`, `xml_path`, `service`, `port_type`, `operation`, `soap_action` | Contract lookup independent of namespace prefixes |
| Maven | `group_id`, `artifact_id`, `version`, `packaging`, `dependency_scope` | Module and dependency questions |
| Quality/provenance | `parser_version`, `chunker_version`, `parse_status`, `resolution_status`, `generated`, `content_origin` | Reproducibility and filtering |

Use a stable repository/module/path/symbol key for logical identity, with separate content hashes for change detection. Include the method signature in Java symbol IDs to distinguish overloads. For split symbols, track the part index and reconcile the previous chunk set when boundaries change. If IDs are sent directly to Qdrant, encode them as a supported point ID, such as a deterministic UUID.

Keep commonly filtered fields flat and typed. Add Qdrant payload indexes for actual filter needs such as repository, module, language, and chunk kind. Store large relationship lists and original source snapshots outside each chunk's metadata.

**Metadata and text have different jobs.** Explicitly construct the embedding input so the model sees a short context header followed by the source. Do not assume arbitrary metadata is embedded or passed to the answering LLM. Verify Spring AI's document formatting and embedding behavior for the installed version.

Example of proposed embedding/LLM text:

```text
Repository: customer-services
Module: customer-soap
File: src/main/java/com/example/customer/CustomerEndpoint.java
Type: com.example.customer.CustomerEndpoint
Method: CustomerResponse getCustomer(CustomerRequest request)
Annotations: @Endpoint, @PayloadRoot(namespace="urn:customer", localPart="GetCustomerRequest")
Related contract: {urn:customer}GetCustomerRequest

<original JavaDoc and method source, verbatim>
```

Keep hashes, ingestion timestamps, and internal bookkeeping out of the embedding text. Generate citations from stored locations; header lines must not shift the reported source ranges.

**Done when:** a retrieved document carries enough information to display its origin, read the exact source, and locate its parent without guessing.

## 4. Add Java AST parsing and semantic chunking

**Status: implemented.** See [JAVA_CHUNKING.md](JAVA_CHUNKING.md) for the pinned parser, semantic units, token bounds, citation semantics, optional local symbol resolution, fallback diagnostics, and remaining resolution limits. Retrieval-quality tuning and model comparisons still require the benchmark.

**Priority: main improvement for Java.** Start with JavaParser and add JavaSymbolSolver for relationship resolution. The [JavaParser project](https://github.com/javaparser/javaparser) documents AST parsing, symbol resolution, and Java support through Java 25. Pin a release and test the specific syntax used by target repositories, including preview features if needed.

- Parse each compilation unit once. Extract package/imports, types, interfaces, records, enums, constructors, methods, fields, annotations, inheritance, and JavaDoc.
- Emit a type overview with its declaration, inheritance, field signatures, and member signatures. Emit separate chunks for methods and constructors, keeping their JavaDoc and annotations with them.
- Keep small related declarations together, especially DTO fields and annotations such as JAXB mappings, validation constraints, and serialization names.
- Slice verbatim source using parser ranges; avoid pretty-printing as the only retained source because it changes formatting and citation positions.
- Start experiments around 400–800 tokens per semantic chunk, with a configurable hard limit below the embedding model's input limit. Count the context header too. These are tuning candidates, not fixed optimal values.
- Keep a complete short method in one chunk. Split oversized methods at statement/block boundaries, repeat a small signature/context header, and link all parts to their parent. Apply bounded overlap only where it improves the benchmark.
- Count tokens with the deployed model's tokenizer or a validated conservative approximation. Check for truncation; do not assume the current splitter's token estimate matches every embedding model.
- On parse failure, emit line-aware fallback chunks with `parse_status=fallback` and a diagnostic. Never silently lose the whole file.

Add symbol resolution progressively: source-only parsing first, source roots and local dependency JARs next, richer build/classpath integration later. Missing dependencies should reduce resolution quality without blocking ingestion. Do not build every repository merely to obtain its AST.

**Done when:** representative methods, overloaded methods, records, nested types, and large methods produce useful chunks with correct source ranges. Failed parsing remains visible and searchable.

## 5. Add structure-aware XML, WSDL, XSD, and Maven readers

**Priority: alongside Java AST work.** XML token windows can separate an operation from the types that explain it. Use namespace-aware parsing and semantic extraction.

- Configure XML readers to disable DTDs and external entity expansion. Resolve needed WSDL/XSD imports through an explicit resolver confined to configured repository roots or an approved local catalog; do not let parsing fetch arbitrary URLs.
- Preserve namespace URIs and qualified names. Prefixes such as `tns` are aliases and are not stable identities.
- Use an XML reader with source-location tracking and preserve original text. Plain DOM parsing alone should not be assumed to provide reliable element line ranges.

| File kind | Retrieval units | Useful context |
| --- | --- | --- |
| WSDL | Service overview and individual operations | Port/portType, binding, input/output messages, faults, SOAPAction, referenced schema elements |
| XSD | Global element, complex type, simple type | Namespace, base type, fields, occurrence bounds, restrictions, documentation, imported types |
| `pom.xml` | Module overview, dependency groups, plugin/configuration blocks, profiles | Coordinates, parent, modules, properties, dependency management, declared scopes and versions |
| Other XML | Meaningful configured elements with a fallback reader | Namespace, element path, ID/name attributes, source excerpt |

- Detect the WSDL version; start with the version used by the corpus. Record unsupported versions rather than interpreting them as WSDL 1.1.
- For each SOAP operation, include a compact factual description of request, response, and fault references. Resolve related XSD declarations as linked chunks instead of recursively copying whole schemas into every operation. Bound traversal and detect import cycles.
- Preserve XSD optionality, multiplicity, restrictions, and namespace qualification because these affect how clients construct requests.
- Link Java service interfaces, implementations, JAXB DTOs, and contract elements using annotations and resolved symbols where possible. Label name-based matches as inferred.
- Distinguish declared Maven dependencies from effective dependencies. Resolve parents, properties, profiles, BOMs, and dependency management only with an explicit resolution policy. Record unresolved placeholders and the profiles/environment used for effective-model results.

**Done when:** retrieval can connect an operation to its request/response types and Java implementation, and distinguish a declared dependency from a resolved version, with source citations.

## 6. Benchmark embeddings and choose a deployment profile

**Priority: after a usable structural baseline.** Keep `bge-m3` as the control; replace it only if measured results justify the deployment cost.

| Candidate | Why evaluate it | Constraints to verify |
| --- | --- | --- |
| Current `bge-m3` | Existing local baseline; multilingual retrieval | Quantization, actual serving limit, query/document formatting, throughput |
| Qwen3-Embedding-0.6B | Small initial candidate with documented code and multilingual retrieval support | Serving compatibility, query instructions, memory, latency |
| Larger Qwen3 embedding variant | Measure whether more capacity improves difficult questions | Hardware budget and improvement over the smaller model |
| A hosted code embedding model, if external processing is allowed | Optional comparison against a model targeting code retrieval | Source-code handling policy, cost, retention, rate limits, provider integration |

The [BGE-M3 model card](https://huggingface.co/BAAI/bge-m3) describes dense, sparse, and multi-vector modes. The current Ollama/Spring AI dense embedding path should not be assumed to expose all of them. The [Qwen3 embedding model card](https://huggingface.co/Qwen/Qwen3-Embedding-0.6B) documents code retrieval and query-side instructions; benchmark with the correct query/document formatting. A published context limit is not a reason to embed entire files.

- Verify model availability and behavior in the selected runtime. Add an alternate embedding provider adapter if needed rather than assuming every model can be selected by changing an Ollama tag.
- Evaluate code, SOAP, Maven, exact symbols, and multilingual queries separately. Measure truncation, vector dimensions, memory, indexing speed, query latency, and quality.
- Compare raw source against source plus the factual context header. Test generated summaries later as a separate experiment.
- Persist the model identity/revision or digest, dimensions, formatting policy, and preprocessing version in an index manifest. Use the same compatible embedding configuration for queries and documents.
- Create a separate collection for a model migration. Re-embed the corpus, evaluate it, and switch the query configuration/alias after validation. Equal vector dimensions do not make different embedding spaces compatible. Retain the previous collection for rollback.

**Done when:** a report selects a model using the held-out questions and an explicit latency/hardware budget. “Keep `bge-m3`” is a valid result.

## 7. Improve retrieval and assemble useful LLM context

**Priority: after structural ingestion; requires a retrieval component.** Loading better vectors alone does not implement these behaviors.

- Combine dense similarity with lexical retrieval for class names, method names, error messages, XML qualified names, and Maven coordinates. Preserve exact identifiers and also tokenize camelCase for natural-language matching.
- Use Qdrant sparse/dense retrieval or a separate lexical index, depending on the integration available in the pinned dependencies. Treat Spring AI support as something to verify; a generic `VectorStore` call does not automatically provide hybrid search.
- Fuse candidate rankings, then benchmark an optional reranker on a bounded candidate set. Apply repository/module filters consistently to both branches.
- Retrieve specific method/operation chunks, then add bounded parent context: the enclosing type, interface, relevant DTO/schema definitions, or neighboring chunks. Avoid flooding the prompt with repeated headers or generated duplicates.
- Keep a lightweight relationship store for `declares`, `implements`, `extends`, `calls`, `uses_type`, `maps_to_operation`, and `depends_on`. A graph database is optional; tables or adjacency records are enough initially.
- Preserve unresolved and inferred relationships. Static call resolution is incomplete around reflection, dependency injection, proxies, and dynamic dispatch; never present it as a complete runtime call graph.
- Pass readable source headers and citations to the answering model within an explicit token budget. Treat repository text as evidence, not instructions. When the index lacks evidence, report that limitation.

**Done when:** the retrieval benchmark improves on exact-symbol and multi-file questions, and every displayed excerpt is traceable to its indexed source revision.

## 8. Make ingestion incremental and recoverable

**Priority: before routine use on large repositories.** Introduce this after stable IDs exist; it can proceed alongside retrieval work.

- Maintain a manifest of files, hashes, emitted chunk IDs, extraction versions, and completed runs. Skip unchanged files only when the parser, enrichment, chunker, and embedding configuration are also unchanged.
- Recompute dependent metadata/context when POMs, schemas, or resolved relationships change; file-local hashes alone do not capture those effects.
- Upsert new chunks and remove obsolete chunks after successful replacement. Handle edits, deletions, renames, and changed split boundaries.
- Reconcile deletions only after a complete successful scan. An unreadable subtree or failed parse must not look like an intentional deletion. Mark retained stale content explicitly or exclude it from active retrieval.
- Choose and document a consistency policy. Per-file replacement is simpler but can briefly expose mixed state; use a staging collection and switch after success when repository-wide consistency is required.
- Separate embedding request size from Qdrant write size. Bound batches by both item count and token count, and bound concurrency to local model capacity.
- Add bounded retries/backoff for transient model/store failures. Record permanent failures with repository, path, stage, and cause, and support resuming failed work.
- Expose a run result with discovered/parsed/skipped/failed files, emitted/stored/deleted chunks, timings, and model errors. Decide whether completion means a batch process exits or a service publishes job status; the current startup subscription does not supply that full job contract.

**Done when:** an unchanged rerun creates no duplicates, an edit removes superseded chunks, a deletion removes only the intended source, and an interrupted run can recover.

## 9. Add optional enrichment only after measuring its value

- Start with deterministic summaries: type purpose from JavaDoc, public API signatures, SOAP operation/type references, and Maven module/dependency summaries.
- Experiment with LLM-generated class or module summaries when relevant behavior spans several methods. Store them as separate derived documents linked to their sources.
- Record `content_origin=generated_summary`, generating model/prompt version, and source hashes. Invalidate summaries when any contributing source changes.
- Evaluate hallucinations and retrieval benefit. Keep verbatim source available for verification, and include the additional generation cost in the decision.
- Defer embedding fine-tuning until the benchmark exposes persistent failures and enough high-quality query/source training pairs exist.

**Done when:** enrichment improves held-out retrieval or answer quality enough to justify its cost and maintenance.

## Suggested implementation sequence

1. **First milestone:** benchmark, project inventory, WSDL/XSD inclusion, metadata contract, and stable IDs.
2. **Second milestone:** Java AST reader and WSDL/XSD/POM readers with semantic chunks, factual context headers, and citation tests.
3. **Third milestone:** embedding comparison, a versioned index configuration, and incremental/resumable ingestion.
4. **Fourth milestone:** hybrid retrieval, parent/relationship expansion, and optional reranking.
5. **Later experiments:** generated summaries, deeper dependency/call analysis, and fine-tuning if justified.

Keep the Spring Cloud Function composition, but evolve its stages toward:

`discover -> classify -> parse -> enrich -> chunk -> createDocuments -> embed/store -> reconcile`

Parsing should produce source units with locations and relationships. Enrichment should provide bounded factual context. Chunking should respect language structure. Run orchestration should coordinate manifests and reconciliation around the stream. Make parser choice, token budgets, exclusions, model profile, collection version, and batching configurable rather than hardcoded in `SpringAiDocLoaderApplication`.

Validate with a small fixture repository containing Java, a Maven reactor, WSDL imports, XSD types, generated stubs, and malformed inputs. Test source ranges, overload identity, namespace/import resolution, parse fallback, unchanged reruns, edits/deletions, and recovery. Use the retrieval benchmark to justify quality changes; document-count tests alone cannot demonstrate useful retrieval.
