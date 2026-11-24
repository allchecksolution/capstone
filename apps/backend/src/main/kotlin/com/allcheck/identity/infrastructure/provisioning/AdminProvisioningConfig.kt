package com.allcheck.identity.infrastructure.provisioning

import com.allcheck.identity.application.account.AdminAccountTransactions
import com.allcheck.identity.application.provisioning.AdminAccountProvisioningService
import com.allcheck.identity.application.provisioning.AdminAccountProvisioningTransactions
import com.allcheck.identity.infrastructure.login.password.AdminPasswordHasher
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@Configuration(proxyBeanMethods = false)
@Profile("admin-provisioning")
@EnableAutoConfiguration
@EntityScan("com.allcheck.identity.domain.entity")
@EnableJpaRepositories("com.allcheck.identity.infrastructure.repository")
@Import(AdminAccountProvisioningService::class, AdminAccountProvisioningTransactions::class, AdminPasswordHasher::class, AdminAccountTransactions::class)
class AdminProvisioningConfig
