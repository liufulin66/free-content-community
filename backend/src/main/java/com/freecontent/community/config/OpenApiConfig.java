package com.freecontent.community.config;

import com.freecontent.community.common.error.ProblemDetailResponse;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI freeContentOpenAPI() {
        Components components = new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("Opaque Token")
                .description("服务端 Redis 管理的不透明 Token，不使用 JWT"));
        ModelConverters.getInstance(true).readAll(ProblemDetailResponse.class).forEach(components::addSchemas);
        return new OpenAPI()
                .info(new Info()
                        .title("免费技术内容社区 API")
                        .version("1.0.0")
                        .description("后端骨架；完整接口契约以项目文档 openapi.json 为准"))
                .components(components);
    }
}
