package com.allcheck.reception.domain.entity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant

class ReceptionTranscriptTest {
    @Test
    fun `유효 원문의 앞뒤 공백과 혼합 줄바꿈 및 유니코드를 변경하지 않는다`() {
        // given
        val original = "  고객: 물이 샙니다.\r\n관리자: 내일 방문하겠습니다.\n\t고객: 🙂 감사합니다.  \r\n"

        // when
        val transcript = transcript(original)

        // then
        assertThat(transcript.transcript).isSameAs(original)
        assertThat(transcript.transcriptFileId).isEqualTo("transcript-1")
        assertThat(transcript.receptionId).isEqualTo("reception-1")
        assertThat(transcript.recordingFileId).isEqualTo("recording-1")
        assertThat(transcript.producedByAnalysisRequestId).isEqualTo("analysis-1")
        assertThat(transcript.storedAt).isEqualTo(STORED_AT)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " \t\r\n", "\u00A0", "\u2003\u3000"])
    fun `비어 있거나 유니코드 공백뿐인 응답은 정식 원문으로 만들지 않는다`(original: String) {
        // when & then
        assertThrows<IllegalArgumentException> { transcript(original) }
    }

    @Test
    fun `AI 계약의 원문 길이 한도는 유니코드 문자 수로 계산한다`() {
        // given
        val original = "🙂".repeat(100_000)

        // when
        val transcript = transcript(original)

        // then
        assertThat(transcript.transcript).isEqualTo(original)
        assertThat(transcript.transcript.length).isEqualTo(200_000)
    }

    @Test
    fun `AI 계약 한도를 넘는 원문은 자르지 않고 거부한다`() {
        // given
        val original = "가".repeat(100_001)

        // when & then
        assertThrows<IllegalArgumentException> { transcript(original) }
        assertThat(original.length).isEqualTo(100_001)
    }

    private fun transcript(original: String) = ReceptionTranscript(
        transcriptFileId = "transcript-1",
        receptionId = "reception-1",
        recordingFileId = "recording-1",
        producedByAnalysisRequestId = "analysis-1",
        transcript = original,
        storedAt = STORED_AT,
    )

    private companion object {
        val STORED_AT = Instant.parse("2026-11-16T00:00:00Z")
    }
}
