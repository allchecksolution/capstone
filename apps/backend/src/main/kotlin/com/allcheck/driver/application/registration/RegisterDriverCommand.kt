package com.allcheck.driver.application.registration

data class RegisterDriverCommand(
    val profile: DriverProfileInput,
    val business: BusinessInfoInput,
)
