package com.allcheck.reception.domain.vo

import com.allcheck.global.exception.BusinessException
import com.allcheck.reception.exception.ReceptionErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class WavRecordingTest {
    @Test
    fun `동일한 두 번호만 연락처로 추출하고 구분자는 제거하되 통화 시각은 해석하지 않는다`() {
        // when & then
        assertThat(WavRecording.contactPhone("rec_테스트_누수탐지_01012345678_01012345678@261117_103802.wav")).isEqualTo("01012345678")
        assertThat(WavRecording.contactPhone("rec_테스트업체_010-1234-5678_01012345678@261117_103802.WAV")).isEqualTo("01012345678")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "rec_테스트업체_01012345678_01098765432@261117_103802.wav",
            "rec_테스트업체_01012345678@261117_103802.wav",
            "call.wav",
            "rec_테스트업체_01012345678_01012345678@261117_103802.mp3",
            "rec_테스트업체_+++123456789_+++123456789@261117_103802.wav",
        ],
    )
    fun `번호가 모호하거나 알려진 파일명 규칙이 아니면 임의 선택하지 않는다`(name: String) {
        // when & then
        assertThat(WavRecording.contactPhone(name)).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave", "AUDIO/WAV"])
    fun `WAV MIME 별칭과 대소문자를 허용한다`(type: String) {
        // when & then
        WavRecording.validateMetadata("call.WAV", type)
    }

    @Test
    fun `확장자나 MIME이 WAV가 아니면 415 오류다`() {
        // when & then
        assertThat(assertThrows<BusinessException> { WavRecording.validateMetadata("call.mp3", "audio/wav") }.errorCode).isEqualTo(ReceptionErrorCode.RECORDING_FORMAT_NOT_SUPPORTED)
        assertThat(assertThrows<BusinessException> { WavRecording.validateMetadata("call.wav", "audio/mp4") }.errorCode).isEqualTo(ReceptionErrorCode.RECORDING_FORMAT_NOT_SUPPORTED)
    }

    @Test
    fun `WAVE 컨테이너 표식을 검사하고 다른 RIFF 파일이나 잘린 파일은 거부한다`() {
        // when & then
        for (container in listOf("RIFF", "RIFX", "RF64", "BW64")) assertThat(WavRecording.hasWaveHeader("${container}0000WAVE".toByteArray())).isTrue()
        assertThat(WavRecording.hasWaveHeader("RIFF0000AVI ".toByteArray())).isFalse()
        assertThat(WavRecording.hasWaveHeader("RIFFWAVE".toByteArray())).isFalse()
    }
}
