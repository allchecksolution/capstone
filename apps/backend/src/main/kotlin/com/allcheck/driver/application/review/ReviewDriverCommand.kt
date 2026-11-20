package com.allcheck.driver.application.review

import com.allcheck.driver.domain.vo.review.ApprovalDecision

data class ReviewDriverCommand(
    val expectedVersion: Long,
    val decision: ApprovalDecision,
    val rejectionReason: String? = null,
)
