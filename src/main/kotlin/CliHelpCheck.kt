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

fun main(args: Array<String>) {
    val source = File(args.getOrNull(0) ?: "src/main/kotlin/CliArgs.kt")
    if (!source.isFile) {
        System.err.println("CliArgs.kt not found at ${source.path}")
        kotlin.system.exitProcess(1)
    }

    val parser = CliHelpCoverage.parserTokens(source)
    val documented = CliHelpModel.allTokens()
    val missing = (parser - documented).sorted()
    val stale = documented.filter { it.startsWith("--") && it !in parser }.sorted()

    val duplicates = CliHelpModel.topics.flatMap { t ->
        t.blocks.flatMap { b -> b.entries.map { it.syntax } }
            .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            .map { t.key + ": " + it }
    }.sorted()

    println("help coverage: ${parser.size} long options in the parser, ${documented.size} tokens documented")

    missing.forEach { println("  FAIL accepted by the parser, absent from the help: $it") }
    stale.forEach { println("  FAIL documented but not accepted by the parser: $it") }
    duplicates.forEach { println("  FAIL the same syntax appears in two help entries: $it") }

    val failures = missing.size + stale.size + duplicates.size
    if (failures == 0) println("  ok   help and parser agree")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
