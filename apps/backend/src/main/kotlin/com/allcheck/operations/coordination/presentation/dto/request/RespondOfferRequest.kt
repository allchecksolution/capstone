package com.allcheck.operations.coordination.presentation.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Pattern

data class RespondOfferRequest(@field:Schema(description = "ACCEPT 또는 REJECT") @field:Pattern(regexp = "ACCEPT|REJECT") val response: String) : StrictOperationsRequest()
