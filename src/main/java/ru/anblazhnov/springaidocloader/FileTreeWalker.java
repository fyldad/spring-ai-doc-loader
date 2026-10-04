package ru.anblazhnov.springaidocloader;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class FileTreeWalker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FileTreeWalker.class);

    private final BiPredicate<Path, BasicFileAttributes> selector;
    private final BiConsumer<Path, IOException> failureHandler;
    private final Deque<DirectoryCursor> directories = new ArrayDeque<>();

    FileTreeWalker(Path root, Pattern filenamePattern, Set<String> excludedDirectoryNames) throws IOException {
        this(root, (path, attributes) -> attributes.isDirectory()
                ? !excludedDirectoryNames.contains(path.getFileName().toString())
                : attributes.isRegularFile() && filenamePattern.matcher(path.getFileName().toString()).matches(),
                (path, error) -> log.warn("Skipping inaccessible path: {}", path, error));
    }

    FileTreeWalker(Path root, BiPredicate<Path, BasicFileAttributes> selector,
            BiConsumer<Path, IOException> failureHandler) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("File supplier directory does not exist or is not a directory: " + root);
        }
        this.selector = selector;
        this.failureHandler = failureHandler;
        openDirectory(root);
    }

    Path next() {
        while (!directories.isEmpty()) {
            DirectoryCursor cursor = directories.peek();
            final Path path;
            try {
                if (!cursor.iterator().hasNext()) {
                    closeCurrentDirectory();
                    continue;
                }
                path = cursor.iterator().next();
            }
            catch (DirectoryIteratorException error) {
                closeCurrentDirectory();
                failureHandler.accept(cursor.path(), error.getCause());
                continue;
            }

            final BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            }
            catch (IOException error) {
                failureHandler.accept(path, error);
                continue;
            }

            if (!selector.test(path, attributes)) continue;
            if (attributes.isDirectory()) {
                try {
                    openDirectory(path);
                }
                catch (IOException error) {
                    failureHandler.accept(path, error);
                }
            }
            else if (attributes.isRegularFile()) {
                return path;
            }
        }
        return null;
    }

    private void openDirectory(Path directory) throws IOException {
        DirectoryStream<Path> stream = Files.newDirectoryStream(directory);
        directories.push(new DirectoryCursor(directory, stream, stream.iterator()));
    }

    private void closeCurrentDirectory() {
        DirectoryCursor cursor = directories.pop();
        try {
            cursor.stream().close();
        }
        catch (IOException error) {
            log.debug("Cannot close directory stream: {}", cursor.path(), error);
        }
    }

    @Override
    public void close() {
        while (!directories.isEmpty()) {
            closeCurrentDirectory();
        }
    }

    private record DirectoryCursor(Path path, DirectoryStream<Path> stream, Iterator<Path> iterator) {
    }
}
