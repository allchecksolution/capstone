package com.allcheck.global.ratelimit

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode

class RequestLimitExceededException(val retryAfterSeconds: Long) : BusinessException(GlobalErrorCode.TOO_MANY_REQUESTS)
