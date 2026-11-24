package com.allcheck.driver.domain.entity.registration

import com.allcheck.driver.domain.vo.registration.BusinessOperatingStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

@Entity
@Table(name = "driver_businesses")
class DriverBusiness(
    @Column(name = "business_name", nullable = false, length = 100) val businessName: String,
    @Column(name = "registration_number", nullable = false, length = 10) val registrationNumber: String,
    @Column(name = "opened_on", nullable = false) val openedOn: LocalDate,
    @Column(name = "business_type", nullable = false, length = 100) val businessType: String,
    @Column(name = "business_item", nullable = false, length = 100) val businessItem: String,
    @Column(name = "representative_name", nullable = false, length = 100) val representativeName: String,
    @Column(name = "company_phone", nullable = false, length = 20) val companyPhone: String,
    @Column(name = "company_address", nullable = false, length = 200) val companyAddress: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "operating_status", nullable = false, length = 16) val operatingStatus: BusinessOperatingStatus,
    @Column(name = "verified_at", nullable = false) val verifiedAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "business_id", nullable = false, updatable = false)
    var id: Long? = null
        protected set
}
