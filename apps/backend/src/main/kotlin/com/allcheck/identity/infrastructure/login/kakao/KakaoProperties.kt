package com.allcheck.identity.infrastructure.login.kakao

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("identity.kakao")
class KakaoProperties(
    val appId: Long? = null,
    val connectTimeout: Duration = Duration.ofSeconds(2),
    val readTimeout: Duration = Duration.ofSeconds(5),
) {
    init {
        require(appId == null || appId > 0) { "KAKAO_APP_ID must be a positive numeric Kakao application ID." }
        require(connectTimeout >= Duration.ofMillis(1)) { "Kakao connect timeout must be at least 1 millisecond." }
        require(readTimeout >= Duration.ofMillis(1)) { "Kakao read timeout must be at least 1 millisecond." }
    }
}
