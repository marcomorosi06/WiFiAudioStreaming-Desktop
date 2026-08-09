/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 */

import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Line
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

object DevicesCli {

    private fun dim(t: String)    = Ansi.dim(t)
    private fun bold(t: String)   = Ansi.bold(t)
    private fun cyan(t: String)   = Ansi.cyan(t)
    private fun green(t: String)  = Ansi.green(t)
    private fun yellow(t: String) = Ansi.yellow(t)

    private fun jsonEscape(s: String) =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private class Probe(
        val info: Mixer.Info,
        val port: Boolean,
        val outputProbed: Boolean,
        val inputProbed: Boolean,
        val outputFormats: Int,
        val inputFormats: Int,
        val error: String?
    ) {
        val role: String
            get() = when {
                port -> "port"
                outputProbed && inputProbed -> "output+input"
                outputProbed -> "output"
                inputProbed -> "input"
                else -> "unprobed"
            }

        val note: String
            get() = when {
                error != null -> "mixer could not be opened: $error"
                port -> "control port, not a playback or capture endpoint"
                outputProbed || inputProbed -> ""
                outputFormats == 0 && inputFormats == 0 ->
                    "no formats reported — the device is most likely held by another " +
                        "audio server (PulseAudio, PipeWire, JACK) or already in use"
                else -> "reports formats but no usable line"
            }
    }

    private fun probe(info: Mixer.Info): Probe {
        val port = NetworkHandler_v1.isPortMixer(info)
        val mixer = runCatching { AudioSystem.getMixer(info) }
        val err = mixer.exceptionOrNull()?.message
        val m = mixer.getOrNull()
        val outSupported = m != null && runCatching {
            m.isLineSupported(Line.Info(SourceDataLine::class.java))
        }.getOrDefault(false)
        val inSupported = m != null && runCatching {
            m.isLineSupported(Line.Info(TargetDataLine::class.java))
        }.getOrDefault(false)
        val outCount = runCatching { m?.sourceLineInfo?.size ?: 0 }.getOrDefault(0)
        val inCount = runCatching { m?.targetLineInfo?.size ?: 0 }.getOrDefault(0)
        return Probe(info, port, outSupported, inSupported, outCount, inCount, err)
    }

    fun run(json: Boolean): Int {
        val all = NetworkHandler_v1.allMixers()
        val probes = all.map { probe(it) }
        val outputs = NetworkHandler_v1.findAvailableOutputMixers()
        val inputs = NetworkHandler_v1.findAvailableInputMixers()
        val degraded = NetworkHandler_v1.probeIsBlind()

        return if (json) printJson(probes, outputs, inputs, degraded)
        else printHuman(probes, outputs, inputs, degraded)
    }

    private fun printJson(
        probes: List<Probe>,
        outputs: List<Mixer.Info>,
        inputs: List<Mixer.Info>,
        degraded: Boolean
    ): Int {
        val outNames = outputs.map { it.name }.toSet()
        val inNames = inputs.map { it.name }.toSet()

        fun str(value: String): String {
            val escaped = jsonEscape(value)
            return "\"" + escaped + "\""
        }

        fun optStr(value: String?): String = if (value == null) "null" else str(value)

        val items = probes.joinToString(", ") { p ->
            val fields = listOf(
                "\"name\": " + str(p.info.name),
                "\"description\": " + str(p.info.description),
                "\"vendor\": " + str(p.info.vendor),
                "\"version\": " + str(p.info.version),
                "\"role\": " + str(p.role),
                "\"port\": " + p.port,
                "\"output_formats\": " + p.outputFormats,
                "\"input_formats\": " + p.inputFormats,
                "\"selectable_as_output\": " + (p.info.name in outNames),
                "\"selectable_as_input\": " + (p.info.name in inNames),
                "\"note\": " + str(p.note)
            )
            "{" + fields.joinToString(", ") + "}"
        }

        val payload = listOf(
            "\"status\": " + str("ok"),
            "\"degraded\": " + degraded,
            "\"default_output\": " + optStr(outputs.firstOrNull()?.name),
            "\"default_input\": " + optStr(inputs.firstOrNull()?.name),
            "\"devices\": [" + items + "]"
        )
        println("{" + payload.joinToString(", ") + "}")
        return if (probes.isEmpty()) ExitCode.RESOURCE_ERROR else ExitCode.OK
    }

    private fun printHuman(
        probes: List<Probe>,
        outputs: List<Mixer.Info>,
        inputs: List<Mixer.Info>,
        degraded: Boolean
    ): Int {
        println()
        println("  " + bold("Audio devices as seen by wfas"))
        println()

        if (probes.isEmpty()) {
            System.err.println("  " + yellow("Java Sound reports no audio devices at all on this system."))
            System.err.println("  " + dim("On Linux this usually means the runtime was built without ALSA support,"))
            System.err.println("  " + dim("or no sound card is visible to this user. Check 'aplay -l'."))
            println()
            return ExitCode.RESOURCE_ERROR
        }

        val outNames = outputs.map { it.name }.toSet()
        val inNames = inputs.map { it.name }.toSet()
        val defaultOutput = outputs.firstOrNull()?.name
        val defaultInput = inputs.firstOrNull()?.name

        val nameWidth = probes.maxOf { it.info.name.length }.coerceIn(18, 34)

        fun shorten(text: String, max: Int): String =
            if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"

        fun row(marker: String, p: Probe, formats: Int) {
            val name = shorten(p.info.name, nameWidth).padEnd(nameWidth)
            val desc = shorten(p.info.description.removePrefix("Direct Audio Device: "), 44)
            val tail = (if (formats > 0) "$formats fmt" else "no probe").padEnd(9)
            println("  $marker ${cyan(name)}  ${dim(tail)}${dim(desc)}")
        }

        if (outputs.isNotEmpty()) {
            println("  " + bold("OUTPUTS") + dim("  — pass any of these to --output"))
            outputs.forEach { info ->
                val p = probes.firstOrNull { it.info.name == info.name } ?: probe(info)
                row(if (info.name == defaultOutput) green("*") else " ", p, p.outputFormats)
            }
            println()
        }

        if (inputs.isNotEmpty()) {
            println("  " + bold("INPUTS") + dim("  — pass any of these to --mic-input"))
            inputs.forEach { info ->
                val p = probes.firstOrNull { it.info.name == info.name } ?: probe(info)
                row(if (info.name == defaultInput) green("*") else " ", p, p.inputFormats)
            }
            println()
        }

        val skipped = probes.filter { it.info.name !in outNames && it.info.name !in inNames }
        if (skipped.isNotEmpty()) {
            println("  " + bold("SKIPPED"))
            skipped.forEach { p ->
                val name = shorten(p.info.name, nameWidth).padEnd(nameWidth)
                println("  ${dim("-")} ${dim(name)}  ${dim(p.note)}")
            }
            println()
        }

        println("  ${dim("* = used when --output or --mic-input is omitted.")}")
        println("  ${dim("Matching is partial and case-insensitive: a fragment such as 'Loopback' is enough.")}")

        if (degraded) {
            println()
            println("  " + yellow("None of these devices answered the format probe."))
            println("  " + dim("They are listed anyway and can still be opened. On Linux an audio server"))
            println("  " + dim("such as PulseAudio or PipeWire usually holds the ALSA cards, which makes"))
            println("  " + dim("the probe fail even though playback works fine."))
        }
        println()
        return ExitCode.OK
    }
}
