package ru.anblazhnov.springaidocloader;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@Configuration(proxyBeanMethods = false)
public class FileSourceConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FileSourceConfiguration.class);

    @Bean
    Supplier<Flux<File>> fileTreeSupplier(
            @Value("${file.supplier.directory}") Path directory,
            @Value("${file.supplier.filename-regex:.*}") String filenameRegex,
            @Value("${file.supplier.excluded-directories:}") String excludedDirectories) {

        Pattern filenamePattern = Pattern.compile(filenameRegex);
        Set<String> excludedDirectoryNames = Arrays.stream(excludedDirectories.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toUnmodifiableSet());

        return () -> Flux.generate(
                        () -> new FileTreeWalker(directory, filenamePattern, excludedDirectoryNames),
                        (walker, sink) -> {
                            Path file = walker.next();
                            if (file == null) {
                                sink.complete();
                            }
                            else {
                                sink.next(file.toFile());
                            }
                            return walker;
                        },
                        FileTreeWalker::close)
                .cast(File.class)
                .doOnSubscribe(_ -> log.info("Scanning files under {}", directory))
                .doOnComplete(() -> log.info("Finished scanning files under {}", directory))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
