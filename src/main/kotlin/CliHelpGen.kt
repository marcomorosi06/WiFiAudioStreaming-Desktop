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
import java.time.LocalDate

fun main(args: Array<String>) {
    val target = File(args.getOrNull(0) ?: "src/main/resources/man/wfas.1")
    val version = args.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "unknown"
    val date = args.getOrNull(2)?.takeIf { it.isNotBlank() } ?: LocalDate.now().toString()

    val source = File(args.getOrNull(3) ?: "src/main/kotlin/CliArgs.kt")
    val missing = CliHelpCoverage.missingTokens(source)
    val stale = CliHelpCoverage.staleTokens(source)
    if (missing.isNotEmpty() || stale.isNotEmpty()) {
        missing.sorted().forEach { System.err.println("  accepted by the parser, absent from the help: $it") }
        stale.sorted().forEach { System.err.println("  documented but not accepted by the parser: $it") }
        System.err.println("Refusing to generate the man page while help and parser disagree.")
        kotlin.system.exitProcess(1)
    }

    val rendered = CliHelpMan.render(version, date)
    if (target.isFile && target.readText() == rendered) {
        println("[man] ${target.path} already up to date")
        return
    }
    target.parentFile?.mkdirs()
    target.writeText(rendered)
    println("[man] wrote ${target.path} (${target.length()} bytes)")
}
