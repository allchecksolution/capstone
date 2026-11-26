package com.allcheck.identity.presentation.provisioning

import com.allcheck.global.exception.BusinessException
import com.allcheck.identity.AdminRole
import com.allcheck.identity.application.provisioning.ProvisionedAdministrator
import org.springframework.beans.BeansException
import org.springframework.dao.DataAccessException
import java.io.IOException
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class AdminProvisioningCli(
    private val provision: (String, String, AdminRole) -> ProvisionedAdministrator,
    private val readConsolePassword: () -> CharArray? = { System.console()?.readPassword("Administrator password: ") },
    private val output: PrintStream = System.out,
    private val error: PrintStream = System.err,
    private val command: String = "provision-admin",
) {
    fun run(args: Array<String>): Int {
        if (args.contentEquals(arrayOf("--help"))) {
            output.println(USAGE)
            return 0
        }
        val options = parse(args) ?: return usageError()
        val password = try {
            readPassword(options.passwordFile)
        } catch (ex: IOException) {
            error.println("ADMIN_PASSWORD_FILE_UNREADABLE")
            return 2
        } ?: return usageError()
        try {
            if (password.isEmpty() || password.size > 200 || password.all { it.isWhitespace() }) return usageError()
            val created = provision(options.loginId, String(password), options.role)
            val action = when (command) {
                "recover-bootstrap-admin" -> "ADMIN_RECOVERED"
                "adopt-bootstrap-admin" -> "ADMIN_ADOPTED"
                else -> "ADMIN_CREATED"
            }
            output.println("$action accountId=${created.accountId} role=${created.role}")
            return 0
        } catch (ex: BusinessException) {
            error.println(ex.errorCode.name)
            return 1
        } catch (ex: DataAccessException) {
            error.println("ADMIN_PROVISIONING_DATABASE_UNAVAILABLE")
            return 1
        } catch (ex: BeansException) {
            error.println("ADMIN_PROVISIONING_CONFIGURATION_FAILED")
            return 1
        } finally {
            password.fill('\u0000')
        }
    }

    private fun parse(args: Array<String>): Options? {
        val values = mutableMapOf<String, String>()
        for (argument in args) {
            val parts = argument.split('=', limit = 2)
            if (parts.size != 2 || parts[0] !in setOf("--login-id", "--role", "--password-file") || parts[1].isEmpty()) return null
            if (values.putIfAbsent(parts[0], parts[1]) != null) return null
        }
        val loginId = values["--login-id"] ?: return null
        if (loginId.isBlank() || loginId.length > 100 || '\u0000' in loginId) return null
        val role = if (command == "provision-admin") AdminRole.entries.firstOrNull { it.name == values["--role"] } ?: return null else AdminRole.MASTER
        if (command != "provision-admin" && values.containsKey("--role")) return null
        return Options(loginId, role, values["--password-file"])
    }

    private fun readPassword(passwordFile: String?): CharArray? {
        if (passwordFile == null) return readConsolePassword()
        val path = try {
            Path.of(passwordFile)
        } catch (ex: java.nio.file.InvalidPathException) {
            return null
        }
        val bytes = Files.newInputStream(path).use { it.readNBytes(801) }
        try {
            if (bytes.size > 800) return null
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().toCharArray()
        } finally {
            bytes.fill(0)
        }
    }

    private fun usageError(): Int {
        error.println("ADMIN_PROVISIONING_INPUT_INVALID")
        error.println(USAGE)
        return 2
    }

    private data class Options(val loginId: String, val role: AdminRole, val passwordFile: String?)

    companion object {
        private const val USAGE = "Usage: java -jar app.jar provision-admin --login-id=<id> --role=<MASTER|SUB> [--password-file=<UTF-8 file>]\nRecovery: recover-bootstrap-admin --login-id=<id> [--password-file=<file>]\nMigration: adopt-bootstrap-admin --login-id=<existing-id> [--password-file=<current-password-file>]\nWithout --password-file, enter the password in an interactive console. Passwords are never accepted as command-line arguments."
    }
}
