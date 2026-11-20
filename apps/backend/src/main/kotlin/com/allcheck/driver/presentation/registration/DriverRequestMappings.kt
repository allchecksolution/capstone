package com.allcheck.driver.presentation.registration

import com.allcheck.driver.application.registration.BusinessInfoInput
import com.allcheck.driver.application.registration.DriverProfileInput
import com.allcheck.driver.application.registration.RegisterDriverCommand
import com.allcheck.driver.application.registration.ResubmitDriverCommand
import com.allcheck.driver.application.registration.SettlementAccountInput
import com.allcheck.driver.application.review.ReviewDriverCommand
import com.allcheck.driver.application.withdrawal.WithdrawDriverCommand
import com.allcheck.driver.presentation.dto.request.registration.BusinessInfoRequest
import com.allcheck.driver.presentation.dto.request.registration.DriverProfileRequest
import com.allcheck.driver.presentation.dto.request.registration.DriverRegistrationRequest
import com.allcheck.driver.presentation.dto.request.registration.DriverResubmissionRequest
import com.allcheck.driver.presentation.dto.request.registration.SettlementAccountRequest
import com.allcheck.driver.presentation.dto.request.review.DriverApprovalRequest
import com.allcheck.driver.presentation.dto.request.withdrawal.DriverWithdrawalRequest

internal fun DriverRegistrationRequest.toCommand(): RegisterDriverCommand = RegisterDriverCommand(profile.toInput(), business.toInput())
internal fun DriverResubmissionRequest.toCommand(): ResubmitDriverCommand = ResubmitDriverCommand(expectedVersion, registration.toCommand())
internal fun DriverApprovalRequest.toCommand(): ReviewDriverCommand = ReviewDriverCommand(expectedVersion, decision, rejectionReason)
internal fun DriverWithdrawalRequest.toCommand(): WithdrawDriverCommand = WithdrawDriverCommand(expectedVersion, confirmed)

private fun SettlementAccountRequest.toInput(): SettlementAccountInput = SettlementAccountInput(bankName, accountNumber, holderName)
private fun DriverProfileRequest.toInput(): DriverProfileInput = DriverProfileInput(workerName, phone, email, residence, vehicleNumber, settlementAccount.toInput(), taxInvoiceEmail, specialties.toList(), activityRegions.toList(), equipment.toList())
private fun BusinessInfoRequest.toInput(): BusinessInfoInput = BusinessInfoInput(businessName, businessRegistrationNumber, openedOn, businessType, businessItem, representativeName, companyPhone, companyAddress)
