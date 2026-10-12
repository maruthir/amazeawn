package com.amazeawn.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Import from each service's application class to pick up the outbox, idempotency and messaging beans.
 */
@Configuration
@ComponentScan(basePackages = "com.amazeawn.common")
// Declaring these here replaces Boot's default (importing-class package) scan, so cover the whole tree.
@EntityScan(basePackages = "com.amazeawn")
@EnableJpaRepositories(basePackages = "com.amazeawn")
public class CommonAutoConfiguration {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .findAndRegisterModules();
    }
}
