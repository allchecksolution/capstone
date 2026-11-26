package com.allcheck.dashboard.presentation.dto.response

import java.time.LocalDate

data class WeeklyRequestCount(val from: LocalDate, val to: LocalDate, val count: Long)
