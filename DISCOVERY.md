# Project discovery configuration

Section 2 of `ROADMAP.md` is implemented by the discovery supplier and reader routes. Files are classified and safely decoded before becoming Spring AI documents; the existing token splitter then processes them.

Prefer explicit repository IDs and roots in `application.yaml`:

```yaml
file:
  supplier:
    repositories:
      - id: customer-services
        root: C:\projects\customer-services
      - id: shared-contracts
        root: C:\projects\shared-contracts
    filename-regex: (?i).*\.(java|xml|wsdl|xsd|md|txt|yaml|yml|properties)
    excluded-directories: .git,.gradle,.idea,build,target,node_modules
    excluded-paths:
      - private/**
      - '**/application-local.properties'
    respect-gitignore: true
    exclude-generated: false
    max-file-size: 2097152
    encoding: UTF-8
    report: build/reports/discovery.csv
```

IDs are independent of checkout location. Roots must be existing directories, cannot be symlinks, and must not overlap. `relative_path` uses `/` on every platform. When `repositories` is absent, the legacy `directory` option accepts a project root or a directory whose immediate children are project roots. Legacy IDs come from directory names; use explicit IDs to keep identity stable across renames. Root-level loose files in a folder of projects are not assigned to its child repositories.

Maven module identity uses the nearest included `pom.xml`, with a repository-relative module path (`.` for the root). Metadata includes declared coordinates, inherited parent group/version when present, declared child modules, and the containing parent module. Build source/resource roots and standard `src/main/java`, `src/test/java`, `src/main/resources`, and `src/test/resources` are classified separately from Java package names. Package extraction is a lightweight source-header reader; AST analysis is a later roadmap phase. Maven profiles, remote parents, unresolved properties, and effective dependencies are not evaluated during discovery. `inventory_status` describes whether a POM was usable, and unresolved coordinates remain declared values.

POM, WSDL, XSD, generic XML, Java, YAML, properties, and prose have separate reader routes. XML classification considers the root name and namespace, so a WSDL stored as `.xml` receives the WSDL route. Both WSDL 1.1 and 2.0 roots are recognized. XML DTDs/external entities are disabled, and no schema imports are fetched. Readers currently retain full-file text; semantic Java/contract chunks remain later roadmap work.

Repository `.gitignore` files, nested overrides/negations, and local `.git/info/exclude` rules are applied with JGit. Existing Git index entries remain eligible even when they match ignore patterns. Machine-wide Git ignore settings are deliberately excluded so ingestion is reproducible across hosts. Configured directory/path exclusions still apply to tracked files. Excluded directories are pruned, and symlinks are skipped. Generated source paths, `@Generated` annotations, and generator comment markers produce `generated=true`; `exclude-generated` can suppress those files. Generated-source detection is heuristic and does not affect ranking automatically.

File-size limits apply to the actual read as well as discovery attributes. Decoding is strict: UTF BOMs and XML encoding declarations override the configured fallback charset. Java/properties/prose without a BOM use the fallback charset. Invalid encoding, malformed XML/YAML, unsafe XML declarations, and binary/NUL content are recorded as failures while scanning continues.

YAML, properties, POM, and other configuration XML are redacted before document creation. Sensitive keys, named XML properties/entries, URL credentials/query tokens, and PEM private keys are masked. YAML is safely parsed, with bounded aliases/nesting/expansion, and recursive values are rejected. Configuration serialization may change formatting and removes configuration comments; source-line mapping is a later roadmap concern. WSDL/XSD and Java source remain verbatim. This is key/value redaction, not general secret detection in arbitrary source or prose; exclude sensitive files and extend `sensitive-key-pattern` for application-specific key names.

The UTF-8 CSV report contains repository, module, relative path, source set, file kind, generated status, and inclusion/exclusion/failure reason. A pruned directory has one row representing its entire subtree, since excluded contents are not opened. Excluded/unreadable files use the filename kind as a hint; successfully read XML uses namespace classification. Failure reasons contain exception types, never parser messages or file contents. `included` means accepted by discovery/readers, not confirmation that embedding or storage succeeded. Each subscription replaces the report; use separate report paths for concurrent ingestion processes. Cancellation closes directory and report handles.

Set `file.supplier.enabled=false` to disable ingestion on application startup. Fixture and context tests use this option to avoid indexing developer checkouts or contacting embedding/vector services.
