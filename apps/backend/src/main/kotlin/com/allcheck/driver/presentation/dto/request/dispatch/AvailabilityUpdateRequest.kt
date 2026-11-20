package com.allcheck.driver.presentation.dto.request.dispatch

import com.allcheck.driver.domain.vo.dispatch.DriverAvailability
import com.allcheck.driver.presentation.dto.request.StrictDriverRequest

data class AvailabilityUpdateRequest(val availability: DriverAvailability) : StrictDriverRequest()
