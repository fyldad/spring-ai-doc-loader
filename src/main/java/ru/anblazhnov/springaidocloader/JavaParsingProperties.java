package ru.anblazhnov.springaidocloader;

import java.nio.file.Path;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ingestion.java")
public class JavaParsingProperties {
    private String languageLevel = "JAVA_25";
    private boolean resolveSymbols = false;
    private List<Path> sourceRoots = List.of();
    private List<Path> dependencyJars = List.of();

    public String getLanguageLevel() { return languageLevel; }
    public void setLanguageLevel(String languageLevel) { this.languageLevel = languageLevel; }
    public boolean isResolveSymbols() { return resolveSymbols; }
    public void setResolveSymbols(boolean resolveSymbols) { this.resolveSymbols = resolveSymbols; }
    public List<Path> getSourceRoots() { return sourceRoots; }
    public void setSourceRoots(List<Path> sourceRoots) { this.sourceRoots = List.copyOf(sourceRoots); }
    public List<Path> getDependencyJars() { return dependencyJars; }
    public void setDependencyJars(List<Path> dependencyJars) { this.dependencyJars = List.copyOf(dependencyJars); }
}
