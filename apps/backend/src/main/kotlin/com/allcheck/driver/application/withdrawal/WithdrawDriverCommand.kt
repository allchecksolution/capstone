package com.allcheck.driver.application.withdrawal

data class WithdrawDriverCommand(
    val expectedVersion: Long,
    val confirmed: Boolean,
)
