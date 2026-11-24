package com.allcheck.dashboard.presentation.dto.response

data class DashboardPage(val items: List<DashboardRequestItem>, val page: Int, val size: Int, val hasNext: Boolean)
