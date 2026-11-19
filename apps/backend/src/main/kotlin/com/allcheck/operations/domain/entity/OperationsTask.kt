package com.allcheck.operations.domain.entity

import com.allcheck.operations.ActorRole
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class TaskKind { SEARCH, EXPAND_OFFER, PUSH_OFFER, EXPAND_ADMIN, PUSH_ADMIN }
enum class TaskState { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED }

@Entity
@Table(name = "operations_tasks")
class OperationsTask(
    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 30, updatable = false) val kind: TaskKind,
    @field:Column(nullable = false, updatable = false) val requestId: Long,
    @field:Column(nullable = false, updatable = false) val subjectId: Long,
    @field:Column(nullable = false, length = 400, unique = true, updatable = false) val logicalKey: String,
    @field:Column(nullable = false, updatable = false) val createdAt: Instant,
    @field:Enumerated(EnumType.STRING)
    @field:Column(length = 10, updatable = false) val actorRole: ActorRole? = null,
    @field:Column(updatable = false) val actorId: Long? = null,
    @field:Column(length = 200, updatable = false) val installationId: String? = null,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(nullable = false, updatable = false)
    var taskId: Long? = null
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 30)
    var state: TaskState = TaskState.PENDING
        protected set

    @field:Column(nullable = false)
    var attempts: Int = 0
        protected set

    @field:Column(nullable = false)
    var dueAt: Instant = createdAt
        protected set

    @field:Column(length = 36)
    var claimToken: String? = null
        protected set

    @field:Column
    var leaseUntil: Instant? = null
        protected set

    @field:Version
    @field:Column(nullable = false)
    var persistenceVersion: Long = 0
        protected set

    fun claim(now: Instant, lease: Duration): String {
        check(state == TaskState.PENDING && dueAt <= now)
        val token = UUID.randomUUID().toString()
        state = TaskState.RUNNING
        claimToken = token
        leaseUntil = now.plus(lease)
        attempts++
        return token
    }
    fun held(token: String, now: Instant) = state == TaskState.RUNNING && token == claimToken && now < checkNotNull(leaseUntil)
    fun finish(success: Boolean) {
        state = if (success) TaskState.SUCCEEDED else TaskState.FAILED
        clearClaim()
    }
    fun cancel() {
        state = TaskState.CANCELLED
        clearClaim()
    }
    fun retry(due: Instant) {
        state = TaskState.PENDING
        dueAt = due
        clearClaim()
    }
    fun terminal() = state in setOf(TaskState.SUCCEEDED, TaskState.FAILED, TaskState.CANCELLED)
    private fun clearClaim() {
        claimToken = null
        leaseUntil = null
    }
}
