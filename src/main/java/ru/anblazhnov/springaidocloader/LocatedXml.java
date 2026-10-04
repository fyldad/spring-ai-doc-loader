package ru.anblazhnov.springaidocloader;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Element;

/** Secure namespace-aware DOM plus a lexical range map over the exact source, never serialized DOM. */
final class LocatedXml {
    record Range(int start, int openEnd, int end) { }
    final Element root;
    private final Map<Element, Range> ranges = new IdentityHashMap<>();

    LocatedXml(String source) throws Exception {
        root = XmlSupport.parse(source).getDocumentElement();
        List<Element> elements = descendants(root);
        var stack = new ArrayDeque<Integer>();
        List<Range> lexical = new ArrayList<>();
        int cursor = 0;
        while ((cursor = source.indexOf('<', cursor)) >= 0) {
            if (source.startsWith("<!--", cursor)) { cursor = after(source, "-->", cursor + 4); continue; }
            if (source.startsWith("<![CDATA[", cursor)) { cursor = after(source, "]]>", cursor + 9); continue; }
            if (source.startsWith("<?", cursor)) { cursor = after(source, "?>", cursor + 2); continue; }
            int end = tagEnd(source, cursor);
            if (source.startsWith("<!", cursor)) throw new IllegalArgumentException("Unsupported XML declaration");
            if (source.startsWith("</", cursor)) {
                int index = stack.pop();
                Range range = lexical.get(index);
                lexical.set(index, new Range(range.start(), range.openEnd(), end));
            }
            else {
                int index = lexical.size();
                if (index >= elements.size()) throw new IllegalArgumentException("XML location mismatch");
                int nameEnd = cursor + 1;
                while (nameEnd < end && !Character.isWhitespace(source.charAt(nameEnd)) && "/>".indexOf(source.charAt(nameEnd)) < 0) nameEnd++;
                if (!source.substring(cursor + 1, nameEnd).equals(elements.get(index).getTagName()))
                    throw new IllegalArgumentException("XML location mismatch");
                boolean empty = source.charAt(end - 2) == '/';
                lexical.add(new Range(cursor, end, empty ? end : -1));
                if (!empty) stack.push(index);
            }
            cursor = end;
        }
        if (!stack.isEmpty() || lexical.size() != elements.size()) throw new IllegalArgumentException("Incomplete XML locations");
        for (int i = 0; i < elements.size(); i++) ranges.put(elements.get(i), lexical.get(i));
    }

    Range range(Element element) { return ranges.get(element); }
    static List<Element> children(Element element) {
        List<Element> result = new ArrayList<>();
        if (element == null) return result;
        for (var node = element.getFirstChild(); node != null; node = node.getNextSibling()) if (node instanceof Element child) result.add(child);
        return result;
    }
    static List<Element> children(Element element, String namespace, String name) {
        return children(element).stream().filter(child -> namespace.equals(ns(child)) && name.equals(child.getLocalName())).toList();
    }
    static List<Element> descendants(Element element) {
        List<Element> result = new ArrayList<>();
        var pending = new ArrayDeque<Element>();
        pending.push(element);
        while (!pending.isEmpty()) {
            Element next = pending.pop(); result.add(next);
            List<Element> children = children(next);
            for (int i = children.size() - 1; i >= 0; i--) pending.push(children.get(i));
        }
        return result;
    }
    static String ns(Element element) { return element.getNamespaceURI() == null ? "" : element.getNamespaceURI(); }
    static String qname(String namespace, String name) { return "{" + namespace + "}" + name; }
    static String reference(Element element, String value) {
        if (value.isBlank()) return "";
        int colon = value.indexOf(':');
        String namespace = element.lookupNamespaceURI(colon < 0 ? null : value.substring(0, colon));
        if (namespace == null && colon >= 0) return "{UNRESOLVED:" + value.substring(0, colon) + "}" + value.substring(colon + 1);
        return qname(namespace == null ? "" : namespace, colon < 0 ? value : value.substring(colon + 1));
    }
    static String path(Element element) {
        List<String> parts = new ArrayList<>();
        for (Element current = element; current != null; current = current.getParentNode() instanceof Element parent ? parent : null) {
            int position = 1;
            for (var sibling = current.getPreviousSibling(); sibling != null; sibling = sibling.getPreviousSibling())
                if (sibling instanceof Element other && ns(other).equals(ns(current)) && other.getLocalName().equals(current.getLocalName())) position++;
            parts.addFirst(qname(ns(current), current.getLocalName()) + "[" + position + "]");
        }
        return "/" + String.join("/", parts);
    }
    private static int after(String source, String delimiter, int start) {
        int end = source.indexOf(delimiter, start);
        if (end < 0) throw new IllegalArgumentException("Unterminated XML token");
        return end + delimiter.length();
    }
    private static int tagEnd(String source, int start) {
        char quote = 0;
        for (int i = start + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quote != 0) { if (c == quote) quote = 0; }
            else if (c == '\'' || c == '"') quote = c;
            else if (c == '>') return i + 1;
        }
        throw new IllegalArgumentException("Unterminated XML tag");
    }
}
