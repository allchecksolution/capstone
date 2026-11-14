package com.allcheck.global.exception

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BusinessExceptionTest {
    @Test
    fun `비즈니스 예외는 에러 코드의 메시지와 선택적인 원인을 보존한다`() {
        // given
        val errorCode = GlobalErrorCode.INVALID_INPUT_VALUE
        val cause = IllegalStateException("sensitive-cause")

        // when
        val exception = BusinessException(errorCode, cause)
        val exceptionWithoutCause = BusinessException(errorCode)

        // then
        assertThat(exception.errorCode).isSameAs(errorCode)
        assertThat(exception.message).isEqualTo(errorCode.message)
        assertThat(exception.cause).isSameAs(cause)
        assertThat(exceptionWithoutCause.errorCode).isSameAs(errorCode)
        assertThat(exceptionWithoutCause.message).isEqualTo(errorCode.message)
        assertThat(exceptionWithoutCause.cause).isNull()
    }
}
