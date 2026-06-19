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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.InputStreamReader
import java.util.Properties

object Strings {
    private val props = Properties()

    init {
        val loader = Strings::class.java.classLoader
        loader.getResourceAsStream("strings.properties")
            ?.use { props.load(InputStreamReader(it, Charsets.UTF_8)) }
    }

    fun get(key: String): String = props.getProperty(key, key)

    fun get(key: String, vararg args: Any): String {
        return try {
            String.format(get(key), *args)
        } catch (e: Exception) {
            key
        }
    }

    val appVersion: String by lazy {
        val loader = Strings::class.java.classLoader
        val stream = loader.getResourceAsStream("version.properties") ?: return@lazy "?"
        val vProps = Properties().apply { load(InputStreamReader(stream, Charsets.UTF_8)) }
        displayVersion(vProps.getProperty("app.version", "?"))
    }
}

/**
 * Transforms the internal build version (e.g. "5.0.0") into the
 * user-facing display version by subtracting 4 from the major component.
 * The build number in build.gradle.kts remains unchanged.
 */
fun displayVersion(raw: String): String {
    val parts = raw.split(".", limit = 3)
    val major = parts.getOrNull(0)?.toIntOrNull() ?: return raw
    val adjusted = (major - 4).coerceAtLeast(0)
    return buildString {
        append(adjusted)
        parts.drop(1).forEach { append('.').append(it) }
    }
}

@Composable
fun stringResource(key: String): String {
    return remember(key) { Strings.get(key) }
}

@Composable
fun stringResource(key: String, vararg args: Any): String {
    return remember(key, args) { Strings.get(key, *args) }
}
