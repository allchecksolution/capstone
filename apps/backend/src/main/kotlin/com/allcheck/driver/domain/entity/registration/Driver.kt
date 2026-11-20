package com.allcheck.driver.domain.entity.registration

import com.allcheck.driver.domain.vo.registration.BusinessOperatingStatus
import com.allcheck.driver.domain.vo.registration.DriverAccountStatus
import com.allcheck.driver.exception.DriverErrorCode
import com.allcheck.global.exception.BusinessException
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "drivers")
class Driver(
    @Column(name = "account_id", nullable = false, length = 64, unique = true, updatable = false) val accountId: String,
    profile: DriverProfile,
    business: DriverBusiness,
    submittedAt: Instant,
) {
    @Id
    @Column(name = "driver_id", nullable = false, length = 64, updatable = false)
    val id: String = UUID.randomUUID().toString()

    @OneToOne(cascade = [CascadeType.ALL], fetch = FetchType.LAZY, orphanRemoval = true)
    @JoinColumn(name = "profile_id", nullable = false, unique = true)
    var profile: DriverProfile = profile
        protected set

    @OneToOne(cascade = [CascadeType.ALL], fetch = FetchType.LAZY, orphanRemoval = true)
    @JoinColumn(name = "business_id", nullable = false, unique = true)
    var business: DriverBusiness = business
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false, length = 20)
    var accountStatus: DriverAccountStatus = DriverAccountStatus.PENDING_APPROVAL
        protected set

    @Column(name = "submitted_at", nullable = false)
    var submittedAt: Instant = submittedAt
        protected set

    @Column(name = "revision", nullable = false)
    var revision: Long = 1
        protected set

    @Column(name = "rejection_reason", length = 1000)
    var rejectionReason: String? = null
        protected set

    @Column(name = "rejected_at")
    var rejectedAt: Instant? = null
        protected set

    @Column(name = "withdrawn_at")
    var withdrawnAt: Instant? = null
        protected set

    @Version
    @Column(name = "version", nullable = false)
    var persistedVersion: Long? = null
        protected set

    val version: Long get() = (persistedVersion ?: 0) + 1

    init {
        require(business.operatingStatus == BusinessOperatingStatus.CONTINUING) { "Registration requires a verified continuing business." }
    }

    fun checkVersion(expectedVersion: Long) {
        if (version != expectedVersion) throw BusinessException(DriverErrorCode.VERSION_CONFLICT)
    }

    fun resubmit(profile: DriverProfile, business: DriverBusiness, now: Instant) {
        requireRejected()
        require(business.operatingStatus == BusinessOperatingStatus.CONTINUING)
        this.profile = profile
        this.business = business
        submittedAt = now
        revision++
        rejectionReason = null
        rejectedAt = null
        accountStatus = DriverAccountStatus.PENDING_APPROVAL
    }

    fun approve() {
        requirePending()
        if (business.operatingStatus != BusinessOperatingStatus.CONTINUING) throw BusinessException(DriverErrorCode.APPROVAL_PRECONDITION_FAILED)
        accountStatus = DriverAccountStatus.ACTIVATED
    }

    fun reject(reason: String, now: Instant) {
        requirePending()
        require(reason.isNotBlank() && reason.length <= 1000)
        accountStatus = DriverAccountStatus.REJECTED
        rejectionReason = reason
        rejectedAt = now
    }

    fun withdraw(now: Instant) {
        requireActive()
        accountStatus = DriverAccountStatus.WITHDRAWN
        withdrawnAt = now
    }

    fun requireActive() {
        if (accountStatus != DriverAccountStatus.ACTIVATED) throw BusinessException(DriverErrorCode.DRIVER_NOT_ACTIVE)
    }

    fun requireRejected() {
        if (accountStatus != DriverAccountStatus.REJECTED) throw BusinessException(DriverErrorCode.NOT_REJECTED)
    }

    private fun requirePending() {
        if (accountStatus != DriverAccountStatus.PENDING_APPROVAL) throw BusinessException(DriverErrorCode.DRIVER_ALREADY_DECIDED)
    }
}
