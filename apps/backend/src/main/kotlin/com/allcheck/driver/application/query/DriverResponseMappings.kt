package com.allcheck.driver.application.query

import com.allcheck.driver.domain.entity.registration.Driver
import com.allcheck.driver.domain.entity.registration.DriverBusiness
import com.allcheck.driver.domain.entity.registration.DriverProfile
import com.allcheck.driver.presentation.dto.response.registration.BusinessInfoResponse
import com.allcheck.driver.presentation.dto.response.registration.BusinessVerificationResponse
import com.allcheck.driver.presentation.dto.response.registration.DriverProfileResponse
import com.allcheck.driver.presentation.dto.response.registration.DriverRegistrationView
import com.allcheck.driver.presentation.dto.response.registration.SettlementAccountResponse
import com.allcheck.driver.presentation.dto.response.review.RegistrationRejection

internal fun DriverProfile.response(maskAccount: Boolean = false): DriverProfileResponse = DriverProfileResponse(
    workerName, phone, email, residence, vehicleNumber,
    SettlementAccountResponse(bankName, if (maskAccount) "*".repeat((accountNumber.length - 4).coerceAtLeast(0)) + accountNumber.takeLast(4) else accountNumber, holderName),
    taxInvoiceEmail, specialties.toList(), activityRegions.toList(), equipment.toList(),
)

internal fun DriverBusiness.response(): BusinessInfoResponse = BusinessInfoResponse(
    businessName,
    registrationNumber,
    openedOn,
    businessType,
    businessItem,
    representativeName,
    companyPhone,
    companyAddress,
)

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
