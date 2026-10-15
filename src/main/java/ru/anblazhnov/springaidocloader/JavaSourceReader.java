package ru.anblazhnov.springaidocloader;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

import com.github.javaparser.*;
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
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;

/**
 * Parses each indexed source once; AST nodes are used only to select original source ranges.
 */
final class JavaSourceReader {
    static final String VERSION = "javaparser-3.28.0-java-reader-v2";
    private static final Logger log = LoggerFactory.getLogger(JavaSourceReader.class);
    private final JavaParser parser;
    private final ReflectionTypeSolver jdkTypes = new ReflectionTypeSolver();
    private final JavaParsingProperties properties;
    private final int contextBudget;
    private final List<String> resolverDiagnostics = new ArrayList<>();
    private final TokenCountEstimator tokenCountEstimator;

    JavaSourceReader(JavaParsingProperties properties, ChunkingProperties chunks) {
        this.tokenCountEstimator = new JTokkitTokenCountEstimator();
        this.properties = properties;
        chunks.validate();
        contextBudget = chunks.getMaxTokens();
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.valueOf(properties.getLanguageLevel()))
                .setTabSize(1);
        if (properties.isResolveSymbols()) {
            CombinedTypeSolver solver = new CombinedTypeSolver(new ReflectionTypeSolver());
            for (Path root : properties.getSourceRoots()) {
                try {
                    solver.add(new JavaParserTypeSolver(root, new ParserConfiguration()
                            .setLanguageLevel(configuration.getLanguageLevel()).setTabSize(1)));
                } catch (RuntimeException error) {
                    resolverDiagnostics.add("source_root_unavailable: " + root.getFileName());
                }
            }
            for (Path jar : properties.getDependencyJars()) {
                // The InputStream constructor avoids Javassist holding the configured JAR open on Windows.
                try (var input = Files.newInputStream(jar)) {
                    solver.add(new JarTypeSolver(input));
                } catch (Exception error) {
                    resolverDiagnostics.add("dependency_jar_unavailable: " + jar.getFileName());
                }
            }
            configuration.setSymbolResolver(new JavaSymbolSolver(solver));
        }
        parser = new JavaParser(configuration);
    }

    synchronized List<SourceUnit> read(DiscoveredFile file) {
        try {
            ParseResult<CompilationUnit> result = parser.parse(file.text());
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                String diagnostics = result.getProblems().stream().map(Problem::getVerboseMessage)
                        .collect(Collectors.joining("; "));
                return fallback(file, diagnostics);
            }

            return extract(file, result.getResult().orElseThrow());
        } catch (RuntimeException error) {
            return fallback(file, error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private List<SourceUnit> extract(DiscoveredFile file, CompilationUnit compilation) {

        Map<String, Object> base = new LinkedHashMap<>(file.metadata());
//        base.put("parser_version", VERSION);
//        base.put("parse_status", "parsed");
//        base.put("java_language_level", properties.getLanguageLevel());
//        base.put("resolution_status", properties.isResolveSymbols() ? "partial" : "not_attempted");
//        base.put("semantic_java", true);
        base.remove("package");
        compilation.getPackageDeclaration().ifPresent(declaration -> base.put("package", declaration.getNameAsString()));
        List<String> imports = compilation.getImports().stream().map(Object::toString).map(String::strip).toList();
        List<String> fields = new ArrayList<>();
        base.put("imports", imports);
        if (!resolverDiagnostics.isEmpty()) base.put("resolution_diagnostic", String.join("; ", resolverDiagnostics));
        List<SourceUnit> units = new ArrayList<>();
        for (TypeDeclaration<?> type : compilation.findAll(TypeDeclaration.class)) {

            if (tokenCountEstimator.estimate(type.toString()) < contextBudget) {
                units.add(new SourceUnit(type.toString(), "java_file_context", base));
                continue;
            }

            String fqn = type.getFullyQualifiedName().orElseGet(() -> qualifiedName(type, base));
            Map<String, Object> typeMetadata = metadata(base, type, fqn, fqn, type.getNameAsString());

            for (BodyDeclaration<?> member : type.getMembers()) {
                if (member instanceof FieldDeclaration field) {
                    fields.add(field.toString());
                } else if (member instanceof CallableDeclaration<?> callable) {
                    String name = callable instanceof ConstructorDeclaration ? "<init>" : callable.getNameAsString();
                    String parameters = callable.getParameters().stream()
                            .map(parameter -> canonical(parameter.getType(), compilation) + (parameter.isVarArgs() ? "[]" : ""))
                            .collect(Collectors.joining(","));
                    String symbol = fqn + "#" + name + "(" + parameters + ")";
                    Map<String, Object> metadata = metadata(typeMetadata, callable, fqn, symbol, null);
                    List<Type> usedTypes = new ArrayList<>(callable.getParameters().stream().map(Parameter::getType).toList());
                    if (callable instanceof MethodDeclaration method) usedTypes.add(method.getType());
                    units.add(new SourceUnit(member.toString(), "java_method", metadata));
                    units.add(new SourceUnit("usedTypes:" + usedTypes.stream().map(String::valueOf).collect(Collectors.joining(";")), base));
                } else if (!(member instanceof TypeDeclaration<?>)) {
                    var metadata = metadata(typeMetadata, member, fqn);
                    units.add(new SourceUnit(member.toString(), "java_member", metadata));
                } else if (type instanceof EnumDeclaration enumeration && !enumeration.getEntries().isEmpty()) {
                    var metadata = metadata(typeMetadata, type, fqn, fqn + "#enum_constants", "enum constants");
                    units.add(new SourceUnit(enumeration.toString(), "java_enum_constants", metadata));
                }
            }
        }
        if (!fields.isEmpty()) {
            base.put("fields", fields);
        }


        return units.stream()
                .filter(u -> u.source() != null && !u.source().isBlank())
                .collect(Collectors.toList());
    }

    private Map<String, Object> metadata(Map<String, Object> base, Node node, String fqn) {
        return metadata(base, node, fqn, null, null);
    }

    private Map<String, Object> metadata(Map<String, Object> base, Node node, String fqn, String symbol, String signature) {
        Map<String, Object> metadata = new LinkedHashMap<>(base);
        metadata.remove("overview");
        metadata.remove("member_signatures");
        metadata.put("type_fqn", fqn);
        if (symbol != null) metadata.put("symbol_id", symbol);
        String fileId = SourceIdentity.uuid("file", String.valueOf(base.get("repository_id")),
                String.valueOf(base.get("module_id")), String.valueOf(base.get("relative_path")));
        metadata.put("enclosing_type_id", SourceIdentity.uuid("symbol", fileId, fqn));
        if (signature != null) metadata.put("signature", signature);
        metadata.remove("annotations");
        if (node instanceof NodeWithAnnotations<?> annotated && !annotated.getAnnotations().isEmpty()) {
            metadata.put("annotations", annotated.getAnnotations().stream().map(Object::toString).toList());
        }
        metadata.put("visibility", visibility(node));
        SoapJavaMetadata.apply(node, metadata);
        return metadata;
    }

    private String visibility(Node node) {
        String visibility = "package_private";
        if (node instanceof NodeWithModifiers<?> modified) {
            for (var modifier : modified.getModifiers()) {
                if (List.of("public", "protected", "private").contains(modifier.getKeyword().asString()))
                    visibility = modifier.getKeyword().asString();
            }
        }
        if (visibility.equals("package_private") && node.getParentNode().orElse(null) instanceof TypeDeclaration<?> enclosing) {
            if (enclosing instanceof AnnotationDeclaration || enclosing instanceof ClassOrInterfaceDeclaration declaration && declaration.isInterface())
                return "public";
            if (node instanceof CompactConstructorDeclaration) return visibility(enclosing);
        }
        return visibility;
    }

    // Explicit imports qualify identities even without a classpath. Wildcard/unknown types stay lexical.
    private String canonical(Type type, CompilationUnit compilation) {
        if (type.isArrayType()) return canonical(type.asArrayType().getComponentType(), compilation) + "[]";
        if (type instanceof ClassOrInterfaceType reference) {
            String name = reference.getNameWithScope();
            String first = name.split("\\.")[0];
            Node parent = type;
            while (parent != null) {
                if (parent instanceof NodeWithTypeParameters<?> generic && generic.getTypeParameters().stream().anyMatch(parameter -> parameter.getNameAsString().equals(first)))
                    return name;
                if (parent instanceof TypeDeclaration<?> enclosing) {
                    if (enclosing.getNameAsString().equals(first))
                        return enclosing.getFullyQualifiedName().orElse(first) + name.substring(first.length());
                    for (var member : enclosing.getMembers()) {
                        if (member instanceof TypeDeclaration<?> nested && nested.getNameAsString().equals(first))
                            return nested.getFullyQualifiedName().orElse(first) + name.substring(first.length());
                    }
                }
                parent = parent.getParentNode().orElse(null);
            }
            for (var declaration : compilation.getTypes()) {
                if (declaration.getNameAsString().equals(first))
                    return declaration.getFullyQualifiedName().orElse(first) + name.substring(first.length());
            }
            for (var imported : compilation.getImports()) {
                if (!imported.isAsterisk() && !imported.isStatic() && imported.getName().getIdentifier().equals(first)) {
                    return imported.getNameAsString() + name.substring(first.length());
                }
            }
            if (!name.contains(".") && jdkTypes.tryToSolveType("java.lang." + name).isSolved())
                return "java.lang." + name;
            // Generic arguments are erased: they cannot distinguish legal Java overloads.
            return name;
        }
        return type.asString().replaceAll("\\s+", "");
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


    private List<SourceUnit> fallback(DiscoveredFile file, String diagnostic) {
        Map<String, Object> metadata = new LinkedHashMap<>(file.metadata());
        metadata.put("parser_version", VERSION);
        metadata.put("parse_status", "fallback");
        metadata.put("resolution_status", "not_attempted");
        metadata.put("semantic_java", true);
        metadata.put("parse_diagnostic", ConservativeTokenBudget.abbreviate(diagnostic.isBlank() ? "Parser produced no compilation unit" : diagnostic, 512));
        log.warn("Java parse fallback for {}:{}", metadata.get("repository_id"), metadata.get("relative_path"));
        return List.of(new SourceUnit(file.text(), "java_fallback", metadata));
    }

}
