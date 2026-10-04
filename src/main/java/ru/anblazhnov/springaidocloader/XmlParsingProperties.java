package ru.anblazhnov.springaidocloader;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ingestion.xml")
public class XmlParsingProperties {
    private int maxImportDepth = 16;
    private int maxImportFiles = 128;
    private List<String> elementNames = new ArrayList<>(List.of("bean", "route", "endpoint"));
    private String mavenResolutionPolicy = "declared";

    public int getMaxImportDepth() { return maxImportDepth; }
    public void setMaxImportDepth(int value) { maxImportDepth = value; }
    public int getMaxImportFiles() { return maxImportFiles; }
    public void setMaxImportFiles(int value) { maxImportFiles = value; }
    public List<String> getElementNames() { return elementNames; }
    public void setElementNames(List<String> value) { elementNames = value; }
    public String getMavenResolutionPolicy() { return mavenResolutionPolicy; }
    public void setMavenResolutionPolicy(String value) { mavenResolutionPolicy = value; }
    void validate() {
        if (maxImportDepth < 0 || maxImportFiles < 1) throw new IllegalArgumentException("Invalid XML import bounds");
        if (elementNames == null || elementNames.stream().anyMatch(value -> value == null || value.isBlank()))
            throw new IllegalArgumentException("XML element names must be nonblank");
        if (!List.of("declared", "local-properties").contains(mavenResolutionPolicy))
            throw new IllegalArgumentException("Maven policy must be declared or local-properties");
    }
}
