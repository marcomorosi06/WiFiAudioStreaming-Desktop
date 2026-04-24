import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.ByteArrayOutputStream

plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
}

group = "com.wifiaudiostreaming"
version = "0.4.0-beta"

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

// ─── Rilevamento piattaforma ──────────────────────────────────────────────────
val osName = System.getProperty("os.name").lowercase()
val osArch = System.getProperty("os.arch").lowercase()

val isWindows = osName.contains("win")
val isMac     = osName.contains("mac")
val isLinux   = !isWindows && !isMac

val nativeOsDir = when {
    isWindows -> "windows"
    isMac     -> "macos"
    else      -> "linux"
}
val nativeArchDir = when {
    osArch.contains("aarch64") || osArch.contains("arm64") -> "arm64"
    else -> "x86_64"
}

// ─── Ricerca di cmake.exe su Windows ─────────────────────────────────────────
// Gradle eredita un PATH ristretto che spesso non include CMake anche quando
// è installato tramite Visual Studio, Scoop, o Chocolatey.
// Questa funzione cerca cmake nei percorsi noti e restituisce il path assoluto.
fun findCmakeExecutable(): String {
    if (!isWindows) return "cmake"

    // 1. Già nel PATH del processo corrente?
    try {
        val check = ProcessBuilder("cmake", "--version")
            .redirectErrorStream(true).start()
        if (check.waitFor() == 0) return "cmake"
    } catch (_: Exception) {}

    // 2. Percorsi standard di installazione su Windows
    val candidates = mutableListOf<String>()

    // Visual Studio 2022 / 2019 (tutte le edizioni: Community, Professional, Enterprise)
    val programFiles = listOf(
        System.getenv("ProgramFiles") ?: "C:\\Program Files",
        System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"
    )
    for (pf in programFiles) {
        for (vsYear in listOf("2022", "2019", "2017")) {
            for (edition in listOf("Community", "Professional", "Enterprise", "BuildTools")) {
                candidates += "$pf\\Microsoft Visual Studio\\$vsYear\\$edition\\Common7\\IDE\\CommonExtensions\\Microsoft\\CMake\\CMake\\bin\\cmake.exe"
            }
        }
    }

    // CLion bundled cmake
    val localAppData = System.getenv("LOCALAPPDATA") ?: ""
    if (localAppData.isNotEmpty()) {
        candidates += "$localAppData\\Programs\\CLion\\bin\\cmake\\win\\x64\\bin\\cmake.exe"
        candidates += "$localAppData\\Programs\\CLion\\bin\\cmake\\win\\bin\\cmake.exe"
    }

    // CMake standalone installer
    candidates += "C:\\Program Files\\CMake\\bin\\cmake.exe"
    candidates += "C:\\Program Files (x86)\\CMake\\bin\\cmake.exe"

    // Scoop
    val userProfile = System.getenv("USERPROFILE") ?: ""
    if (userProfile.isNotEmpty()) {
        candidates += "$userProfile\\scoop\\apps\\cmake\\current\\bin\\cmake.exe"
        candidates += "$userProfile\\scoop\\shims\\cmake.exe"
    }

    // Chocolatey
    candidates += "C:\\ProgramData\\chocolatey\\bin\\cmake.exe"
    candidates += "C:\\tools\\cmake\\bin\\cmake.exe"

    // winget default
    candidates += "C:\\Program Files\\CMake\\bin\\cmake.exe"

    for (path in candidates) {
        if (file(path).exists()) {
            println("[NativeBuild] CMake trovato: $path")
            return path
        }
    }

    throw GradleException(
        "cmake.exe non trovato sul sistema.\n" +
        "Installalo con uno di questi metodi:\n" +
        "  • winget install Kitware.CMake\n" +
        "  • scoop install cmake\n" +
        "  • choco install cmake\n" +
        "  • Visual Studio Installer → Singoli componenti → 'Strumenti CMake C++ per Windows'\n" +
        "Poi riavvia Android Studio / IntelliJ IDEA per aggiornare il PATH."
    )
}

fun findWindowsGenerator(cmakePath: String): List<String> {
    return listOf(
        "-G", "Visual Studio 17 2022",
        "-A", "x64"
    )
}

fun programFilesCandidatesForNinja(): List<String> {
    val pf = System.getenv("ProgramFiles") ?: "C:\\Program Files"
    return listOf("2022", "2019", "2017").flatMap { year ->
        listOf("Community", "Professional", "Enterprise", "BuildTools").map { ed ->
            "$pf\\Microsoft Visual Studio\\$year\\$ed\\Common7\\IDE\\CommonExtensions\\Microsoft\\CMake\\Ninja\\ninja.exe"
        }
    }
}

// ─── Directory per la libreria nativa compilata ───────────────────────────────
val nativeSrcDir    = file("src/main/native")
val nativeBuildDir  = file("${layout.buildDirectory.get()}/native-build")
val nativeOutputDir = file("${layout.buildDirectory.get()}/native-build/output")
val nativeResDir    = file("src/main/resources/native/$nativeOsDir/$nativeArchDir")

val compileNative by tasks.registering(Exec::class) {
    description = "Configura il progetto CMake per audio_engine"
    group       = "build"
    onlyIf { !isLinux }

    doFirst {
        nativeBuildDir.mkdirs()
        nativeResDir.mkdirs()
    }

    val javaHome = System.getProperty("java.home")?.replace("\\", "/")
        ?: throw GradleException("java.home non trovato. Assicurati di usare JDK 17.")

    val cmakePath = if (isLinux) "cmake" else findCmakeExecutable()

    workingDir = nativeBuildDir

    val cmakeArgs = mutableListOf(
        cmakePath,
        nativeSrcDir.absolutePath,
        "-DJAVA_HOME=$javaHome",
        "-DCMAKE_BUILD_TYPE=Release"
    )

    if (isWindows) {
        cmakeArgs += findWindowsGenerator(cmakePath)
    } else {
        cmakeArgs += listOf("-G", "Unix Makefiles")
    }

    commandLine(cmakeArgs)
}

val buildNative by tasks.registering(Exec::class) {
    description = "Compila audio_engine tramite cmake --build"
    group       = "build"
    onlyIf { !isLinux }
    dependsOn(compileNative)

    val cmakePath = if (isLinux) "cmake" else findCmakeExecutable()
    workingDir = nativeBuildDir
    commandLine(cmakePath, "--build", ".", "--config", "Release", "--parallel")
}

val copyNativeLib by tasks.registering(Copy::class) {
    description = "Copia la libreria nativa in src/main/resources/native/"
    group       = "build"
    onlyIf { !isLinux }
    dependsOn(buildNative)

    from(nativeOutputDir) {
        include("**/*.so", "**/*.dll", "**/*.dylib")
    }
    into(nativeResDir)

    eachFile {
        path = name
    }
    includeEmptyDirs = false
}

if (!isLinux) {
    tasks.named("compileKotlin") {
        dependsOn(copyNativeLib)
    }

    tasks.named<ProcessResources>("processResources") {
        dependsOn(copyNativeLib)
    }
}

// ─── Dipendenze ───────────────────────────────────────────────────────────────
dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    val ktorVersion = "3.0.3"
    implementation("io.ktor:ktor-network:$ktorVersion")
    implementation("io.ktor:ktor-network-tls:$ktorVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    val bcVersion = "1.78.1"
    implementation("org.bouncycastle:bcprov-jdk18on:$bcVersion")
    implementation("org.bouncycastle:bctls-jdk18on:$bcVersion")
    implementation("org.bouncycastle:bcpkix-jdk18on:$bcVersion")

    // JavaCV rimane per gli encoder AAC e Opus (HTTP server) —
    // NON viene più usato per la cattura audio (sostituita da AudioEngine JNI).
    val javacvVersion = "1.5.10"
    val targetPlatform = when {
        isWindows -> if (osArch.contains("64")) "windows-x86_64" else "windows-x86"
        isMac     -> if (osArch.contains("aarch64") || osArch.contains("arm64")) "macosx-arm64" else "macosx-x86_64"
        else      -> "linux-x86_64"
    }
    implementation("org.bytedeco:javacv:$javacvVersion")
    implementation("org.bytedeco:ffmpeg:$javacvVersion:$targetPlatform")
    implementation("org.bytedeco:javacpp:$javacvVersion:$targetPlatform")
    implementation("com.dorkbox:SystemTray:4.4")
    implementation("org.slf4j:slf4j-nop:2.0.9")
}

// ─── Packaging Compose Desktop ────────────────────────────────────────────────
compose.desktop {
    application {
        mainClass = "MainKt"

        jvmArgs += listOf(
            "-Djava.net.preferIPv4Stack=true",
            "-XX:UseAVX=2"
        )

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Rpm)

            packageName = "WiFi Audio Streaming"
            packageVersion = "4.0.0"

            modules(
                "java.desktop",
                "java.instrument",
                "java.scripting",
                "java.naming",
                "java.sql",
                "java.xml",
                "jdk.unsupported",
                "java.net.http"
            )

            buildTypes.release.proguard {
                isEnabled.set(false)
                configurationFiles.from(project.file("proguard-rules.pro"))
            }

            windows {
                iconFile.set(project.file("src/main/resources/app_icon.ico"))
                shortcut = true
                menu = true
            }

            macOS {
                iconFile.set(project.file("src/main/resources/app_icon.icns"))
                bundleID = "com.wifiaudiostreaming"
            }

            linux {
                iconFile.set(project.file("src/main/resources/app_icon.png"))
                packageName = "wifi-audio-streaming"
                appCategory = "AudioVideo"
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}
