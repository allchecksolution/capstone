package com.allcheck.global.config

import com.fasterxml.jackson.annotation.JsonInclude
import org.hibernate.cfg.MappingSettings
import org.hibernate.type.format.jackson.Jackson3JsonFormatMapper
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.databind.json.JsonMapper

@Configuration(proxyBeanMethods = false)
class HibernateConfig {
    /** JSONB의 날짜를 ISO 문자열로 저장하고 역직렬화할 때 원본 오프셋을 유지한다. */
    @Bean
    fun jsonFormatMapperCustomizer(): HibernatePropertiesCustomizer {
        val mapper = JsonMapper.builder()
            .findAndAddModules()
            .changeDefaultPropertyInclusion { it.withValueInclusion(JsonInclude.Include.NON_NULL) }
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DateTimeFeature.WRITE_DATES_WITH_CONTEXT_TIME_ZONE)
            .disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
            .build()

        return HibernatePropertiesCustomizer { properties ->
            properties[MappingSettings.JSON_FORMAT_MAPPER] = Jackson3JsonFormatMapper(mapper)
        }
    }
}
