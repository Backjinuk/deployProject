package com.deployProject.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig : WebMvcConfigurer {

    override fun addCorsMappings(registry: CorsRegistry) {
        // 수정 이유: Origin 값에는 trailing slash가 없어야 매칭이 정확하다.
        registry.addMapping("/**")
            .allowedOriginPatterns(
                "http://localhost:*",
                "http://127.0.0.1:*",
                "http://backjin.iptime.org:*"
            )
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")

    }
}
