package com.example.agent.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 文档配置
 * - 访问路径:/swagger-ui.html(Swagger UI) / /v3/api-docs(JSON)
 * - 配置全局 Bearer JWT 认证,在 Swagger UI 右上角 Authorize 填 token 即可测需登录接口
 */
@Configuration
public class OpenApiConfig {

    /** 供 Controller @SecurityRequirement 注解引用的 scheme 名称 */
    public static final String SECURITY_SCHEME_NAME = "bearer-jwt";

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Smart Knowledge Agent API")
                        .description("基于 RAG 和 Agent 工具调用的智能知识库系统 - 接口文档")
                        .version("v1")
                        .contact(new Contact().name("wibecoding").email("dev@wibecoding.com"))
                        .license(new License().name("MIT")))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .in(SecurityScheme.In.HEADER)
                                        .name("Authorization")));
    }
}
