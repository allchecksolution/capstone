package com.allcheck.reception.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.Immutable
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Immutable
@Table(name = "reception_command_receipts", uniqueConstraints = [UniqueConstraint(name = "uq_reception_command_receipts_scope", columnNames = ["actor_admin_id", "operation", "scope_reception_id", "idempotency_key"])])
class ReceptionCommandReceipt(
    @field:Column(name = "actor_admin_id", nullable = false, updatable = false) val actorAdminId: Long,
    @field:Column(name = "operation", nullable = false, length = 30, updatable = false) val operation: String,
    @field:Column(name = "scope_reception_id", nullable = false, updatable = false) val scopeReceptionId: Long,
    @field:Column(name = "idempotency_key", nullable = false, length = 200, updatable = false) val idempotencyKey: String,
    @field:Column(name = "request_fingerprint", nullable = false, columnDefinition = "bytea", updatable = false) private val fingerprint: ByteArray,
    @field:Column(name = "result_reception_id", nullable = false, updatable = false) val resultReceptionId: Long,
    @field:JdbcTypeCode(SqlTypes.JSON)
    @field:Column(name = "response_body", nullable = false, columnDefinition = "jsonb", updatable = false) val responseBody: Map<String, Any?>,
    @field:Column(name = "created_at", nullable = false, updatable = false) val createdAt: Instant,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "receipt_id", nullable = false, updatable = false)
    var receiptId: Long? = null
        protected set

    fun matches(other: ByteArray): Boolean = fingerprint.contentEquals(other)
}
