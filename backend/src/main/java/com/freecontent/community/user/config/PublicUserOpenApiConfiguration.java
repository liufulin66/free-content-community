package com.freecontent.community.user.config;

import io.swagger.v3.oas.models.security.SecurityRequirement;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PublicUserOpenApiConfiguration {
    @Bean
    public OpenApiCustomizer optionalPublicUserAuthentication() {
        return api -> {
            if (api.getPaths() == null) return;
            var path = api.getPaths().get("/api/v1/users/{userId}");
            if (path != null && path.getGet() != null) {
                path.getGet().setSecurity(List.of(new SecurityRequirement(), new SecurityRequirement().addList("bearerAuth")));
            }
        };
    }
}
