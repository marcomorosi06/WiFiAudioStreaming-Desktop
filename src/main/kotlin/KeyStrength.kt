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

import kotlin.math.ln
import kotlin.math.min

/**
 * A rough strength estimate for a manually typed pre-shared key, shown live under
 * the key field as a red → amber → green meter.
 */
enum class KeyStrengthLevel { EMPTY, WEAK, FAIR, STRONG }

object KeyStrength {

    private const val WEAK_BITS = 45.0
    private const val STRONG_BITS = 75.0
    private const val FULL_BAR_BITS = 100.0

    fun bits(key: String): Double {
        if (key.isEmpty()) return 0.0
        var pool = 0
        if (key.any { it in 'a'..'z' }) pool += 26
        if (key.any { it in 'A'..'Z' }) pool += 26
        if (key.any { it in '0'..'9' }) pool += 10
        if (key.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' }) pool += 33
        if (pool == 0) pool = 1
        val distinctRatio = key.toSet().size.toDouble() / key.length
        val factor = distinctRatio.coerceAtLeast(0.35)
        return key.length * (ln(pool.toDouble()) / ln(2.0)) * factor
    }

    fun level(key: String): KeyStrengthLevel = when {
        key.isEmpty()          -> KeyStrengthLevel.EMPTY
        bits(key) < WEAK_BITS  -> KeyStrengthLevel.WEAK
        bits(key) < STRONG_BITS -> KeyStrengthLevel.FAIR
        else                   -> KeyStrengthLevel.STRONG
    }

    /** 0f..1f fill for the meter bar. */
    fun fraction(key: String): Float =
        min(1.0, bits(key) / FULL_BAR_BITS).toFloat()
}
