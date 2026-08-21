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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import java.io.InputStreamReader
import java.util.Properties

object Strings {
    private val props = Properties()
    private var currentLanguage = "auto"
    val languageVersion = mutableStateOf(0)

    init {
        loadLanguage("auto")
    }

    fun setLanguage(lang: String) {
        currentLanguage = lang
        loadLanguage(lang)
        languageVersion.value++
    }

    fun getLanguage(): String = currentLanguage

    fun loadLanguage(lang: String) {
        props.clear()
        val loader = Strings::class.java.classLoader
        loader.getResourceAsStream("strings.properties")
            ?.use { props.load(InputStreamReader(it, Charsets.UTF_8)) }

        val targetLang = if (lang == "auto" || lang.isBlank()) {
            java.util.Locale.getDefault().language.lowercase()
        } else {
            lang.lowercase()
        }

        val propFile = when (targetLang) {
            "it" -> "strings_it.properties"
            "vi" -> "strings_vi.properties"
            "es" -> "strings_es.properties"
            "fr" -> "strings_fr.properties"
            "de" -> "strings_de.properties"
            "pt" -> "strings_pt.properties"
            "ru" -> "strings_ru.properties"
            "ja" -> "strings_ja.properties"
            "ko" -> "strings_ko.properties"
            "zh", "zh-cn", "zh_cn" -> "strings_zh.properties"
            "zh-tw", "zh_tw", "zh-hk", "zh_hk" -> "strings_zh_TW.properties"
            "ar" -> "strings_ar.properties"
            "hi" -> "strings_hi.properties"
            "id", "in" -> "strings_id.properties"
            "tr" -> "strings_tr.properties"
            "pl" -> "strings_pl.properties"
            "nl" -> "strings_nl.properties"
            "th" -> "strings_th.properties"
            "uk" -> "strings_uk.properties"
            else -> null
        }

        if (propFile != null) {
            loader.getResourceAsStream(propFile)
                ?.use { props.load(InputStreamReader(it, Charsets.UTF_8)) }
        }
    }

    fun get(key: String): String = props.getProperty(key, key)

    fun get(key: String, vararg args: Any): String {
        return try {
            String.format(get(key), *args)
        } catch (e: Exception) {
            key
        }
    }

    val appVersion: String = "1.2-rebuild"
}

fun displayVersion(raw: String): String {
    return "1.2-rebuild"
}

@Composable
fun stringResource(key: String): String {
    val ver = Strings.languageVersion.value
    return remember(key, ver) { Strings.get(key) }
}

@Composable
fun stringResource(key: String, vararg args: Any): String {
    val ver = Strings.languageVersion.value
    return remember(key, ver, *args) { Strings.get(key, *args) }
}
