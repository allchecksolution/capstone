package com.allcheck.identity.infrastructure.provisioning

import com.allcheck.identity.application.AdminAccountProvisioningService
import com.allcheck.identity.application.AdminAccountProvisioningTransactions
import com.allcheck.identity.domain.entity.Account
import com.allcheck.identity.infrastructure.password.AdminPasswordHasher
import com.allcheck.identity.infrastructure.repository.AccountRepository
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@Configuration(proxyBeanMethods = false)
@Profile("admin-provisioning")
@EnableAutoConfiguration
@EntityScan(basePackageClasses = [Account::class])
@EnableJpaRepositories(basePackageClasses = [AccountRepository::class])
@Import(AdminAccountProvisioningService::class, AdminAccountProvisioningTransactions::class, AdminPasswordHasher::class)
class AdminProvisioningConfig
