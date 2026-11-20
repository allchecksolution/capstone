package com.allcheck.driver.application.registration

import com.allcheck.driver.domain.vo.registration.WorkType

data class DriverProfileInput(
    val workerName: String,
    val phone: String,
    val email: String,
    val residence: String,
    val vehicleNumber: String,
    val settlementAccount: SettlementAccountInput,
    val taxInvoiceEmail: String,
    val specialties: List<WorkType>,
    val activityRegions: List<String>,
    val equipment: List<String>,
)
