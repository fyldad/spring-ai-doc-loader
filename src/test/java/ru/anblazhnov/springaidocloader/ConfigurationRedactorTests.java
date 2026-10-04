package ru.anblazhnov.springaidocloader;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurationRedactorTests {
    private final ConfigurationRedactor redactor = new ConfigurationRedactor(new DiscoveryProperties().getSensitiveKeyPattern());

    @Test
    void redactsNestedYamlMapsListsMultidocumentAndUrlCredentials() {
        String result = redactor.yaml("""
                spring:
                  datasource:
                    password: yaml-secret
                    url: jdbc:postgresql://user:url-secret@localhost/database
                clients:
                  - name: customer
                    api-key: api-secret
                    endpoint: https://host/service?token=query-secret&mode=soap
                ---
                enabled: true
                """);
        assertThat(result).contains("[REDACTED]", "customer", "mode=soap", "enabled: true")
                .doesNotContain("yaml-secret", "url-secret", "api-secret", "query-secret");
    }

    @Test
    void redactsPropertiesIncludingContinuationAndUnicodeEscapedKeys() throws Exception {
        String result = redactor.properties("""
                service.pass\\u0077ord=property-secret
                private-key=first\\
                  second
                endpoint=https://user:url-secret@host/service?api_key=query-secret
                service.name=customer
                """);
        assertThat(result).contains("REDACTED", "customer")
                .doesNotContain("property-secret", "first", "second", "url-secret", "query-secret");
    }

    @Test
    void redactsXmlElementsNamedPropertiesAttributesAndComments() throws Exception {
        String result = redactor.xml("""
                <!-- obsolete: comment-secret -->
                <beans>
                  <password>element-secret</password>
                  <property name="password" value="attribute-secret"/>
                  <entry key="api-key">entry-secret</entry>
                  <client token="token-secret" url="https://user:url-secret@host/service"/>
                  <name>customer</name><!-- password: inner-secret -->
                </beans>
                """);
        assertThat(result).contains("[REDACTED]", "customer")
                .doesNotContain("comment-secret", "element-secret", "attribute-secret", "entry-secret", "token-secret", "url-secret", "inner-secret");
        XmlSupport.parse(result);
    }

    @Test
    void rejectsUnsafeYamlTagsDuplicateKeysAndRecursiveValues() {
        assertThatThrownBy(() -> redactor.yaml("data: !!java.net.URL ['https://example.org']"))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> redactor.yaml("password: first\npassword: second"))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> redactor.yaml("data: &cycle [*cycle]"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Recursive");
    }
}
