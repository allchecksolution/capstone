package com.allcheck.operations.dispatch.infrastructure.repository

import com.allcheck.operations.dispatch.domain.entity.DispatchOffer
import com.allcheck.operations.dispatch.domain.entity.DispatchRound
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface DispatchRoundRepository : JpaRepository<DispatchRound, Long> {
    fun findAllByRequestIdOrderByRoundId(requestId: Long): List<DispatchRound>

    @Query(value = "select distinct r.request_id from dispatch_rounds r join dispatch_offers o on o.round_id=r.round_id where r.status='OFFERING' and o.status='PENDING' and o.expires_at<=:now order by r.request_id limit :limit", nativeQuery = true)
    fun expiringRequests(now: Instant, limit: Int): List<Long>
}
interface DispatchOfferRepository : JpaRepository<DispatchOffer, Long> {
    fun findAllByRoundIdOrderByOfferId(roundId: Long): List<DispatchOffer>
    fun findAllByDriverIdOrderByOfferIdDesc(driverId: Long, pageable: Pageable): List<DispatchOffer>
}
