package com.allcheck.identity

import com.allcheck.identity.application.AdminAccountProvisioningService
import com.allcheck.identity.infrastructure.provisioning.AdminProvisioningConfig
import com.allcheck.identity.presentation.cli.AdminProvisioningCli
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder

/** 애플리케이션 진입점에서 호출하는 운영자 전용 명령. HTTP로 노출하지 않는다. */
object AdminProvisioningCommand {
    fun run(args: Array<String>): Int = AdminProvisioningCli(provision = { loginId, password, role ->
        SpringApplicationBuilder(AdminProvisioningConfig::class.java)
            .web(WebApplicationType.NONE)
            .profiles("admin-provisioning")
            .run(
                "--spring.main.banner-mode=off",
                "--spring.main.log-startup-info=false",
                "--spring.jpa.show-sql=false",
                "--logging.level.root=OFF",
                "--logging.level.org.hibernate.orm.jdbc.bind=OFF",
            ).use { context ->
                context.getBean(AdminAccountProvisioningService::class.java).provision(loginId, password, role)
            }
    }).run(args)
}
