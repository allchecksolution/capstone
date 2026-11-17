package com.allcheck.driver.application

import com.allcheck.driver.domain.entity.Driver
import com.allcheck.driver.domain.entity.DriverBusiness
import com.allcheck.driver.domain.entity.DriverProfile
import com.allcheck.driver.domain.vo.BusinessIdentity
import com.allcheck.driver.domain.vo.VerifiedBusinessIdentity
import com.allcheck.driver.presentation.dto.request.BusinessInfoRequest
import com.allcheck.driver.presentation.dto.request.DriverProfileRequest
import com.allcheck.driver.presentation.dto.request.SettlementAccountRequest
import com.allcheck.driver.presentation.dto.response.BusinessVerificationResponse
import com.allcheck.driver.presentation.dto.response.DriverRegistrationResult
import com.allcheck.driver.presentation.dto.response.DriverRegistrationView
import com.allcheck.driver.presentation.dto.response.RegistrationRejection

internal fun BusinessInfoRequest.identity(): BusinessIdentity = BusinessIdentity(businessRegistrationNumber, openedOn, representativeName)

internal fun DriverProfileRequest.entity(): DriverProfile = DriverProfile(
    workerName, phone, email, residence, vehicleNumber,
    settlementAccount.bankName, settlementAccount.accountNumber, settlementAccount.holderName,
    taxInvoiceEmail, specialties.toList(), activityRegions.toList(), equipment.toList(),
)

internal fun BusinessInfoRequest.entity(verified: VerifiedBusinessIdentity): DriverBusiness {
    require(verified.identity.registrationNumber == businessRegistrationNumber && verified.identity.openedOn == openedOn && verified.identity.representativeName == representativeName) {
        "Business verification must match the submitted identity."
    }
    return DriverBusiness(
        businessName, businessRegistrationNumber, openedOn, businessType, businessItem, representativeName,
        companyPhone, companyAddress, verified.operatingStatus, verified.verifiedAt,
    )
}

internal fun DriverProfile.response(maskAccount: Boolean = false): DriverProfileRequest = DriverProfileRequest(
    workerName, phone, email, residence, vehicleNumber,
    SettlementAccountRequest(bankName, if (maskAccount) "*".repeat((accountNumber.length - 4).coerceAtLeast(0)) + accountNumber.takeLast(4) else accountNumber, holderName),
    taxInvoiceEmail, specialties.toList(), activityRegions.toList(), equipment.toList(),
)

internal fun DriverBusiness.response(): BusinessInfoRequest = BusinessInfoRequest(
    businessName,
    registrationNumber,
    openedOn,
    businessType,
    businessItem,
    representativeName,
    companyPhone,
    companyAddress,
)

internal fun Driver.result(): DriverRegistrationResult = DriverRegistrationResult(id, accountStatus, submittedAt, version)

internal fun Driver.view(): DriverRegistrationView = DriverRegistrationView(
    id,
    accountStatus,
    version,
    submittedAt,
    rejectionReason?.let { RegistrationRejection(it, checkNotNull(rejectedAt)) },
    profile.response(),
    business.response(),
    BusinessVerificationResponse(verifiedAt = business.verifiedAt),
)
