package com.allcheck.driver.domain.vo.registration

import com.allcheck.driver.domain.vo.registration.WorkType
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Embeddable
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.JoinColumn
import jakarta.persistence.OrderColumn
import jakarta.persistence.UniqueConstraint

@Embeddable
class DriverProfile(
    @Column(name = "worker_name", nullable = false, length = 100) val workerName: String,
    @Column(nullable = false, length = 20) val phone: String,
    @Column(nullable = false, length = 254) val email: String,
    @Column(nullable = false, length = 200) val residence: String,
    @Column(name = "vehicle_number", nullable = false, length = 20) val vehicleNumber: String,
    @Column(name = "bank_name", nullable = false, length = 50) val bankName: String,
    @Column(name = "account_number", nullable = false, length = 30) val accountNumber: String,
    @Column(name = "holder_name", nullable = false, length = 50) val holderName: String,
    @Column(name = "tax_invoice_email", nullable = false, length = 254) val taxInvoiceEmail: String,
    @ElementCollection
    @CollectionTable(name = "driver_profile_specialties", joinColumns = [JoinColumn(name = "driver_id")], uniqueConstraints = [UniqueConstraint(columnNames = ["driver_id", "specialty"])])
    @OrderColumn(name = "position")
    @Enumerated(EnumType.STRING)
    @Column(name = "specialty", nullable = false, length = 20)
    val specialties: List<WorkType>,
    @ElementCollection
    @CollectionTable(name = "driver_profile_regions", joinColumns = [JoinColumn(name = "driver_id")], uniqueConstraints = [UniqueConstraint(columnNames = ["driver_id", "region"])])
    @OrderColumn(name = "position")
    @Column(name = "region", nullable = false, length = 100)
    val activityRegions: List<String>,
    @ElementCollection
    @CollectionTable(name = "driver_profile_equipment", joinColumns = [JoinColumn(name = "driver_id")], uniqueConstraints = [UniqueConstraint(columnNames = ["driver_id", "equipment"])])
    @OrderColumn(name = "position")
    @Column(name = "equipment", nullable = false, length = 100)
    val equipment: List<String>,
) {
    init {
        require(accountNumber.length in 6..30 && accountNumber.matches(Regex("[0-9]+(?:-[0-9]+)*"))) {
            "Settlement account number must contain numeric groups, not a mask."
        }
    }
}
