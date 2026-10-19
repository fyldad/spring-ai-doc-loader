package ru.anblazhnov.springaidocloader;

import java.util.function.Supplier;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({DiscoveryProperties.class, ChunkingProperties.class, JavaParsingProperties.class, XmlParsingProperties.class, WriterProperties.class})
public class FileSourceConfiguration {

    @Bean
    LoadedFileState loadedFileState(WriterProperties writer, Environment environment, DiscoveryProperties discovery,
                                   ChunkingProperties chunks, JavaParsingProperties java, XmlParsingProperties xml) {
        return new LoadedFileState(writer.getStateFile(),
                LoadedFileState.settingsHash(environment, discovery, chunks, java, xml));
    }

    @Bean
    Supplier<Flux<DiscoveredFile>> fileTreeSupplier(DiscoveryProperties properties, LoadedFileState loadedState) {
        return () -> Flux.generate(
                        () -> new ProjectDiscovery(properties),
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
                .filter(file -> !loadedState.isStateFile(file.path()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
