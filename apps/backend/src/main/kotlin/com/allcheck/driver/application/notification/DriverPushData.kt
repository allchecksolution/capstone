package com.allcheck.driver.application.notification

data class DriverPushData(val eventId: Long, val eventType: String, val driverId: Long, val revision: Long)
