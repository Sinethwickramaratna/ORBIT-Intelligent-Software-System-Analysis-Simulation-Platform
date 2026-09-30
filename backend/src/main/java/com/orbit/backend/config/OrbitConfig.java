package com.orbit.backend.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Feature switches kept out of BackendApplication so the generated main class stays untouched. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrbitProperties.class)
@EnableScheduling
public class OrbitConfig {
}
