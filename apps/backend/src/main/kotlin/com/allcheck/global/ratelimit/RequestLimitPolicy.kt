package com.allcheck.global.ratelimit

import java.time.Duration

data class RequestLimitPolicy(val scope: String, val maxRequests: Int, val window: Duration) {
    init {
        require(scope.matches(Regex("[a-z][a-z-]{0,62}")))
        require(maxRequests in 1 until Int.MAX_VALUE)
        require(!window.isNegative && !window.isZero && window.nano == 0)
    }
}
