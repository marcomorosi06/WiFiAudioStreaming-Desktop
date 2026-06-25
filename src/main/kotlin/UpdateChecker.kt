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

import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    private const val REPO = "marcomorosi06/WiFiAudioStreaming-Desktop"
    const val RELEASES_URL = "https://github.com/marcomorosi06/WiFiAudioStreaming-Desktop/releases/latest"

    val currentVersion: String get() = Strings.appVersion

    sealed class Result {
        data class UpToDate(val current: String) : Result()
        data class Available(val current: String, val latest: String, val url: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    private val TAG_RX = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"")

    fun check(timeoutMs: Int = 5000): Result {
        return try {
            val conn = (URL("https://api.github.com/repos/$REPO/releases/latest")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "WFAS-UpdateChecker")
            }
            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                return Result.Failed("HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val tag = TAG_RX.find(body)?.groupValues?.get(1)
                ?: return Result.Failed("no release tag found")
            val latest = normalize(tag)
            val current = normalize(currentVersion)
            if (compareVersions(latest, current) > 0)
                Result.Available(current, latest, RELEASES_URL)
            else
                Result.UpToDate(current)
        } catch (e: Exception) {
            Result.Failed(e.message ?: "network error")
        }
    }

    fun normalize(tag: String): String =
        tag.trim().removePrefix("v").removePrefix("V").trim()

    fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.', '-', '+').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val pb = b.split('.', '-', '+').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}
