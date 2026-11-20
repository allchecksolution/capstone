package com.allcheck.driver.application.registration

import java.time.LocalDate

data class BusinessInfoInput(
    val businessName: String,
    val businessRegistrationNumber: String,
    val openedOn: LocalDate,
    val businessType: String,
    val businessItem: String,
    val representativeName: String,
    val companyPhone: String,
    val companyAddress: String,
)
