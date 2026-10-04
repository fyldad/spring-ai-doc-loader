package ru.anblazhnov.springaidocloader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

final class RepositoryRevision {
    static Map<String, Object> read(Path root) {
        var builder = new FileRepositoryBuilder().findGitDir(root.toFile());
        if (builder.getGitDir() == null) return Map.of("revision_status", "unversioned");
        try (var repository = builder.build(); var git = new Git(repository)) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            var head = repository.resolve("HEAD");
            metadata.put("revision_status", head == null ? "unborn" : "available");
            if (head != null) metadata.put("revision", head.name());
            metadata.put("dirty_worktree", !git.status().call().isClean());
            return Map.copyOf(metadata);
        }
        catch (IOException | GitAPIException error) {
            return Map.of("revision_status", "unavailable");
        }
    }
}
