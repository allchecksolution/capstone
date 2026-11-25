package com.allcheck.operations.dispatch.application

import com.allcheck.global.exception.BusinessException
import com.allcheck.operations.DeliveryStatus
import com.allcheck.operations.OfferStatus
import com.allcheck.operations.RoundStatus
import com.allcheck.operations.dispatch.DispatchOperations
import com.allcheck.operations.dispatch.OfferSnapshot
import com.allcheck.operations.dispatch.RoundSnapshot
import com.allcheck.operations.dispatch.domain.entity.DispatchOffer
import com.allcheck.operations.dispatch.domain.entity.DispatchRound
import com.allcheck.operations.dispatch.infrastructure.repository.DispatchOfferRepository
import com.allcheck.operations.dispatch.infrastructure.repository.DispatchRoundRepository
import com.allcheck.operations.exception.OperationsErrorCode
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

@Service
@Transactional(readOnly = true)
class DispatchService(private val rounds: DispatchRoundRepository, private val offers: DispatchOfferRepository) : DispatchOperations {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun create(requestId: Long, now: Instant) = rounds.saveAndFlush(DispatchRound(requestId, now)).snapshot()
    override fun round(roundId: Long) = roundEntity(roundId).snapshot()
    override fun rounds(requestId: Long) = rounds.findAllByRequestIdOrderByRoundId(requestId).map { it.snapshot() }
    override fun offer(offerId: Long) = offerEntity(offerId).snapshot()
    override fun offers(roundId: Long) = offers.findAllByRoundIdOrderByOfferId(roundId).map { it.snapshot() }
    override fun driverOffers(driverId: Long, page: Int, size: Int) = offers.findAllByDriverIdOrderByOfferIdDesc(driverId, PageRequest.of(page - 1, size)).map { it.snapshot() }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun expand(roundId: Long) = roundEntity(roundId).expand()

    @Transactional(propagation = Propagation.MANDATORY)
    override fun publish(roundId: Long, candidates: Map<Long, Double>, now: Instant, lifetime: Duration): List<OfferSnapshot> {
        require(candidates.isNotEmpty())
        val round = roundEntity(roundId)
        round.offering()
        return offers.saveAllAndFlush(candidates.map { (driverId, distance) -> DispatchOffer(roundId, round.requestId, driverId, distance, now, now.plus(lifetime)) }).map { it.snapshot() }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun close(roundId: Long, outcome: RoundStatus, now: Instant) {
        roundEntity(roundId).close(outcome, now)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun accept(offerId: Long, now: Instant) {
        val offer = offerEntity(offerId)
        offer.respond(true, now)
        roundEntity(offer.roundId).assign(now)
        offers.findAllByRoundIdOrderByOfferId(offer.roundId).filter { it.offerId != offerId }.forEach { it.close() }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun reject(offerId: Long, now: Instant) {
        offerEntity(offerId).respond(false, now)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun reconcile(roundId: Long, now: Instant): RoundStatus? {
        val round = roundEntity(roundId)
        if (round.status != RoundStatus.OFFERING) return null
        val all = offers.findAllByRoundIdOrderByOfferId(roundId)
        all.forEach { it.expire(now) }
        if (all.any { it.status == OfferStatus.PENDING && it.deliveryStatus != DeliveryStatus.FAILED }) return null
        val outcome = when {
            all.any { it.status == OfferStatus.EXPIRED } -> RoundStatus.ROUND_CLOSED
            all.any { it.status == OfferStatus.REJECTED } -> RoundStatus.NO_ACCEPT
            else -> RoundStatus.DELIVERY_FAILED
        }
        round.close(outcome, now)
        return outcome
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun delivery(offerId: Long, status: DeliveryStatus) {
        offerEntity(offerId).delivery(status)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun signal(offerId: Long, viewed: Boolean, now: Instant) {
        offerEntity(offerId).signal(viewed, now)
    }
    override fun expiringRequests(now: Instant, limit: Int) = rounds.expiringRequests(now, limit)
    private fun roundEntity(id: Long) = rounds.findById(id).orElseThrow { BusinessException(OperationsErrorCode.OPERATIONS_NOT_FOUND) }
    private fun offerEntity(id: Long) = offers.findById(id).orElseThrow { BusinessException(OperationsErrorCode.OPERATIONS_NOT_FOUND) }
    private fun DispatchRound.snapshot() = RoundSnapshot(checkNotNull(roundId), requestId, status, radiusKm, createdAt, closedAt)
    private fun DispatchOffer.snapshot() = OfferSnapshot(checkNotNull(offerId), roundId, requestId, driverId, status, deliveryStatus, distanceMeters, createdAt, expiresAt, respondedAt, receivedAt, viewedAt)
}
