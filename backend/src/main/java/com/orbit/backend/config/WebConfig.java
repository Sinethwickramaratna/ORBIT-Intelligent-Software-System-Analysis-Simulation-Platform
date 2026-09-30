package com.orbit.backend.config;

import com.orbit.backend.filter.DatabaseReadyInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final DatabaseReadyInterceptor databaseReadyInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // /api/setup/** must work before the database exists - it is how the database gets configured.
        registry.addInterceptor(databaseReadyInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/setup/**");
    }
}
