package ru.anblazhnov.springaidocloader;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Element;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

final class ConfigurationRedactor {
    private static final String REDACTED = "[REDACTED]";
    private static final Pattern URL_USERINFO = Pattern.compile("(://)[^\\s/@]+:[^\\s/@]+@");
    private static final Pattern URL_SECRET = Pattern.compile(
            "(?i)([?;&](?:password|passwd|pwd|token|secret|api[_-]?key|access[_-]?token)=)[^;&\\s]*");
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "(?s)-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----.*?-----END (?:[A-Z]+ )?PRIVATE KEY-----");
    private final Pattern sensitiveKeys;

    ConfigurationRedactor(String sensitiveKeyPattern) { sensitiveKeys = Pattern.compile(sensitiveKeyPattern); }

    String properties(String text) throws Exception {
        Properties properties = new Properties();
        properties.load(new StringReader(text));
        for (String key : properties.stringPropertyNames()) {
            properties.setProperty(key, sensitive(key) ? REDACTED : redactValue(properties.getProperty(key)));
        }
        StringWriter writer = new StringWriter();
        properties.store(writer, null);
        // Properties.store adds a changing timestamp which does not belong in the document.
        return writer.toString().replaceFirst("^#[^\\r\\n]*\\R", "");
    }

    String yaml(String text) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(20);
        options.setNestingDepthLimit(50);
        options.setCodePointLimit(text.length() + 1);
        Yaml parser = new Yaml(new SafeConstructor(options));
        DumperOptions dumpOptions = new DumperOptions();
        dumpOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml dumper = new Yaml(dumpOptions);
        List<Object> documents = new ArrayList<>();
        int[] nodes = {0};
        for (Object document : parser.loadAll(text)) {
            documents.add(redactTree(document, Collections.newSetFromMap(new IdentityHashMap<>()), nodes));
        }
        return dumper.dumpAll(documents.iterator());
    }

    private Object redactTree(Object value, Set<Object> active, int[] nodes) {
        if (++nodes[0] > 100_000) throw new IllegalArgumentException("YAML expansion limit exceeded");
        if (value instanceof Map<?, ?> map) {
            if (!active.add(value)) throw new IllegalArgumentException("Recursive YAML aliases are unsupported");
            Map<String, Object> result = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                // Complex YAML keys may serialize entire values. Reject rather than leak them.
                if (!(entry.getKey() instanceof String) && !(entry.getKey() instanceof Number)
                        && !(entry.getKey() instanceof Boolean)) throw new IllegalArgumentException("Unsupported YAML key");
                String key = String.valueOf(entry.getKey());
                result.put(key, sensitive(key) ? REDACTED : redactTree(entry.getValue(), active, nodes));
            }
            active.remove(value);
            return result;
        }
        if (value instanceof List<?> list) {
            if (!active.add(value)) throw new IllegalArgumentException("Recursive YAML aliases are unsupported");
            List<Object> result = new ArrayList<>();
            for (Object item : list) result.add(redactTree(item, active, nodes));
            active.remove(value);
            return result;
        }
        if (value instanceof String string) return redactValue(string);
        if (value instanceof byte[]) return REDACTED;
        return value;
    }

    String xml(String text) throws Exception {
        var document = XmlSupport.parse(text);
        boolean changed = redactElement(document.getDocumentElement());
        for (var node = document.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == org.w3c.dom.Node.COMMENT_NODE) {
                node.setNodeValue("");
                changed = true;
            }
        }
        if (!changed) return text;
        var factory = TransformerFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        StringWriter result = new StringWriter();
        factory.newTransformer().transform(new DOMSource(document), new StreamResult(result));
        return result.toString();
    }

    private boolean redactElement(Element element) {
        boolean changed = false;
        if (sensitive(element.getLocalName())) {
            element.setTextContent(REDACTED);
            changed = true;
        }
        // Spring-style <property name="password" value="..."> and similar name/key entries.
        boolean secretEntry = sensitive(element.getAttribute("name")) || sensitive(element.getAttribute("key"));
        var attributes = element.getAttributes();
        for (int index = 0; index < attributes.getLength(); index++) {
            var attribute = attributes.item(index);
            String previous = attribute.getNodeValue();
            boolean secretAttribute = sensitive(attribute.getLocalName() == null ? attribute.getNodeName() : attribute.getLocalName())
                    || secretEntry && attribute.getNodeName().equals("value");
            String next = secretAttribute ? REDACTED : redactValue(previous);
            attribute.setNodeValue(next);
            changed |= !next.equals(previous);
        }
        if (secretEntry) {
            element.setTextContent(REDACTED);
            changed = true;
        }
        for (var node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child) changed |= redactElement(child);
            else if (node.getNodeType() == org.w3c.dom.Node.TEXT_NODE || node.getNodeType() == org.w3c.dom.Node.CDATA_SECTION_NODE) {
                String previous = node.getNodeValue();
                String next = redactValue(previous);
                node.setNodeValue(next);
                changed |= !next.equals(previous);
            }
            else if (node.getNodeType() == org.w3c.dom.Node.COMMENT_NODE) {
                // Comments can contain obsolete credentials and are unnecessary configuration evidence.
                node.setNodeValue("");
                changed = true;
            }
        }
        return changed;
    }

    private boolean sensitive(String key) { return key != null && sensitiveKeys.matcher(key).matches(); }

    private static String redactValue(String value) {
        String result = URL_USERINFO.matcher(value).replaceAll("$1" + REDACTED + "@");
        result = URL_SECRET.matcher(result).replaceAll("$1" + REDACTED);
        return PRIVATE_KEY.matcher(result).replaceAll(REDACTED);
    }
}
