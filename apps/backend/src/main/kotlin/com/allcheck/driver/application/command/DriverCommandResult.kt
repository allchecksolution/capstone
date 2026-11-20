package com.allcheck.driver.application.command

data class DriverCommandResult<T>(val body: T, val replayed: Boolean)
