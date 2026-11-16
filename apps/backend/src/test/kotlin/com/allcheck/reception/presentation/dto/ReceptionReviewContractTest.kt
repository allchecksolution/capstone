package com.allcheck.reception.presentation.dto

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import com.allcheck.reception.domain.entity.Reception
import com.allcheck.reception.domain.vo.ReceptionReviewStatus
import com.allcheck.reception.presentation.dto.request.ConfirmReceptionRequest
import com.allcheck.reception.presentation.dto.request.ReopenReceptionRequest
import com.allcheck.reception.presentation.dto.response.ReceptionConfirmedResponse
import com.allcheck.reception.presentation.dto.response.ReceptionFieldsResponse
import com.allcheck.reception.presentation.dto.response.ReceptionReopenedResponse
import jakarta.validation.Validation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Instant

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReceptionReviewContractTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val validatorFactory = Validation.buildDefaultValidatorFactory()
    private val validator = validatorFactory.validator

    @Test
    fun `notes는 생략할 수 있지만 현재 명세에서 허용하지 않는 명시적 null은 거부한다`() {
        // given
        val json = mapper.readTree(VALID_REQUEST) as ObjectNode
        (json.get("finalData") as ObjectNode).putNull("notes")

        // when & then
        assertThrows<JacksonException> {
            mapper.readValue(mapper.writeValueAsString(json), ConfirmReceptionRequest::class.java)
        }
    }

    @Test
    fun `전체 검토 JSON을 변환하고 notes 생략을 최종 메모 없음으로 전달한다`() {
        // given
        val request = readRequest()

        // when
        val data = request.finalData.toConfirmationData()
        data.validateForConfirmation(NOW)

        // then
        assertThat(validator.validate(request)).isEmpty()
        assertThat(data.notes).isNull()
        assertThat(data.desiredVisitFrom.toInstant()).isEqualTo(Instant.parse("2026-11-17T05:00:00Z"))
        assertThat(data.contactPhone).isEqualTo("010-1234-5678")
    }

    @ParameterizedTest
    @ValueSource(strings = ["expectedVersion", "finalData", "workType", "symptoms", "address", "desiredVisitFrom", "desiredVisitTo", "contactPhone"])
    fun `필수 필드 누락을 기본값이나 부분 수정으로 받아들이지 않는다`(field: String) {
        // given
        val json = mapper.readTree(VALID_REQUEST) as ObjectNode
        val parent = if (field in listOf("expectedVersion", "finalData")) json else json.get("finalData") as ObjectNode
        parent.remove(field)

        // when & then
        val requestJson = mapper.writeValueAsString(json)
        assertThrows<JacksonException> { mapper.readValue(requestJson, ConfirmReceptionRequest::class.java) }
    }

    @Test
    fun `잘못된 버전과 중첩 최종값의 공백 및 전화번호를 요청 검증으로 거부한다`() {
        // given
        val original = readRequest()
        val request = original.copy(
            expectedVersion = 0,
            finalData = original.finalData.copy(symptoms = " ", address = "\n", contactPhone = "invalid"),
        )

        // when
        val paths = validator.validate(request).map { it.propertyPath.toString() }

        // then
        assertThat(paths).containsExactlyInAnyOrder("expectedVersion", "finalData.symptoms", "finalData.address", "finalData.contactPhone")
        assertThat(validator.validate(ReopenReceptionRequest(0))).extracting("propertyPath").hasSize(1)
    }

    @Test
    fun `코드 포인트 길이 상한의 이모지를 요청 단계에서 잘못 거부하지 않는다`() {
        // given
        val original = readRequest()
        val request = original.copy(finalData = original.finalData.copy(symptoms = "😀".repeat(2000), notes = "😀".repeat(2000)))

        // when & then
        assertThat(validator.validate(request)).isEmpty()
        request.finalData.toConfirmationData().validateForConfirmation(NOW)

        val tooLong = request.finalData.copy(notes = "😀".repeat(2001)).toConfirmationData()
        val exception = assertThrows<BusinessException> { tooLong.validateForConfirmation(NOW) }
        assertThat(exception.errorCode).isSameAs(GlobalErrorCode.INVALID_INPUT_VALUE)
    }

    @Test
    fun `확정 응답에는 검토된 전체값만 포함하고 AI 원본과 내부 버전은 포함하지 않는다`() {
        // given
        val reception = Reception("reception-1", "admin-1", NOW.minusSeconds(60))
        reception.confirm(1, readRequest().finalData.toConfirmationData(), null, NOW)
        val response = ReceptionConfirmedResponse(
            reception.receptionId,
            reception.reviewStatus,
            reception.version,
            checkNotNull(reception.confirmedAt),
            ReceptionFieldsResponse.from(reception.finalData),
        )

        // when
        val json = mapper.readTree(mapper.writeValueAsString(response))

        // then
        assertThat(json.propertyNames()).containsExactlyInAnyOrder("receptionId", "reviewStatus", "version", "confirmedAt", "finalData")
        assertThat(json.get("reviewStatus").asString()).isEqualTo("CONFIRMED")
        assertThat(json.get("finalData").propertyNames()).containsExactlyInAnyOrder(
            "workType",
            "symptoms",
            "address",
            "desiredVisitFrom",
            "desiredVisitTo",
            "contactPhone",
        )
    }

    @Test
    fun `확정 취소 응답은 해제된 confirmedAt을 null로 명시하며 notes를 보존한다`() {
        // given
        val reception = Reception("reception-1", "admin-1", NOW.minusSeconds(60))
        reception.confirm(1, readRequest().finalData.copy(notes = "방문 전 연락").toConfirmationData(), null, NOW)
        reception.reopen(2, NOW.plusSeconds(1))
        val response = ReceptionReopenedResponse(
            reception.receptionId,
            reception.reviewStatus,
            reception.version,
            reception.updatedAt,
            reception.confirmedAt,
            ReceptionFieldsResponse.from(reception.finalData),
        )

        // when
        val json = mapper.readTree(mapper.writeValueAsString(response))

        // then
        assertThat(response.reviewStatus).isEqualTo(ReceptionReviewStatus.DRAFT)
        assertThat(json.propertyNames()).containsExactlyInAnyOrder("receptionId", "reviewStatus", "version", "updatedAt", "confirmedAt", "finalData")
        assertThat(json.get("confirmedAt").isNull).isTrue()
        assertThat(json.get("finalData").get("notes").asString()).isEqualTo("방문 전 연락")
    }

    @AfterAll
    fun closeValidatorFactory() {
        validatorFactory.close()
    }

    private fun readRequest(): ConfirmReceptionRequest = mapper.readValue(VALID_REQUEST, ConfirmReceptionRequest::class.java)

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-17T06:00:00Z")
        val VALID_REQUEST = """
            {
              "expectedVersion": 1,
              "finalData": {
                "workType": "BLOCKAGE",
                "symptoms": "싱크대 배수 불가",
                "address": "서울시 강남구 예시로 10",
                "desiredVisitFrom": "2026-11-17T14:00:00+09:00",
                "desiredVisitTo": "2026-11-17T16:00:00+09:00",
                "contactPhone": "010-1234-5678"
              }
            }
        """.trimIndent()
    }
}
