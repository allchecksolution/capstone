package com.allcheck.global.config

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Loads operator-owned local configuration, without printing credentials or executing shell syntax. */
class DotenvEnvironmentPostProcessor :
    EnvironmentPostProcessor,
    Ordered {
    override fun getOrder(): Int = ConfigDataEnvironmentPostProcessor.ORDER - 1

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val path = Path.of(environment.getProperty("allcheck.env-file", ".env"))
        if (Files.notExists(path)) return
        val values = linkedMapOf<String, Any>()
        Files.readAllLines(path, StandardCharsets.UTF_8).forEachIndexed { index, line ->
            val input = line.removePrefix("\uFEFF").trim()
            if (input.isNotEmpty() && !input.startsWith('#')) {
                val assignment = ASSIGNMENT.matchEntire(input) ?: invalidLine(index)
                val name = assignment.groupValues[1]
                if (values.containsKey(name)) invalidLine(index)
                values[name] = value(assignment.groupValues[2], index)
            }
        }
        val source = SystemEnvironmentPropertySource("allcheckDotenv", values)
        val sources = environment.propertySources
        // Keep command-line arguments, JVM properties, and actual OS environment variables authoritative.
        if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, source)
        } else {
            sources.addLast(source)
        }
    }

    private fun value(input: String, line: Int): String {
        if (input.isEmpty()) return ""
        val quote = input.first()
        if (quote != '\'' && quote != '"') return input.substringBefore(" #").substringBefore("\t#").trimEnd().takeUnless { it.startsWith('#') } ?: ""
        val result = StringBuilder()
        var index = 1
        while (index < input.length) {
            val character = input[index++]
            if (character == quote) {
                val remainder = input.substring(index).trimStart()
                if (remainder.isNotEmpty() && !remainder.startsWith('#')) invalidLine(line)
                return result.toString()
            }
            if (quote == '"' && character == '\\' && index < input.length) {
                when (val escaped = input[index++]) {
                    'n' -> result.append('\n')
                    'r' -> result.append('\r')
                    't' -> result.append('\t')
                    '"', '\\' -> result.append(escaped)
                    else -> result.append('\\').append(escaped)
                }
            } else {
                result.append(character)
            }
        }
        invalidLine(line)
    }

    private fun invalidLine(index: Int): Nothing = throw IllegalStateException("Invalid .env format at line ${index + 1}. Use one KEY=VALUE assignment per line; duplicate keys are not supported.")

    companion object {
        private val ASSIGNMENT = Regex("(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.*)")
    }
}
