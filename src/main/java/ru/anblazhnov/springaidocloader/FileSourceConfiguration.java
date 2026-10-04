package ru.anblazhnov.springaidocloader;

import java.util.function.Supplier;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({DiscoveryProperties.class, ChunkingProperties.class, JavaParsingProperties.class})
public class FileSourceConfiguration {

    @Bean
    Supplier<Flux<DiscoveredFile>> fileTreeSupplier(DiscoveryProperties properties, ChunkingProperties chunks) {
        return () -> Flux.generate(
                        () -> new ProjectDiscovery(properties, chunks.getSnapshotDirectory()),
                        (discovery, sink) -> {
                            DiscoveredFile file = discovery.next();
                            if (file == null) {
                                sink.complete();
                            }
                            else {
                                sink.next(file);
                            }
                            return discovery;
                        },
                        ProjectDiscovery::close)
                .cast(DiscoveredFile.class)
                .subscribeOn(Schedulers.boundedElastic());
    }
}
