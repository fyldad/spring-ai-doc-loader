package ru.anblazhnov.springaidocloader;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Parses the indexed snapshot once; AST nodes are used only to select original source ranges. */
final class JavaSourceReader {
    static final String VERSION = "javaparser-3.28.0-java-reader-v1";
    private static final Logger log = LoggerFactory.getLogger(JavaSourceReader.class);
    private final JavaParser parser;
    private final ReflectionTypeSolver jdkTypes = new ReflectionTypeSolver();
    private final JavaParsingProperties properties;
    private final int contextBudget;
    private final List<String> resolverDiagnostics = new ArrayList<>();

    JavaSourceReader(JavaParsingProperties properties, ChunkingProperties chunks) {
        this.properties = properties;
        chunks.validate();
        contextBudget = Math.max(32, chunks.getJavaMaxTokens() / 4);
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.valueOf(properties.getLanguageLevel()))
                .setTabSize(1);
        if (properties.isResolveSymbols()) {
            CombinedTypeSolver solver = new CombinedTypeSolver(new ReflectionTypeSolver());
            for (Path root : properties.getSourceRoots()) {
                try { solver.add(new JavaParserTypeSolver(root, new ParserConfiguration()
                        .setLanguageLevel(configuration.getLanguageLevel()).setTabSize(1))); }
                catch (RuntimeException error) { resolverDiagnostics.add("source_root_unavailable: " + root.getFileName()); }
            }
            for (Path jar : properties.getDependencyJars()) {
                // The InputStream constructor avoids Javassist holding the configured JAR open on Windows.
                try (var input = Files.newInputStream(jar)) { solver.add(new JarTypeSolver(input)); }
                catch (Exception error) { resolverDiagnostics.add("dependency_jar_unavailable: " + jar.getFileName()); }
            }
            configuration.setSymbolResolver(new JavaSymbolSolver(solver));
        }
        parser = new JavaParser(configuration);
    }

    synchronized List<SourceUnit> read(DiscoveredFile file) {
        try {
            var result = parser.parse(file.text());
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                String diagnostics = result.getProblems().stream().map(problem -> problem.getVerboseMessage())
                        .collect(Collectors.joining("; "));
                return fallback(file, diagnostics);
            }
            return extract(file, result.getResult().orElseThrow());
        }
        catch (RuntimeException error) {
            return fallback(file, error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private List<SourceUnit> extract(DiscoveredFile file, CompilationUnit compilation) {
        Offsets offsets = new Offsets(file.text());
        Map<String, Object> base = new LinkedHashMap<>(file.metadata());
        base.put("parser_version", VERSION);
        base.put("parse_status", "parsed");
        base.put("java_language_level", properties.getLanguageLevel());
        base.put("resolution_status", properties.isResolveSymbols() ? "partial" : "not_attempted");
        base.put("semantic_java", true);
        base.remove("package");
        compilation.getPackageDeclaration().ifPresent(declaration -> base.put("package", declaration.getNameAsString()));
        List<String> imports = compilation.getImports().stream().map(Object::toString).map(String::strip).toList();
        base.put("imports", imports);
        if (!resolverDiagnostics.isEmpty()) base.put("resolution_diagnostic", String.join("; ", resolverDiagnostics));
        List<SourceUnit> units = new ArrayList<>();
        // Retain license/package/import context as a separate searchable unit.
        int firstType = compilation.getTypes().stream().mapToInt(type -> offsets.start(type, true)).min().orElse(file.text().length());
        if (firstType > 0) units.add(new SourceUnit(file.text(), 0, firstType, "java_file_context", base));
        for (TypeDeclaration<?> type : compilation.findAll(TypeDeclaration.class)) {
            // Local/anonymous declarations stay in their enclosing executable excerpt.
            if (!isMemberType(type)) continue;
            String fqn = type.getFullyQualifiedName().orElseGet(() -> qualifiedName(type, base));
            Map<String, Object> typeMetadata = metadata(base, type, fqn, fqn, type.getNameAsString());
            typeMetadata.put("type_kind", type instanceof RecordDeclaration ? "record" : type instanceof EnumDeclaration ? "enum"
                    : type instanceof AnnotationDeclaration ? "annotation" : ((ClassOrInterfaceDeclaration) type).isInterface() ? "interface" : "class");
            List<String> inherited = new ArrayList<>();
            if (type instanceof ClassOrInterfaceDeclaration declaration) {
                declaration.getExtendedTypes().forEach(value -> inherited.add("extends " + value));
                declaration.getImplementedTypes().forEach(value -> inherited.add("implements " + value));
            }
            if (type instanceof RecordDeclaration declaration) declaration.getImplementedTypes().forEach(value -> inherited.add("implements " + value));
            if (type instanceof EnumDeclaration declaration) declaration.getImplementedTypes().forEach(value -> inherited.add("implements " + value));
            if (!inherited.isEmpty()) typeMetadata.put("inheritance", inherited);
            List<Type> superTypes = new ArrayList<>();
            if (type instanceof ClassOrInterfaceDeclaration declaration) {
                superTypes.addAll(declaration.getExtendedTypes());
                superTypes.addAll(declaration.getImplementedTypes());
            }
            if (type instanceof RecordDeclaration declaration) superTypes.addAll(declaration.getImplementedTypes());
            if (type instanceof EnumDeclaration declaration) superTypes.addAll(declaration.getImplementedTypes());
            resolve(superTypes, typeMetadata);
            List<String> signatures = type.getMembers().stream().map(member -> memberSignature(member, file.text(), offsets)).toList();
            if (type instanceof RecordDeclaration record) {
                signatures = new ArrayList<>(signatures);
                signatures.addFirst("components: " + record.getParameters());
            }
            typeMetadata.put("member_signatures", signatures);
            if (!signatures.isEmpty()) typeMetadata.put("overview", ConservativeTokenBudget.abbreviate(String.join("; ", signatures), contextBudget));
            int declarationEnd = declarationEnd(type, offsets);
            units.add(new SourceUnit(file.text(), offsets.start(type, true), declarationEnd, "java_type_overview", typeMetadata));
            List<FieldDeclaration> fields = new ArrayList<>();
            int initializerIndex = 0;
            for (BodyDeclaration<?> member : type.getMembers()) {
                if (member instanceof FieldDeclaration field) { fields.add(field); continue; }
                flushFields(fields, units, file, offsets, typeMetadata, fqn);
                if (member instanceof CallableDeclaration<?> callable) {
                    String name = callable instanceof ConstructorDeclaration ? "<init>" : callable.getNameAsString();
                    String parameters = callable.getParameters().stream()
                            .map(parameter -> canonical(parameter.getType(), compilation) + (parameter.isVarArgs() ? "[]" : ""))
                            .collect(Collectors.joining(","));
                    String symbol = fqn + "#" + name + "(" + parameters + ")";
                    Map<String, Object> metadata = metadata(typeMetadata, callable, fqn, symbol, memberSignature(member, file.text(), offsets));
                    List<Type> usedTypes = new ArrayList<>(callable.getParameters().stream().map(Parameter::getType).toList());
                    if (callable instanceof MethodDeclaration method) usedTypes.add(method.getType());
                    resolve(usedTypes, metadata);
                    units.add(unit(file, member, offsets, callable instanceof ConstructorDeclaration ? "java_constructor" : "java_method", metadata));
                }
                else if (member instanceof CompactConstructorDeclaration constructor) {
                    String parameters = ((RecordDeclaration) type).getParameters().stream().map(parameter -> canonical(parameter.getType(), compilation))
                            .collect(Collectors.joining(","));
                    var metadata = metadata(typeMetadata, constructor, fqn, fqn + "#<init>(" + parameters + ")", memberSignature(member, file.text(), offsets));
                    units.add(unit(file, member, offsets, "java_constructor", metadata));
                }
                else if (!(member instanceof TypeDeclaration<?>)) {
                    String identity = member instanceof InitializerDeclaration initializer
                            ? (initializer.isStatic() ? "<clinit>" : "<initializer>") + "[" + initializerIndex++ + "]"
                            : member instanceof AnnotationMemberDeclaration annotation ? annotation.getNameAsString() + "()"
                            : memberSignature(member, file.text(), offsets);
                    var metadata = metadata(typeMetadata, member, fqn, fqn + "#" + identity, memberSignature(member, file.text(), offsets));
                    units.add(unit(file, member, offsets, "java_member", metadata));
                }
            }
            flushFields(fields, units, file, offsets, typeMetadata, fqn);
            if (type instanceof EnumDeclaration enumeration && !enumeration.getEntries().isEmpty()) {
                var entries = enumeration.getEntries();
                var metadata = metadata(typeMetadata, type, fqn, fqn + "#enum_constants", "enum constants");
                units.add(new SourceUnit(file.text(), offsets.start(entries.get(0), true), offsets.end(entries.get(entries.size() - 1)),
                        "java_enum_constants", metadata, entries.stream().map(offsets::end).toList()));
            }
        }
        if (units.isEmpty() && !file.text().isBlank()) units.add(new SourceUnit(file.text(), 0, file.text().length(), "java_file_context", base));
        return List.copyOf(units);
    }

    private void flushFields(List<FieldDeclaration> fields, List<SourceUnit> units, DiscoveredFile file,
            Offsets offsets, Map<String, Object> base, String fqn) {
        if (fields.isEmpty()) return;
        String names = fields.stream().flatMap(field -> field.getVariables().stream()).map(variable -> variable.getNameAsString()).collect(Collectors.joining(","));
        var metadata = metadata(base, fields.getFirst(), fqn, fqn + "#fields(" + names + ")", "fields: " + names);
        List<String> visibilities = fields.stream().map(field -> visibility(field)).distinct().toList();
        metadata.put("visibility", visibilities.size() == 1 ? visibilities.getFirst() : "mixed");
        metadata.put("annotations", fields.stream().flatMap(field -> field.getAnnotations().stream()).map(Object::toString).distinct().toList());
        resolve(fields.stream().flatMap(field -> field.getVariables().stream()).map(variable -> variable.getType()).toList(), metadata);
        units.add(new SourceUnit(file.text(), offsets.start(fields.getFirst(), true), offsets.end(fields.getLast()), "java_fields", metadata,
                fields.stream().map(offsets::end).toList()));
        fields.clear();
    }

    private SourceUnit unit(DiscoveredFile file, Node node, Offsets offsets, String kind, Map<String, Object> metadata) {
        List<Integer> boundaries = new ArrayList<>();
        node.findAll(Statement.class).forEach(statement -> {
            boundaries.add(offsets.start(statement, false));
            boundaries.add(offsets.end(statement));
        });
        boundaries.add(offsets.end(node));
        return new SourceUnit(file.text(), offsets.start(node, true), offsets.end(node), kind, metadata, boundaries);
    }

    private Map<String, Object> metadata(Map<String, Object> base, Node node, String fqn, String symbol, String signature) {
        Map<String, Object> metadata = new LinkedHashMap<>(base);
        metadata.remove("overview");
        metadata.remove("member_signatures");
        metadata.put("type_fqn", fqn);
        metadata.put("symbol_id", symbol);
        String fileId = SourceIdentity.uuid("file", String.valueOf(base.get("repository_id")),
                String.valueOf(base.get("module_id")), String.valueOf(base.get("relative_path")));
        metadata.put("enclosing_type_id", SourceIdentity.uuid("symbol", fileId, fqn));
        metadata.put("signature", signature);
        metadata.remove("annotations");
        if (node instanceof NodeWithAnnotations<?> annotated && !annotated.getAnnotations().isEmpty()) {
            metadata.put("annotations", annotated.getAnnotations().stream().map(Object::toString).toList());
        }
        metadata.put("visibility", visibility(node));
        return metadata;
    }

    private String visibility(Node node) {
        String visibility = "package_private";
        if (node instanceof NodeWithModifiers<?> modified) {
            for (var modifier : modified.getModifiers()) {
                if (List.of("public", "protected", "private").contains(modifier.getKeyword().asString())) visibility = modifier.getKeyword().asString();
            }
        }
        if (visibility.equals("package_private") && node.getParentNode().orElse(null) instanceof TypeDeclaration<?> enclosing) {
            if (enclosing instanceof AnnotationDeclaration || enclosing instanceof ClassOrInterfaceDeclaration declaration && declaration.isInterface()) return "public";
            if (node instanceof CompactConstructorDeclaration) return visibility(enclosing);
        }
        return visibility;
    }

    private void resolve(List<Type> types, Map<String, Object> metadata) {
        if (!properties.isResolveSymbols()) return;
        metadata.remove("resolved_types");
        List<String> resolved = new ArrayList<>();
        boolean failed = !resolverDiagnostics.isEmpty();
        for (Type type : types) {
            try { resolved.add(type.resolve().describe()); }
            catch (RuntimeException error) { failed = true; }
        }
        metadata.put("resolution_status", failed ? "partial" : "resolved");
        if (!resolved.isEmpty()) metadata.put("resolved_types", resolved);
    }

    // Explicit imports qualify identities even without a classpath. Wildcard/unknown types stay lexical.
    private String canonical(Type type, CompilationUnit compilation) {
        if (type.isArrayType()) return canonical(type.asArrayType().getComponentType(), compilation) + "[]";
        if (type instanceof ClassOrInterfaceType reference) {
            String name = reference.getNameWithScope();
            String first = name.split("\\.")[0];
            Node parent = type;
            while (parent != null) {
                if (parent instanceof NodeWithTypeParameters<?> generic && generic.getTypeParameters().stream().anyMatch(parameter -> parameter.getNameAsString().equals(first))) return name;
                if (parent instanceof TypeDeclaration<?> enclosing) {
                    if (enclosing.getNameAsString().equals(first)) return enclosing.getFullyQualifiedName().orElse(first) + name.substring(first.length());
                    for (var member : enclosing.getMembers()) {
                        if (member instanceof TypeDeclaration<?> nested && nested.getNameAsString().equals(first)) return nested.getFullyQualifiedName().orElse(first) + name.substring(first.length());
                    }
                }
                parent = parent.getParentNode().orElse(null);
            }
            for (var declaration : compilation.getTypes()) {
                if (declaration.getNameAsString().equals(first)) return declaration.getFullyQualifiedName().orElse(first) + name.substring(first.length());
            }
            for (var imported : compilation.getImports()) {
                if (!imported.isAsterisk() && !imported.isStatic() && imported.getName().getIdentifier().equals(first)) {
                    return imported.getNameAsString() + name.substring(first.length());
                }
            }
            if (!name.contains(".") && jdkTypes.tryToSolveType("java.lang." + name).isSolved()) return "java.lang." + name;
            // Generic arguments are erased: they cannot distinguish legal Java overloads.
            return name;
        }
        return type.asString().replaceAll("\\s+", "");
    }

    private String memberSignature(BodyDeclaration<?> member, String source, Offsets offsets) {
        if (member instanceof FieldDeclaration field) return field.getVariables().stream()
                .map(variable -> variable.getType() + " " + variable.getNameAsString()).collect(Collectors.joining(", "));
        int end = offsets.end(member);
        if (member instanceof MethodDeclaration method && method.getBody().isPresent()) end = offsets.start(method.getBody().orElseThrow(), false);
        else if (member instanceof ConstructorDeclaration constructor) end = offsets.start(constructor.getBody(), false);
        else if (member instanceof CompactConstructorDeclaration constructor) end = offsets.start(constructor.getBody(), false);
        else if (member instanceof TypeDeclaration<?> declaration) end = declarationEnd(declaration, offsets);
        else if (member instanceof InitializerDeclaration initializer) end = offsets.start(initializer.getBody(), false);
        return source.substring(offsets.start(member, false), end).strip().replaceAll("\\s+", " ");
    }

    private int declarationEnd(TypeDeclaration<?> type, Offsets offsets) {
        int parentheses = 0;
        for (var token : type.getTokenRange().orElseThrow()) {
            if (token.getText().equals("(")) parentheses++;
            if (token.getText().equals(")")) parentheses--;
            if (token.getText().equals("{") && parentheses == 0) return offsets.at(token.getRange().orElseThrow().end) + 1;
        }
        return offsets.end(type);
    }

    private String qualifiedName(TypeDeclaration<?> type, Map<String, Object> base) {
        String name = type.getNameAsString();
        Node parent = type.getParentNode().orElse(null);
        while (parent != null) {
            if (parent instanceof TypeDeclaration<?> enclosing) name = enclosing.getNameAsString() + "." + name;
            parent = parent.getParentNode().orElse(null);
        }
        return base.containsKey("package") ? base.get("package") + "." + name : name;
    }

    private boolean isMemberType(TypeDeclaration<?> type) {
        Node parent = type.getParentNode().orElse(null);
        while (parent instanceof TypeDeclaration<?>) parent = parent.getParentNode().orElse(null);
        return parent instanceof CompilationUnit;
    }

    private List<SourceUnit> fallback(DiscoveredFile file, String diagnostic) {
        Map<String, Object> metadata = new LinkedHashMap<>(file.metadata());
        metadata.put("parser_version", VERSION);
        metadata.put("parse_status", "fallback");
        metadata.put("resolution_status", "not_attempted");
        metadata.put("semantic_java", true);
        metadata.put("parse_diagnostic", ConservativeTokenBudget.abbreviate(diagnostic.isBlank() ? "Parser produced no compilation unit" : diagnostic, 512));
        log.warn("Java parse fallback for {}:{}", metadata.get("repository_id"), metadata.get("relative_path"));
        return List.of(new SourceUnit(file.text(), 0, file.text().length(), "java_fallback", metadata));
    }

    private static final class Offsets {
        private final List<Integer> lines = new ArrayList<>();
        Offsets(String source) {
            lines.add(0);
            for (int index = 0; index < source.length(); index++) {
                if (source.charAt(index) == '\r') {
                    if (index + 1 < source.length() && source.charAt(index + 1) == '\n') index++;
                    lines.add(index + 1);
                }
                else if (source.charAt(index) == '\n') lines.add(index + 1);
            }
        }
        int at(Position position) { return lines.get(position.line - 1) + position.column - 1; }
        int start(Node node, boolean comment) {
            int start = at(node.getRange().orElseThrow().begin);
            if (comment && node.getComment().isPresent()) start = Math.min(start, at(node.getComment().orElseThrow().getRange().orElseThrow().begin));
            return start;
        }
        int end(Node node) { return at(node.getRange().orElseThrow().end) + 1; }
    }
}
