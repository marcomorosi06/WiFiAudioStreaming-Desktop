/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by
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

/**
 * Switches off the dorkbox update ping.
 *
 * dorkbox SystemTray (the Linux tray icon) and the dorkbox libraries it brings
 * register themselves with com.dorkbox:Updates. Five minutes after start, and
 * then every four hours, that library sends a plain-HTTP request to
 * updates.dorkbox.com with a random instance id and the library versions. The
 * app makes no other connection the user did not ask for, so this one is off.
 *
 * The library reads the system property once, when its object is first
 * loaded, and checks the flag again before every request, so both are set:
 * the property before any dorkbox class is touched, and the flag by
 * reflection, which works whatever the load order turned out to be. Removing
 * the module instead is not an option: SystemTray calls it from its static
 * initialiser and would fail to load without it.
 */
object DorkboxUpdates {

    private const val CLASS_NAME = "dorkbox.updates.Updates"
    private const val PROPERTY = "$CLASS_NAME.ENABLE"

    fun disable() {
        runCatching { System.setProperty(PROPERTY, "false") }
        runCatching {
            val type = Class.forName(CLASS_NAME)
            val instance = type.getField("INSTANCE").get(null)
            type.getMethod("setENABLE", Boolean::class.javaPrimitiveType).invoke(instance, false)
        }.onFailure {
            // Absent on platforms without the tray dependency, or renamed in a
            // future release: the property set above still applies then.
            AppDebug.log("[TRAY] dorkbox Updates flag not set by reflection: ${it.javaClass.simpleName} ${it.message}")
        }
    }
}
