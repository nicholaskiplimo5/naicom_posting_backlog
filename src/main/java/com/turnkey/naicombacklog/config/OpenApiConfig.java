package com.turnkey.naicombacklog.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI naicomBacklogPosterOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("NAICOM Backlog Poster API")
                        .description("Standalone service to select, stage and post backlog NAICOM policy transactions")
                        .version("1.0.0")
                        .contact(new Contact().name("Turnkey")));
    }
}
