package com.allcheck.reception.domain.vo

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource

class ReceptionExternalIdTest {
    @ParameterizedTest
    @EnumSource(ReceptionExternalId::class)
    fun `자원별 외부 문자열을 내부 Long으로 왕복하며 Long 최대값도 숫자로 직렬화하지 않는다`(type: ReceptionExternalId) {
        // given
        val id = Long.MAX_VALUE

        // when
        val external = type.format(id)

        // then
        assertThat(type.parse(external)).isEqualTo(id)
        assertThat(external).endsWith(id.toString())
        assertThat(external.length).isLessThanOrEqualTo(64)
        assertThrows<IllegalArgumentException> { type.format(0) }
    }

    @Test
    fun `명세 예시의 앞자리 0은 허용하며 출력은 접두어와 십진수로 구성한다`() {
        // when & then
        assertThat(ReceptionExternalId.RECEPTION.parse("rec_01")).isEqualTo(1L)
        assertThat(ReceptionExternalId.RECEPTION.format(1)).isEqualTo("rec_1")
        assertThat(ReceptionExternalId.RECORDING.format(2)).isEqualTo("file_rec_2")
        assertThat(ReceptionExternalId.ANALYSIS.format(3)).isEqualTo("anl_3")
        assertThat(ReceptionExternalId.TRANSCRIPT.format(4)).isEqualTo("file_stt_4")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "1", "rec_", "rec_0", "rec_-1", "rec_+1", "rec_ 1", "rec_1 ", "rec_1.0", "rec_9223372036854775808", "anl_1", "file_rec_1"])
    fun `잘못된 자원 유형과 범위 및 숫자 형식은 변환하지 않는다`(external: String) {
        // when
        val exception = assertThrows<BusinessException> { ReceptionExternalId.RECEPTION.parse(external) }

        // then
        assertThat(exception.errorCode).isSameAs(GlobalErrorCode.INVALID_INPUT_VALUE)
    }
}
