package ru.anblazhnov.springaidocloader;

import org.springframework.cloud.fn.common.config.ComponentCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.file.dsl.FileInboundChannelAdapterSpec;

@Configuration(proxyBeanMethods = false)
public class FileSourceConfiguration {

    @Bean
    ComponentCustomizer<FileInboundChannelAdapterSpec> recursiveFileSource() {
        // Poll the entire tree, including files already present on the first poll.
        return source -> source.recursive(true);
    }
}
