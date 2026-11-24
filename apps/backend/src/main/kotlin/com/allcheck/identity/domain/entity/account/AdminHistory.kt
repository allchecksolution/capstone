package com.allcheck.identity.domain.entity.account

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "identity_admin_history")
class AdminHistory(
    @Column(name = "actor_account_id") val actorAccountId: Long?,
    @Column(name = "target_account_id", nullable = false) val targetAccountId: Long,
    @Column(nullable = false, length = 40) val action: String,
    @Column(name = "occurred_at", nullable = false) val occurredAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "history_id")
    var id: Long? = null
        protected set
}
