package com.allcheck.driver.application.notification

data class DriverPushData(val eventId: String, val eventType: String, val driverId: String, val revision: Long)
