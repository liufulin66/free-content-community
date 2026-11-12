package com.freecontent.community.content.browse.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springdoc.core.customizers.OpenApiCustomizer;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({PublicContentQueryProperties.class, PublicContentCursorProperties.class, PublishedMediaAccessProperties.class})
public class PublicContentConfiguration {
    @Bean
    public OpenApiCustomizer optionalPublicContentAuthentication() {
        return api -> {
            if (api.getPaths() != null) {
                for (String route : List.of("/api/v1/contents", "/api/v1/contents/{contentId}", "/api/v1/users/{userId}/contents",
                        "/api/v1/contents/{contentId}/media/{assetId}/access")) {
                    var path = api.getPaths().get(route);
                    if (path != null && path.getGet() != null) path.getGet().setSecurity(
                            List.of(new SecurityRequirement(), new SecurityRequirement().addList("bearerAuth")));
                }
            }
            if (api.getComponents() == null || api.getComponents().getSchemas() == null) return;
            var page = api.getComponents().getSchemas().get("ContentPage");
            if (page == null) return;
            var condition = new ObjectSchema();
            condition.addProperty("hasMore", new BooleanSchema()._const(true));
            condition.addRequiredItem("hasMore");
            var continuing = new ObjectSchema();
            var nonemptyCursor = new Schema<>();
            nonemptyCursor.setTypes(java.util.Set.of("string"));
            nonemptyCursor.setMinLength(1);
            continuing.addProperty("nextCursor", nonemptyCursor);
            var terminal = new ObjectSchema();
            var nullCursor = new Schema<>();
            nullCursor.setTypes(java.util.Set.of("null"));
            terminal.addProperty("nextCursor", nullCursor);
            var rule = new Schema<>();
            rule.setIf(condition);
            rule.setThen(continuing);
            rule.setElse(terminal);
            page.setAllOf(List.of(rule));
        };
    }
}
