package com.freecontent.community.tag.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springdoc.core.customizers.OpenApiCustomizer;
import io.swagger.v3.oas.models.media.Schema;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TagCreationProperties.class)
public class TagCreationConfiguration {
    @Bean
    @SuppressWarnings("unchecked")
    public OpenApiCustomizer tagCreationSchemaDefaults() {
        return api -> {
            if (api.getComponents() == null || api.getComponents().getSchemas() == null) return;
            Schema<?> request = api.getComponents().getSchemas().get("TagWriteRequest");
            if (request == null || request.getProperties() == null) return;
            Schema<Object> description = (Schema<Object>) request.getProperties().get("description");
            // 注解的空字符串是“未声明默认值”，在最终schema中显式补上业务默认值。
            if (description != null) description.setDefault("");
        };
    }
}
