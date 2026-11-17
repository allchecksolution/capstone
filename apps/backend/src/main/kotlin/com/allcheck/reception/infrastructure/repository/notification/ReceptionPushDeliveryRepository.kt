package com.allcheck.reception.infrastructure.repository.notification

import com.allcheck.reception.domain.entity.notification.ReceptionPushDelivery
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface ReceptionPushDeliveryRepository : JpaRepository<ReceptionPushDelivery, Long> {
    fun findAllByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscDeliveryIdAsc(status: String, now: Instant, page: Pageable): List<ReceptionPushDelivery>
    fun findAllByStatusAndLeaseExpiresAtLessThanEqualOrderByLeaseExpiresAtAscDeliveryIdAsc(status: String, now: Instant, page: Pageable): List<ReceptionPushDelivery>
    fun findAllByEventId(eventId: Long): List<ReceptionPushDelivery>
}
