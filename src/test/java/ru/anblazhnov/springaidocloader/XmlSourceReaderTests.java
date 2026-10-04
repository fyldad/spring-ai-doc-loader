package ru.anblazhnov.springaidocloader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class XmlSourceReaderTests {
    @TempDir Path root;

    private static final String SCHEMA = """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:c="urn:customer" targetNamespace="urn:customer" elementFormDefault="qualified">
              <xs:element name="Request" type="c:RequestType"/>
              <xs:element name="Response" type="xs:string"/>
              <xs:element name="Fault" type="xs:string"/>
              <xs:complexType name="RequestType"><xs:sequence minOccurs="0">
                <xs:element name="id" type="xs:string" minOccurs="0" maxOccurs="unbounded" nillable="true"/>
              </xs:sequence></xs:complexType>
              <xs:simpleType name="Code"><xs:restriction base="xs:string"><xs:pattern value="[A-Z]+"/><xs:enumeration value="YES"/></xs:restriction></xs:simpleType>
            </xs:schema>
            """;
    private static final String CONTRACT = """
            <?xml version="1.0"?>
            <w:definitions xmlns:w="http://schemas.xmlsoap.org/wsdl/" xmlns:s="http://schemas.xmlsoap.org/wsdl/soap/" xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:c="urn:customer" xmlns:t="urn:service" targetNamespace="urn:service" name="Customer">
              <w:types><xs:schema targetNamespace="urn:service"><xs:import namespace="urn:customer" schemaLocation="types.xsd"/></xs:schema></w:types>
              <w:message name="Input"><w:part name="request" element="c:Request"/></w:message>
              <w:message name="Output"><w:part name="response" element="c:Response"/></w:message>
              <w:message name="Error"><w:part name="fault" element="c:Fault"/></w:message>
              <w:portType name="CustomerPort">
                <w:operation name="getCustomer"><w:input message="t:Input"/><w:output message="t:Output"/><w:fault name="Failure" message="t:Error"/></w:operation>
              </w:portType>
              <w:binding name="CustomerBinding" type="t:CustomerPort"><w:operation name="getCustomer"><s:operation soapAction="urn:getCustomer"/></w:operation></w:binding>
              <w:service name="CustomerService"><w:port name="SoapPort" binding="t:CustomerBinding"/></w:service>
            </w:definitions>
            """;

    @Test
    void linksOperationToImportedRequestResponseAndFaultAndRetainsCitations() {
        DiscoveredFile wsdl = file("service.wsdl", DiscoveredFile.FileKind.WSDL, CONTRACT.replace("\n", "\r\n"));
        DiscoveredFile schema = file("types.xsd", DiscoveredFile.FileKind.XSD, SCHEMA);
        XmlSourceReader reader = reader(List.of(wsdl, schema));
        SourceUnit operation = kind(reader.read(wsdl), "wsdl_operation");
        assertThat(operation.metadata()).containsEntry("port_type", "{urn:service}CustomerPort")
                .containsEntry("soap_action", "urn:getCustomer").containsEntry("resolution_status", "resolved_local");
        assertThat(operation.value("contract_context")).contains("Request message: {urn:service}Input", "{urn:customer}Request", "{urn:customer}Response", "{urn:customer}Fault", "CustomerService", "SoapPort");
        SourceUnit request = reader.read(schema).stream().filter(unit -> unit.value("element_qname").equals("{urn:customer}Request")).findFirst().orElseThrow();
        assertThat(operation.value("relationships")).contains("uses_schema|" + request.parentId());
        var docs = factory(1200).create(operation);
        assertThat(docs).allSatisfy(document -> {
            var metadata = document.getMetadata();
            assertThat(new SourceSnapshotStore(root.resolve("snapshots")).excerpt(metadata))
                    .isEqualTo(wsdl.text().substring((int) metadata.get("start_offset"), (int) metadata.get("end_offset")));
            assertThat(metadata).containsEntry("start_line", 8).containsKeys("relationships_snapshot", "contract_context_snapshot");
            assertThat(document.getText()).hasSizeLessThanOrEqualTo(1200);
        });
    }

    @Test
    void xsdRetainsQualificationOccurrencesRestrictionsAndLinkedTypes() {
        DiscoveredFile schema = file("types.xsd", DiscoveredFile.FileKind.XSD, SCHEMA);
        List<SourceUnit> units = reader(List.of(schema)).read(schema);
        SourceUnit type = kind(units, "xsd_complexType");
        assertThat(type.value("contract_context")).contains("element {urn:customer}id", "occurs=0..unbounded", "nillable=true", "sequence occurs=0..1");
        assertThat(kind(units, "xsd_simpleType").value("contract_context")).contains("pattern=[A-Z]+", "enumeration=YES", "base={http://www.w3.org/2001/XMLSchema}string");
        SourceUnit request = units.stream().filter(unit -> unit.value("element_qname").equals("{urn:customer}Request")).findFirst().orElseThrow();
        assertThat(request.value("relationships")).contains(type.parentId());
    }

    @Test
    void prefixAliasesDoNotChangeSemanticIdentityOrLinks() {
        DiscoveredFile one = file("types.xsd", DiscoveredFile.FileKind.XSD, SCHEMA);
        DiscoveredFile two = file("types.xsd", DiscoveredFile.FileKind.XSD, SCHEMA.replace("xs:", "schema:").replace("xmlns:xs", "xmlns:schema").replace("c:", "other:").replace("xmlns:c=", "xmlns:other="));
        List<SourceUnit> first = reader(List.of(one)).read(one); List<SourceUnit> second = reader(List.of(two)).read(two);
        assertThat(second).extracting(SourceUnit::parentId).containsExactlyElementsOf(first.stream().map(SourceUnit::parentId).toList());
        assertThat(second).extracting(unit -> unit.value("element_qname")).containsExactlyElementsOf(first.stream().map(unit -> unit.value("element_qname")).toList());
    }

    @Test
    void blocksExternalImportsAndCrossRepositoryTargetsAndBoundsCycles() {
        DiscoveredFile schema = file("types.xsd", DiscoveredFile.FileKind.XSD, SCHEMA);
        DiscoveredFile external = file("external.wsdl", DiscoveredFile.FileKind.WSDL, CONTRACT.replace("types.xsd", "https://example.invalid/types.xsd"));
        assertThat(kind(reader(List.of(external, schema)).read(external), "wsdl_operation").value("resolution_diagnostic")).contains("blocked_nonlocal_import");
        DiscoveredFile missing = file("service.wsdl", DiscoveredFile.FileKind.WSDL, CONTRACT.replace("types.xsd", "../outside.xsd"));
        assertThat(kind(reader(List.of(missing, schema)).read(missing), "wsdl_operation").value("resolution_diagnostic")).contains("import_not_in_repository_inventory");
        String cycle = "<s:schema xmlns:s='http://www.w3.org/2001/XMLSchema' targetNamespace='urn:cycle'><s:include schemaLocation='%s'/></s:schema>";
        DiscoveredFile a = file("a.xsd", DiscoveredFile.FileKind.XSD, cycle.formatted("b.xsd"));
        DiscoveredFile b = file("b.xsd", DiscoveredFile.FileKind.XSD, cycle.formatted("a.xsd"));
        assertThat(kind(reader(List.of(a, b)).read(a), "xsd_overview").value("resolution_diagnostic")).contains("import_revisit_or_cycle");
        XmlParsingProperties limits = new XmlParsingProperties(); limits.setMaxImportDepth(0);
        assertThat(kind(new XmlSourceReader(limits, List.of(a, b)).read(a), "xsd_overview").value("resolution_diagnostic")).contains("import_limit");
        Map<String, Object> foreignMetadata = new java.util.LinkedHashMap<>(schema.metadata());
        foreignMetadata.put("repository_id", "foreign");
        DiscoveredFile foreign = new DiscoveredFile(schema.path(), schema.kind(), schema.text(), foreignMetadata);
        assertThat(kind(reader(List.of(file("service.wsdl", DiscoveredFile.FileKind.WSDL, CONTRACT), foreign)).read(file("service.wsdl", DiscoveredFile.FileKind.WSDL, CONTRACT)), "wsdl_operation").value("resolution_diagnostic")).contains("import_not_in_repository_inventory");
    }

    @Test
    void detectsUnsupportedWsdlAndMalformedOrUnsafeXml() throws Exception {
        DiscoveredFile version2 = file("v2.wsdl", DiscoveredFile.FileKind.WSDL, "<description xmlns='http://www.w3.org/ns/wsdl'/>");
        assertThat(reader(List.of(version2)).read(version2).getFirst().metadata()).containsEntry("parse_status", "unsupported_version");
        for (String text : List.of("<broken>", "<!DOCTYPE a [<!ENTITY e SYSTEM 'file:///secret'>]><a>&e;</a>")) {
            DiscoveredFile bad = file("bad.xml", DiscoveredFile.FileKind.XML, text);
            assertThat(reader(List.of(bad)).read(bad).getFirst().metadata()).containsEntry("parse_status", "fallback");
        }
    }

    @Test
    void lexicalRangesHandleCommentsCdataQuotedAnglesAndRepeatedElements() throws Exception {
        String text = "<?xml version='1.0'?>\n<!-- <fake/> --><root>\n<bean id='a>b'><![CDATA[<not-an-element/>]]></bean>\n<bean id='b'/>\n</root>";
        DiscoveredFile file = file("beans.xml", DiscoveredFile.FileKind.XML, text);
        List<SourceUnit> units = reader(List.of(file)).read(file);
        assertThat(units).hasSize(2);
        assertThat(units.getFirst().text()).isEqualTo("<bean id='a>b'><![CDATA[<not-an-element/>]]></bean>");
        assertThat(units.getLast().text()).isEqualTo("<bean id='b'/>");
        assertThat(units.getFirst().parentId()).isNotEqualTo(units.getLast().parentId());
    }

    @Test
    void mavenSeparatesDeclaredAndLocalVersionsManagementPluginsAndInactiveProfiles() {
        String text = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <parent><groupId>org.parent</groupId><artifactId>parent</artifactId><version>9</version></parent>
                  <artifactId>module</artifactId><modules><module>child</module></modules>
                  <properties><lib.version>1.2</lib.version><alias>${lib.version}</alias></properties>
                  <dependencyManagement><dependencies><dependency><groupId>org.lib</groupId><artifactId>lib</artifactId><version>${alias}</version></dependency></dependencies></dependencyManagement>
                  <dependencies><dependency><groupId>org.lib</groupId><artifactId>lib</artifactId></dependency><dependency><groupId>org.lib</groupId><artifactId>other</artifactId><version>${alias}</version><scope>test</scope></dependency></dependencies>
                  <build><plugins><plugin><artifactId>compiler</artifactId><configuration><release>25</release></configuration></plugin></plugins></build>
                  <profiles><profile><id>prod</id><dependencies><dependency><artifactId>profile-lib</artifactId><version>${alias}</version></dependency></dependencies></profile></profiles>
                </project>
                """;
        DiscoveredFile pom = file("pom.xml", DiscoveredFile.FileKind.POM, text);
        List<SourceUnit> declared = reader(List.of(pom)).read(pom);
        assertThat(kind(declared, "maven_overview").metadata()).containsEntry("group_id", "").containsEntry("version", "").containsEntry("dependency_model", "declared");
        assertThat(declared).anySatisfy(unit -> assertThat(unit.metadata()).containsEntry("artifact_id", "other").containsEntry("version", "${alias}").containsEntry("dependency_scope", "test").doesNotContainKey("resolved_version"));
        assertThat(declared).anySatisfy(unit -> assertThat(unit.metadata()).containsEntry("artifact_id", "lib").containsEntry("resolution_status", "version_not_declared"));
        assertThat(declared).extracting(SourceUnit::chunkKind).contains("maven_dependency_management", "maven_plugin", "maven_configuration", "maven_profile");
        XmlParsingProperties policy = new XmlParsingProperties(); policy.setMavenResolutionPolicy("local-properties");
        List<SourceUnit> local = new XmlSourceReader(policy, List.of(pom)).read(pom);
        assertThat(local).anySatisfy(unit -> assertThat(unit.metadata()).containsEntry("artifact_id", "other").containsEntry("declared_version", "${alias}").containsEntry("resolved_version", "1.2"));
        assertThat(local).anySatisfy(unit -> assertThat(unit.metadata()).containsEntry("artifact_id", "profile-lib").containsEntry("profile", "prod").doesNotContainKey("resolved_version"));
    }

    @Test
    void linksLiteralJavaAnnotationsToContractsAndInterfacesAsInferred() {
        DiscoveredFile wsdl = file("service.wsdl", DiscoveredFile.FileKind.WSDL, CONTRACT);
        DiscoveredFile schema = file("types.xsd", DiscoveredFile.FileKind.XSD, SCHEMA);
        DiscoveredFile java = file("CustomerPort.java", DiscoveredFile.FileKind.JAVA, """
                package example;
                @WebService(targetNamespace="urn:service", name="CustomerPort")
                interface CustomerPort { @WebMethod(operationName="getCustomer") String get(String id); }
                @WebService(endpointInterface="example.CustomerPort") class Endpoint implements CustomerPort { public String get(String id) { return id; } }
                @XmlRootElement(namespace="urn:customer", name="Request") class Request {}
                """);
        List<SourceUnit> units = new ArrayList<>(); XmlSourceReader xml = reader(List.of(wsdl, schema));
        units.addAll(xml.read(wsdl)); units.addAll(xml.read(schema));
        units.addAll(new JavaSourceReader(new JavaParsingProperties(), new ChunkingProperties()).read(java));
        List<SourceUnit> linked = ContractLinker.link(units);
        assertThat(linked).anySatisfy(unit -> assertThat(unit.metadata()).containsEntry("symbol_id", "example.CustomerPort#get(java.lang.String)").containsKey("relationships"));
        SourceUnit operation = kind(linked, "wsdl_operation");
        assertThat(operation.value("relationships")).contains("mapped_from_java", "inferred");
        assertThat(linked).anySatisfy(unit -> assertThat(unit.metadata()).containsEntry("symbol_id", "example.Endpoint").containsKey("relationships"));
        assertThat(linked).anySatisfy(unit -> {
            assertThat(unit.metadata()).containsEntry("symbol_id", "example.Endpoint#get(java.lang.String)");
            assertThat(unit.value("relationships")).contains("maps_to_operation|" + operation.parentId(), "inferred");
        });
        assertThat(linked).anySatisfy(unit -> assertThat(unit.metadata()).containsEntry("symbol_id", "example.Request").containsKey("relationships"));
    }

    @Test
    void semanticSplittingPreservesEveryCharacterAndUniqueParents() {
        DiscoveredFile schema = file("types.xsd", DiscoveredFile.FileKind.XSD, SCHEMA);
        SourceUnit type = kind(reader(List.of(schema)).read(schema), "xsd_complexType");
        var documents = factory(240).create(type);
        assertThat(documents).hasSizeGreaterThan(1);
        SourceSnapshotStore snapshots = new SourceSnapshotStore(root.resolve("snapshots"));
        assertThat(String.join("", documents.stream().map(document -> snapshots.excerpt(document.getMetadata())).toList())).isEqualTo(type.text());
        assertThat(documents).allSatisfy(document -> assertThat(document.getText()).hasSizeLessThanOrEqualTo(240));
    }

    @Test
    void overloadedWsdlOperationsHaveDistinctParentsAndBindingActions() {
        String text = """
                <w:definitions xmlns:w="http://schemas.xmlsoap.org/wsdl/" xmlns:s="http://schemas.xmlsoap.org/wsdl/soap12/" xmlns:t="urn:s" targetNamespace="urn:s">
                  <w:message name="Input"/>
                  <w:portType name="Port">
                    <w:operation name="get"><w:input name="One" message="t:Input"/></w:operation>
                    <w:operation name="get"><w:input name="Two" message="t:Input"/></w:operation>
                  </w:portType>
                  <w:binding name="Binding" type="t:Port">
                    <w:operation name="get"><s:operation soapAction="one"/><w:input name="One"/></w:operation>
                    <w:operation name="get"><s:operation soapAction="two"/><w:input name="Two"/></w:operation>
                  </w:binding>
                </w:definitions>
                """;
        DiscoveredFile file = file("overloaded.wsdl", DiscoveredFile.FileKind.WSDL, text);
        List<SourceUnit> operations = reader(List.of(file)).read(file).stream().filter(unit -> unit.chunkKind().equals("wsdl_operation")).toList();
        assertThat(operations).hasSize(2);
        assertThat(operations.getFirst().parentId()).isNotEqualTo(operations.getLast().parentId());
        assertThat(operations.getFirst().metadata()).containsEntry("soap_action", "one");
        assertThat(operations.getLast().metadata()).containsEntry("soap_action", "two");
    }

    private XmlSourceReader reader(List<DiscoveredFile> files) { return new XmlSourceReader(new XmlParsingProperties(), files); }
    private SourceUnit kind(List<SourceUnit> units, String kind) { return units.stream().filter(unit -> unit.chunkKind().equals(kind)).findFirst().orElseThrow(); }
    private SourceDocumentFactory factory(int size) { ChunkingProperties properties = new ChunkingProperties(); properties.setMaxCharacters(size); properties.setSnapshotDirectory(root.resolve("snapshots")); return new SourceDocumentFactory(properties); }
    private DiscoveredFile file(String name, DiscoveredFile.FileKind kind, String text) {
        return new DiscoveredFile(root.resolve(name), kind, text, Map.of("repository_id", "fixture", "module_id", "fixture:.", "relative_path", name,
                "language", kind.language, "file_kind", kind.name().toLowerCase(), "source_set", "main_resources", "file_hash", SourceIdentity.hash(text)));
    }
}
