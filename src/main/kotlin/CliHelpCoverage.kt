/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/software/page/eupl
 */

import java.io.File

object CliHelpCoverage {

    val INTERNAL: Set<String> = setOf("--", "--cli-no-args", "--fred")

    private val OPTION = Regex("\"(--[a-z0-9][a-z0-9-]*)\"")

    fun parserTokens(source: File): Set<String> {
        if (!source.isFile) return emptySet()
        val body = source.readText()
        val start = body.indexOf("fun parse(")
        val end = body.indexOf("fun printHelp(")
        val scope = if (start in 0 until end) body.substring(start, end) else body
        return OPTION.findAll(scope).map { it.groupValues[1] }.toSet() - INTERNAL
    }

    fun missingTokens(source: File = File("src/main/kotlin/CliArgs.kt")): Set<String> =
        parserTokens(source) - CliHelpModel.allTokens()

    fun staleTokens(source: File = File("src/main/kotlin/CliArgs.kt")): Set<String> {
        val known = parserTokens(source)
        if (known.isEmpty()) return emptySet()
        return CliHelpModel.allTokens().filter { it.startsWith("--") && it !in known }.toSet()
    }
}
