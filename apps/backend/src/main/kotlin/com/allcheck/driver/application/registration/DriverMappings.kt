package com.allcheck.driver.application.registration

import com.allcheck.driver.domain.entity.registration.Driver
import com.allcheck.driver.domain.entity.registration.DriverBusiness
import com.allcheck.driver.domain.entity.registration.DriverProfile
import com.allcheck.driver.domain.vo.registration.BusinessIdentity
import com.allcheck.driver.domain.vo.registration.VerifiedBusinessIdentity
import com.allcheck.driver.presentation.dto.response.registration.DriverRegistrationResult

internal fun BusinessInfoInput.identity(): BusinessIdentity = BusinessIdentity(businessRegistrationNumber, openedOn, representativeName)

internal fun DriverProfileInput.entity(): DriverProfile = DriverProfile(
    workerName, phone, email, residence, vehicleNumber,
    settlementAccount.bankName, settlementAccount.accountNumber, settlementAccount.holderName,
    taxInvoiceEmail, specialties.toList(), activityRegions.toList(), equipment.toList(),
)

internal fun BusinessInfoInput.entity(verified: VerifiedBusinessIdentity): DriverBusiness {
    require(verified.identity.registrationNumber == businessRegistrationNumber && verified.identity.openedOn == openedOn && verified.identity.representativeName == representativeName) {
        "Business verification must match the submitted identity."
    }
    return DriverBusiness(
        businessName, businessRegistrationNumber, openedOn, businessType, businessItem, representativeName,
        companyPhone, companyAddress, verified.operatingStatus, verified.verifiedAt,
    )
}

internal fun Driver.result(): DriverRegistrationResult = DriverRegistrationResult(id, accountStatus, submittedAt, version)
