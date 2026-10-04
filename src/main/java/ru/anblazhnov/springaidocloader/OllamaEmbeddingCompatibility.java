package ru.anblazhnov.springaidocloader;

import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingProperties;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.aopalliance.intercept.MethodInterceptor;

/** Spring AI 2.0.1 drops Ollama defaults when merging generic runtime options (Qdrant's batch path). */
@Configuration(proxyBeanMethods = false)
class OllamaEmbeddingCompatibility {
    @Bean
    static BeanPostProcessor preserveOllamaEmbeddingOptions(ObjectProvider<OllamaEmbeddingProperties> properties) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof OllamaEmbeddingModel ? adapt(bean, properties.getObject()) : bean;
            }
        };
    }

    static Object adapt(Object model, OllamaEmbeddingProperties properties) {
        ProxyFactory proxy = new ProxyFactory(model);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice((MethodInterceptor) invocation -> {
            Object[] arguments = invocation.getArguments();
            for (int index = 0; index < arguments.length; index++) {
                if (arguments[index] instanceof EmbeddingOptions options) {
                    arguments[index] = runtimeOptions(options, properties);
                }
                else if (arguments[index] instanceof EmbeddingRequest request && request.getOptions() != null) {
                    arguments[index] = new EmbeddingRequest(request.getInstructions(), runtimeOptions(request.getOptions(), properties));
                }
            }
            return invocation.proceed();
        });
        return proxy.getProxy();
    }

    private static EmbeddingOptions runtimeOptions(EmbeddingOptions options, OllamaEmbeddingProperties properties) {
        if (options instanceof OllamaEmbeddingOptions) return options;
        return OllamaEmbeddingOptions.builder()
                .model(options.getModel() == null ? properties.toOptions().getModel() : options.getModel())
                .dimensions(options.getDimensions())
                .truncate(properties.getTruncate())
                .build();
    }
}
