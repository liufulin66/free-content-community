package com.freecontent.community.content.workspace.config;

import com.freecontent.community.content.workspace.dto.CreateContentRequest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springdoc.core.customizers.OpenApiCustomizer;
import io.swagger.v3.oas.models.media.Schema;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ContentCreationProperties.class)
public class ContentCreationConfiguration {
    @Bean
    @SuppressWarnings("unchecked")
    public OpenApiCustomizer contentCreationDefaults() {
        return api -> {
            if (api.getComponents() == null || api.getComponents().getSchemas() == null) return;
            Schema<?> request = api.getComponents().getSchemas().get("CreateContentRequest");
            if (request == null || request.getProperties() == null) return;
            for (String name : List.of("title", "bodyMarkdown", "imageAssetIds", "tagIds")) {
                Schema<Object> field = (Schema<Object>) request.getProperties().get(name);
                if (field != null) field.setDefault(name.endsWith("Ids") ? List.of() : "");
            }
        };
    }
}
