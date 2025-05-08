package com.deployProject.config

import org.modelmapper.Conditions
import org.modelmapper.ModelMapper
import org.modelmapper.convention.MatchingStrategies
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ModelMapperConfig {

    @Bean
    fun modelMapper(): ModelMapper {
        return ModelMapper().apply {
            // 엄격 매칭 전략 (필드명이 정확히 일치해야 매핑됩니다)
            configuration.matchingStrategy = MatchingStrategies.STRICT
            // null 값은 매핑하지 않음
            configuration.propertyCondition = Conditions.isNotNull()
        }
    }
}
