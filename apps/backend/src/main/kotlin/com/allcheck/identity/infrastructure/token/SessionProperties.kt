package com.allcheck.identity.infrastructure.token

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("identity.sessions")
class SessionProperties(
    val expiration: Duration = Duration.ofDays(14),
) {
    init {
        require(expiration >= Duration.ofSeconds(1)) { "Session expiration must be at least 1 second." }
    }
}
