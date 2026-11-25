package com.allcheck.identity.application

import com.allcheck.identity.AdminRole
import com.allcheck.identity.application.provisioning.AdminAccountProvisioningService
import com.allcheck.identity.infrastructure.login.password.AdminPasswordHasher
import com.allcheck.identity.infrastructure.provisioning.AdminProvisioningConfig
import com.allcheck.identity.infrastructure.repository.account.AccountRepository
import com.allcheck.support.PostgresTestSchema
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import javax.sql.DataSource

@SpringBootTest(classes = [AdminProvisioningConfig::class], webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("admin-provisioning")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminProvisioningContextTest @Autowired constructor(
    private val service: AdminAccountProvisioningService,
    private val accounts: AccountRepository,
    private val passwords: AdminPasswordHasher,
) {
    @Test
    fun `웹 서버나 JWT 설정 없이 CLI 전용 컨텍스트에서 최초 발급과 복구가 가능하다`() {
        val created = service.provision("operator-created", "initial-test-password", AdminRole.MASTER)
        service.recover("operator-created", "recovered-test-password")
        val account = accounts.findById(created.accountId).orElseThrow()
        assertThat(account.isBootstrapAdmin).isTrue()
        assertThat(passwords.matches("recovered-test-password", account.adminCredential?.passwordHash)).isTrue()
    }

    companion object {
        private val database = PostgresTestSchema("admin_cli")

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = database.register(registry)

        @JvmStatic @AfterAll
        fun removeTestSchema(@Autowired dataSource: DataSource) = database.remove(dataSource)
    }
}
