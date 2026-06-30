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

import java.io.File

enum class HostOs { WINDOWS, MACOS, LINUX, OTHER }

object ConfigPaths {

    const val APP_DIR_NAME = "wfas"
    const val CONFIG_FILE_NAME = "config.json"

    val os: HostOs by lazy {
        val n = System.getProperty("os.name", "").lowercase()
        when {
            n.contains("win")                        -> HostOs.WINDOWS
            n.contains("mac") || n.contains("darwin") -> HostOs.MACOS
            n.contains("nux") || n.contains("nix") || n.contains("aix") -> HostOs.LINUX
            else                                     -> HostOs.OTHER
        }
    }

    val osLabel: String
        get() = when (os) {
            HostOs.WINDOWS -> "Windows"
            HostOs.MACOS   -> "macOS"
            HostOs.LINUX   -> "Linux"
            HostOs.OTHER   -> System.getProperty("os.name", "unknown")
        }

    private fun homeDir(): File = File(System.getProperty("user.home", "."))

    private fun envDir(name: String): File? =
        System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }?.let { File(it) }

    fun defaultConfigDir(): File = when (os) {
        HostOs.WINDOWS -> (envDir("APPDATA") ?: File(homeDir(), "AppData/Roaming")).let { File(it, APP_DIR_NAME) }
        HostOs.MACOS   -> File(homeDir(), "Library/Application Support/$APP_DIR_NAME")
        else           -> (envDir("XDG_CONFIG_HOME") ?: File(homeDir(), ".config")).let { File(it, APP_DIR_NAME) }
    }

    @Volatile
    var overrideConfigFile: File? = null

    fun configFile(): File = overrideConfigFile ?: File(defaultConfigDir(), CONFIG_FILE_NAME)

    fun configDir(): File = configFile().parentFile ?: defaultConfigDir()

    fun tmpDir(): File = File(System.getProperty("java.io.tmpdir", "."))

    fun portFile(pid: Long): File = File(tmpDir(), "wfas-$pid.port")

    fun portFileGlob(): String = File(tmpDir(), "wfas-<pid>.port").path

    fun sdpFile(): File = File(tmpDir(), "stream.sdp")

    fun describeForHelp(): List<Pair<String, String>> = listOf(
        "settings file"  to configFile().path,
        "IPC port file"  to portFileGlob(),
        "RTP descriptor" to sdpFile().path
    )
}
