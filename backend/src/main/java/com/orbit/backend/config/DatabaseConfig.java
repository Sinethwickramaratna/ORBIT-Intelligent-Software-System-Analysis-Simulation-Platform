package com.orbit.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class DatabaseConfig {

    /**
     * Replaces Spring Boot's auto-configured pool. The real pool is created by {@link DatabaseProvisioner} after the
     * credentials are known; Hibernate is configured (see application.yaml) not to touch the database at startup.
     */
    @Bean
    public DynamicDataSource dataSource() {
        return new DynamicDataSource();
    }
}
