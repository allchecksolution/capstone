package com.allcheck.driver.application.registration

data class ResubmitDriverCommand(
    val expectedVersion: Long,
    val registration: RegisterDriverCommand,
)
