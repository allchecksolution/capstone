package com.allcheck.reception.domain.vo.review

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import java.time.OffsetDateTime

data class ReceptionCreationData(val callerPhone: String? = null, val callStartedAt: OffsetDateTime? = null) {
    init {
        if (callerPhone != null && !Regex("[0-9+-]{9,20}").matches(callerPhone)) throw BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE)
    }
}
