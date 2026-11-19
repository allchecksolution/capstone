package com.allcheck.operations.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Immutable
@Table(name = "operations_receipts")
class OperationsReceipt(
    @field:Column(nullable = false, length = 10, updatable = false) val actorRole: String,
    @field:Column(nullable = false, updatable = false) val actorId: Long,
    @field:Column(nullable = false, length = 40, updatable = false) val operation: String,
    @field:Column(nullable = false, updatable = false) val scopeId: Long,
    @field:Column(nullable = false, length = 200, updatable = false) val idempotencyKey: String,
    @field:Column(nullable = false, columnDefinition = "bytea", updatable = false) val fingerprint: ByteArray,
    @field:JdbcTypeCode(SqlTypes.JSON)
    @field:Column(nullable = false, columnDefinition = "jsonb", updatable = false) val result: Map<String, Any?>,
    @field:Column(nullable = false, updatable = false) val createdAt: Instant,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(nullable = false, updatable = false)
    var receiptId: Long? = null
        protected set
}
