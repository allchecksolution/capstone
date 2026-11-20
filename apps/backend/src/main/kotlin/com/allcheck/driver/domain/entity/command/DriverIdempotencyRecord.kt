package com.allcheck.driver.domain.entity.command

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(name = "driver_idempotency_records", uniqueConstraints = [UniqueConstraint(name = "uq_driver_idempotency_scope", columnNames = ["actor_id", "operation_scope", "key_hash"])])
class DriverIdempotencyRecord(
    @Column(name = "actor_id", nullable = false, updatable = false) val actorId: Long,
    @Column(name = "operation_scope", nullable = false, length = 200, updatable = false) val operationScope: String,
    @Column(name = "key_hash", nullable = false, length = 64, updatable = false) val keyHash: String,
    @Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false) val requestFingerprint: String,
    @Column(name = "response_json", nullable = false, columnDefinition = "text", updatable = false) val responseJson: String,
    @Column(name = "created_at", nullable = false, updatable = false) val createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "record_id", nullable = false, updatable = false)
    var id: Long? = null
        protected set
}
