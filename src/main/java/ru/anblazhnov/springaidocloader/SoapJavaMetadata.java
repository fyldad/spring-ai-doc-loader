package ru.anblazhnov.springaidocloader;

import java.util.Map;
import java.util.Set;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;

/** Only literal annotation attributes are facts; constants/defaults need richer resolution. */
final class SoapJavaMetadata {
    static void apply(Node node, Map<String, Object> metadata) {
        metadata.remove("soap_schema_qname"); metadata.remove("soap_schema_kind");
        if (!(node instanceof NodeWithAnnotations<?> annotated)) return;
        for (AnnotationExpr annotation : annotated.getAnnotations()) {
            String name = annotation.getName().getIdentifier();
            if (!Set.of("WebService", "WebMethod", "XmlRootElement", "XmlType", "PayloadRoot").contains(name)) continue;
            if (name.equals("WebService")) {
                put(metadata, "soap_namespace", literal(annotation, "targetNamespace"));
                put(metadata, "soap_port_type", literal(annotation, "name"));
                put(metadata, "soap_endpoint_interface", literal(annotation, "endpointInterface"));
            }
            if (name.equals("WebMethod")) {
                if (annotation.toString().matches("(?s).*\\bexclude\\s*=\\s*true\\b.*")) continue;
                String operation = literal(annotation, "operationName");
                if (operation.isBlank() && node instanceof MethodDeclaration method) operation = method.getNameAsString();
                put(metadata, "soap_operation", operation);
                put(metadata, "soap_action", literal(annotation, "action"));
            }
            if (name.equals("PayloadRoot")) {
                String namespace = literal(annotation, "namespace"); String local = literal(annotation, "localPart");
                if (!namespace.isBlank() && !local.isBlank()) metadata.put("soap_schema_qname", LocatedXml.qname(namespace, local));
            }
            if (name.equals("XmlRootElement") || name.equals("XmlType")) {
                String namespace = literal(annotation, "namespace"); String local = literal(annotation, "name");
                if (!namespace.isBlank() && !local.isBlank() && !namespace.equals("##default") && !local.equals("##default")) {
                    metadata.put("soap_schema_qname", LocatedXml.qname(namespace, local));
                    metadata.put("soap_schema_kind", name.equals("XmlType") ? "type" : "element");
                }
            }
            // Name-only annotation recognition may match a custom annotation. Link evidence is labelled inferred.
            metadata.put("soap_annotation_evidence", "literal_attributes; annotation_type_unresolved");
        }
    }

    private static void put(Map<String, Object> metadata, String field, String value) {
        if (!value.isBlank() && !value.equals("##default")) metadata.put(field, value);
    }
    private static String literal(AnnotationExpr annotation, String attribute) {
        if (!(annotation instanceof NormalAnnotationExpr normal)) return "";
        for (var pair : normal.getPairs()) if (pair.getNameAsString().equals(attribute)) {
            Expression value = pair.getValue();
            if (value.isStringLiteralExpr()) return value.asStringLiteralExpr().asString();
        }
        return "";
    }
}
