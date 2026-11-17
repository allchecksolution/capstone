package com.allcheck.driver.domain.vo

import java.time.LocalDate

/** 국세청 진위확인에 사용하는 세 가지 필수 정보. */
class BusinessIdentity(
    val registrationNumber: String,
    val openedOn: LocalDate,
    val representativeName: String,
) {
    init {
        require(registrationNumber.matches(Regex("[0-9]{10}"))) { "Business registration number must contain 10 digits." }
        require(openedOn.year in 1..9999) { "Business opening date must have a four-digit year." }
        require(representativeName.isNotBlank() && representativeName.none { it.isISOControl() }) { "Invalid business representative name." }
    }
}
