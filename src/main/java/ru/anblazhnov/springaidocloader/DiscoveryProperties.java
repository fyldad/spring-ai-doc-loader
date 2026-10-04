package ru.anblazhnov.springaidocloader;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("file.supplier")
public class DiscoveryProperties {

    private Path directory = Path.of(".");
    private List<Repository> repositories = new ArrayList<>();
    private String filenameRegex = "(?i).*\\.(java|xml|wsdl|xsd|md|txt|yaml|yml|properties)";
    private String excludedDirectories = ".git,.gradle,.idea,build,target,node_modules";
    private List<String> excludedPaths = new ArrayList<>();
    private boolean respectGitignore = true;
    private boolean excludeGenerated = false;
    private long maxFileSize = 2 * 1024 * 1024;
    private String encoding = "UTF-8";
    private Path report = Path.of("build/reports/discovery.csv");
    private String sensitiveKeyPattern = "(?i).*(password|passwd|pwd|secret|token|api[-_.]?key|credential|private[-_.]?key|authorization|access[-_.]?key|connection[-_.]?string).*";

    public record Repository(String id, Path root) { }

    void validate() {
        if (maxFileSize < 1 || maxFileSize >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("file.supplier.max-file-size must be between 1 and 2147483646 bytes");
        }
        Charset.forName(encoding);
    }

    public Path getDirectory() { return directory; }
    public void setDirectory(Path directory) { this.directory = directory; }
    public List<Repository> getRepositories() { return repositories; }
    public void setRepositories(List<Repository> repositories) { this.repositories = repositories; }
    public String getFilenameRegex() { return filenameRegex; }
    public void setFilenameRegex(String filenameRegex) { this.filenameRegex = filenameRegex; }
    public String getExcludedDirectories() { return excludedDirectories; }
    public void setExcludedDirectories(String excludedDirectories) { this.excludedDirectories = excludedDirectories; }
    public List<String> getExcludedPaths() { return excludedPaths; }
    public void setExcludedPaths(List<String> excludedPaths) { this.excludedPaths = excludedPaths; }
    public boolean isRespectGitignore() { return respectGitignore; }
    public void setRespectGitignore(boolean respectGitignore) { this.respectGitignore = respectGitignore; }
    public boolean isExcludeGenerated() { return excludeGenerated; }
    public void setExcludeGenerated(boolean excludeGenerated) { this.excludeGenerated = excludeGenerated; }
    public long getMaxFileSize() { return maxFileSize; }
    public void setMaxFileSize(long maxFileSize) { this.maxFileSize = maxFileSize; }
    public String getEncoding() { return encoding; }
    public void setEncoding(String encoding) { this.encoding = encoding; }
    public Path getReport() { return report; }
    public void setReport(Path report) { this.report = report; }
    public String getSensitiveKeyPattern() { return sensitiveKeyPattern; }
    public void setSensitiveKeyPattern(String sensitiveKeyPattern) { this.sensitiveKeyPattern = sensitiveKeyPattern; }
}
