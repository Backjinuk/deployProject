package com.deployProject.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig : WebMvcConfigurer {

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/**")
            .allowedOriginPatterns(
                "http://localhost:[*]",
                "http://127.0.0.1:[*]",
                "http://backjin.iptime.org:[*]",
                "https://deploy.jinuk.dev"
            )
            .allowedMethods("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS")
    }
}
