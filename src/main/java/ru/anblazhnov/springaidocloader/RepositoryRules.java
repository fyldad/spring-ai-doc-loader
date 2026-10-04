package ru.anblazhnov.springaidocloader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.jgit.ignore.FastIgnoreRule;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

final class RepositoryRules {
    private final Path root;
    private final boolean respectGitignore;
    private final List<FastIgnoreRule> exclusions;
    private final Map<Path, List<FastIgnoreRule>> ignoreFiles = new HashMap<>();
    private final Set<Path> trackedFiles = new HashSet<>();
    private final Set<Path> trackedDirectories = new HashSet<>();
    private final Path gitRoot;
    private final Path gitDirectory;
    private final long maxRuleFileSize;

    RepositoryRules(Path root, DiscoveryProperties properties) throws IOException {
        this.root = root;
        respectGitignore = properties.isRespectGitignore();
        maxRuleFileSize = properties.getMaxFileSize();
        exclusions = properties.getExcludedPaths().stream().map(FastIgnoreRule::new).toList();
        var builder = new FileRepositoryBuilder().findGitDir(root.toFile());
        if (respectGitignore && builder.getGitDir() != null) {
            try (var repository = builder.build()) {
                gitRoot = repository.getWorkTree().toPath().toAbsolutePath().normalize();
                gitDirectory = repository.getDirectory().toPath();
                var index = repository.readDirCache();
                for (int entry = 0; entry < index.getEntryCount(); entry++) {
                    Path tracked = gitRoot.resolve(index.getEntry(entry).getPathString()).normalize();
                    if (!tracked.startsWith(root)) continue;
                    trackedFiles.add(tracked);
                    for (Path parent = tracked.getParent(); parent != null && parent.startsWith(root); parent = parent.getParent()) {
                        trackedDirectories.add(parent);
                    }
                }
            }
        }
        else {
            gitRoot = root;
            gitDirectory = root.resolve(".git");
        }
    }

    String exclusion(Path path, boolean directory) throws IOException {
        String relative = portable(root.relativize(path));
        if (matches(exclusions, relative, directory, false)) return "configured_path";
        if (!respectGitignore) return null;
        // Ignore patterns do not remove tracked files, or directories needed to reach them.
        if (trackedFiles.contains(path) || directory && trackedDirectories.contains(path)) return null;
        // A tracked descendant may force traversal of an otherwise ignored directory.
        // Untracked children are still ignored; a child negation cannot reopen its parent.
        for (Path parent = path.getParent(); parent != null && parent.startsWith(gitRoot)
                && !parent.equals(gitRoot); parent = parent.getParent()) {
            if (ignored(parent, true)) return "gitignore";
        }
        return ignored(path, directory) ? "gitignore" : null;
    }

    private boolean ignored(Path path, boolean directory) throws IOException {
        boolean ignored = false;
        // Git's repository-local exclude rules precede .gitignore rules.
        if (Files.isDirectory(gitDirectory, LinkOption.NOFOLLOW_LINKS)) {
            ignored = matches(load(gitDirectory.resolve("info/exclude")), portable(gitRoot.relativize(path)), directory, ignored);
        }
        Path parent = path.getParent();
        List<Path> ancestors = new ArrayList<>();
        while (parent != null && parent.startsWith(gitRoot)) {
            ancestors.addFirst(parent);
            if (parent.equals(gitRoot)) break;
            parent = parent.getParent();
        }
        for (Path ancestor : ancestors) {
            ignored = matches(load(ancestor.resolve(".gitignore")), portable(ancestor.relativize(path)), directory, ignored);
        }
        return ignored;
    }

    private List<FastIgnoreRule> load(Path file) throws IOException {
        if (ignoreFiles.containsKey(file)) return ignoreFiles.get(file);
        List<FastIgnoreRule> rules = new ArrayList<>();
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes;
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes((int) maxRuleFileSize + 1);
            }
            if (bytes.length > maxRuleFileSize) throw new IOException("Ignore rules exceed max-file-size");
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            if (text.startsWith("\ufeff")) text = text.substring(1);
            for (String line : text.split("\\R")) rules.add(new FastIgnoreRule(line));
        }
        ignoreFiles.put(file, rules);
        return rules;
    }

    private static boolean matches(List<FastIgnoreRule> rules, String path, boolean directory, boolean previous) {
        boolean result = previous;
        for (FastIgnoreRule rule : rules) {
            if (rule.isMatch(path, directory, true)) result = rule.getResult();
        }
        return result;
    }

    static String portable(Path path) { return path.toString().replace('\\', '/'); }
}
