package com.deployProject.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig : WebMvcConfigurer {

    override fun addCorsMappings(registry: CorsRegistry) {
        // 수정 이유: Origin 값에는 trailing slash가 없어야 매칭이 정확하다.
        registry.addMapping("/**")
            .allowedOrigins("http://localhost:3000", "http://localhost:8080", "http://backjin.iptime.org:8080")
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")

    }
}
