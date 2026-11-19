package com.allcheck.operations.domain

import com.allcheck.global.exception.BusinessException
import com.allcheck.operations.CancellationReason
import com.allcheck.operations.DeliveryStatus
import com.allcheck.operations.ReportContent
import com.allcheck.operations.WorkStatus
import com.allcheck.operations.dispatch.domain.entity.DispatchOffer
import com.allcheck.operations.work.domain.entity.WorkCancellation
import com.allcheck.operations.work.domain.entity.WorkOrder
import com.allcheck.operations.work.domain.entity.WorkReport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class OperationsDomainTest {
    private val now = Instant.parse("2026-10-31T14:59:00Z")

    @Test
    fun `만료 시각과 같으면 수락을 거절하고 공급자 성공은 후속 실패로 덮지 않는다`() {
        // given
        val offer = DispatchOffer(1, 1, 1, 100.0, now, now.plusSeconds(60))
        offer.delivery(DeliveryStatus.PROVIDER_ACCEPTED)
        // when
        offer.delivery(DeliveryStatus.FAILED)
        assertThrows<BusinessException> { offer.respond(true, now.plusSeconds(60)) }
        // then
        assertThat(offer.deliveryStatus).isEqualTo(DeliveryStatus.PROVIDER_ACCEPTED)
        assertThat(offer.respondedAt).isNull()
    }

    @Test
    fun `재방문 일정 변경은 이전 이력과 담당 작업을 유지한다`() {
        // given
        val work = inProgress()
        // when
        work.transition(4, WorkStatus.REVISIT_REQUIRED, "부품 재준비", null, now)
        work.planRevisit(5, now.plusSeconds(3600), now)
        // then
        assertThat(work.unfinished()).isTrue()
        assertThat(work.events().filter { it.status == WorkStatus.REVISIT_REQUIRED }).hasSize(2)
        assertThat(work.events().last().plannedRevisitAt).isEqualTo(now.plusSeconds(3600))
        assertThrows<BusinessException> { work.complete(6, now) }
    }

    @Test
    fun `취소 상세는 모든 분류에서 필수이며 유니코드 코드포인트로 제한한다`() {
        // given
        for (code in CancellationReason.entries) {
            // when & then
            assertThrows<BusinessException> { WorkCancellation(1, 1, 1, code, " \n", WorkStatus.ARRIVED, now) }
            assertThat(WorkCancellation(1, 1, 1, code, "😀".repeat(1000), WorkStatus.ARRIVED, now).reasonDetail).isNotBlank()
            assertThrows<BusinessException> { WorkCancellation(1, 1, 1, code, "😀".repeat(1001), WorkStatus.ARRIVED, now) }
        }
    }

    @Test
    fun `보고서 사진 수와 중복을 검증하고 수정 후에도 원본과 완료 시각을 보존한다`() {
        // given
        val original = ReportContent("수리", "누수", 1000)
        assertThrows<BusinessException> { WorkReport(1, 1, original, listOf(1, 2), listOf(3, 4, 5), now) }
        assertThrows<BusinessException> { WorkReport(1, 1, original, listOf(1, 2, 3), listOf(3, 4, 5), now) }
        val report = WorkReport(1, 1, original, listOf(1, 2, 3), listOf(4, 5, 6), now)
        val work = inProgress()
        work.complete(4, now)
        // when
        report.revise(1, ReportContent("수리 보완", "확정 진단", 2000))
        // then
        assertThat(report.original).isEqualTo(original)
        assertThat(report.submittedAt).isEqualTo(now)
        assertThat(work.completedAt).isEqualTo(now)
        assertThrows<BusinessException> { work.cancel(5, now.plusSeconds(1)) }
    }

    private fun inProgress(): WorkOrder = WorkOrder(1, 1, 1, 1, null, now).apply {
        transition(1, WorkStatus.EN_ROUTE, null, null, now)
        transition(2, WorkStatus.ARRIVED, null, null, now)
        transition(3, WorkStatus.IN_PROGRESS, null, null, now)
    }
}
