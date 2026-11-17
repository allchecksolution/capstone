package com.allcheck.driver.infrastructure.business

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("driver.nts")
class NtsProperties(
    val serviceKey: String? = null,
    val connectTimeout: Duration = Duration.ofSeconds(2),
    val readTimeout: Duration = Duration.ofSeconds(5),
) {
    init {
        require(serviceKey == null || serviceKey.none { it.isISOControl() }) { "NTS service key must not contain control characters." }
        require(connectTimeout >= Duration.ofMillis(1)) { "NTS connect timeout must be at least 1 millisecond." }
        require(readTimeout >= Duration.ofMillis(1)) { "NTS read timeout must be at least 1 millisecond." }
    }
}
