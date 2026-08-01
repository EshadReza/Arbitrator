package com.labjudge.server.config;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.DeserializationFeature;

@Configuration
public class JacksonConfig {

    /**
     * Tolerate unknown fields so ADDING a DTO field in labjudge-common never
     * breaks an older client mid-contest (rules.md Rule 2: additions are safe).
     */
    @Bean
    Jackson2ObjectMapperBuilderCustomizer labjudgeJackson() {
        return builder -> builder
                .featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
