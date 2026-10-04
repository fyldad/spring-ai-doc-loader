package ru.anblazhnov.springaidocloader;

import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

final class XmlSupport {
    static org.w3c.dom.Document parse(String text) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler() {
            @Override public void error(SAXParseException e) throws SAXException { throw e; }
            @Override public void fatalError(SAXParseException e) throws SAXException { throw e; }
        });
        return builder.parse(new InputSource(new StringReader(text)));
    }

    static DiscoveredFile.FileKind classify(Element root, java.nio.file.Path path) {
        String namespace = root.getNamespaceURI() == null ? "" : root.getNamespaceURI();
        String name = root.getLocalName();
        if ((name.equals("definitions") && namespace.equals("http://schemas.xmlsoap.org/wsdl/"))
                || (name.equals("description") && namespace.equals("http://www.w3.org/ns/wsdl"))) {
            return DiscoveredFile.FileKind.WSDL;
        }
        if (name.equals("schema") && namespace.equals("http://www.w3.org/2001/XMLSchema")) {
            return DiscoveredFile.FileKind.XSD;
        }
        if (name.equals("project") && (namespace.equals("http://maven.apache.org/POM/4.0.0")
                || namespace.isEmpty() && path.getFileName().toString().equalsIgnoreCase("pom.xml"))) {
            return DiscoveredFile.FileKind.POM;
        }
        return DiscoveredFile.FileKind.XML;
    }

    static Element child(Element parent, String name) {
        if (parent == null) return null;
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getLocalName())
                    && java.util.Objects.equals(parent.getNamespaceURI(), element.getNamespaceURI())) return element;
        }
        return null;
    }

    static String value(Element parent, String name) {
        Element element = child(parent, name);
        return element == null ? "" : element.getTextContent().trim();
    }
}
