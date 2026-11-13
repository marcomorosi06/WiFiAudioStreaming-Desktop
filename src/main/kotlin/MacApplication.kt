/*
 * Copyright (c) 2026 Marco Morosi and contributors
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

/** macOS UI integration, sharing the application's existing JNI library. */
object MacApplication {
    private val isMac = System.getProperty("os.name").lowercase().contains("mac")
    private external fun nativeSetWindowVisible(visible: Boolean)

    fun setWindowVisible(visible: Boolean) {
        if (!isMac) return
        if (!AudioEngine.loadLibrary()) {
            System.err.println("[MacApplication] Cannot update Dock presence: native library unavailable")
            return
        }
        runCatching { nativeSetWindowVisible(visible) }
            .onFailure { System.err.println("[MacApplication] Cannot update Dock presence: ${it.message}") }
    }
}
