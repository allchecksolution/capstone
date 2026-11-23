package com.allcheck.dashboard.presentation.dto.response

import com.allcheck.operations.WorkType

data class RequestTypeCount(val workType: WorkType, val count: Long, val ratio: Double)
