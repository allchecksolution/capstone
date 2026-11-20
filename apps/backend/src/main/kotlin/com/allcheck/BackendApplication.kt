package com.allcheck

import com.allcheck.identity.AdminProvisioningCommand
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import kotlin.system.exitProcess

@SpringBootApplication
class BackendApplication

fun main(args: Array<String>) {
    if (args.firstOrNull() == "provision-admin") {
        exitProcess(AdminProvisioningCommand.run(args.drop(1).toTypedArray()))
    }
    runApplication<BackendApplication>(*args)
}
