package com.allcheck.operations.request.domain.entity

import com.allcheck.operations.FollowUpChoice
import com.allcheck.operations.FollowUpTarget
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
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
@Table(name = "operations_follow_up_decisions")
class FollowUpDecision(
    @field:Column(nullable = false, updatable = false) val requestId: Long,
    @field:JdbcTypeCode(SqlTypes.JSON)
    @field:Column(nullable = false, columnDefinition = "jsonb", updatable = false) val target: FollowUpTarget,
    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 30, updatable = false) val choice: FollowUpChoice,
    @field:Column(nullable = false, updatable = false) val adminId: Long,
    @field:Column(updatable = false) val newRoundId: Long?,
    @field:Column(nullable = false, updatable = false) val decidedAt: Instant,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(nullable = false, updatable = false)
    var decisionId: Long? = null
        protected set
}
