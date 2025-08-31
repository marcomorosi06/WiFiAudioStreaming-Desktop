import org.jetbrains.compose.desktop.application.dsl.TargetFormat


plugins {
    kotlin("jvm") version "1.9.23"
    id("org.jetbrains.compose") version "1.7.0"
}

group = "com.wifiaudiostreaming"
version = "0.1-beta"

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    // Dipendenza per la UI di Compose Multiplatform
    implementation(compose.desktop.currentOs)
    // Aggiungiamo esplicitamente la dipendenza per Material 3
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Ktor per il networking (TCP/UDP)
    val ktorVersion = "2.3.11"
    implementation("io.ktor:ktor-network:$ktorVersion")
    implementation("io.ktor:ktor-network-tls:$ktorVersion")

    // Per la gestione delle coroutine (necessario per Ktor e operazioni asincrone)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")
    implementation("org.bouncycastle:bctls-jdk15on:1.70")
    implementation("org.bouncycastle:bcpkix-jdk15on:1.70")
}

compose.desktop {
    application {
        mainClass = "MainKt"

        nativeDistributions {
            // === PASSO 1: AGGIUNGI I NUOVI FORMATI ===
            // Aggiungiamo .dmg per macOS e .deb/.rpm per Linux
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Rpm)

            packageName = "WiFi Audio Streaming"
            packageVersion = "1.0.0"

            buildTypes.release.proguard {
                isEnabled.set(true)
                configurationFiles.from(project.file("proguard-rules.pro"))
            }

            // Configurazione specifica per Windows
            windows {
                iconFile.set(project.file("src/main/resources/app_icon.ico"))
                shortcut = true
                menu = true
            }

            // === PASSO 2: DECOMMENTA E ABILITA LE SEZIONI PER macOS e LINUX ===
            macOS {
                // IMPORTANTE: Devi creare e inserire un'icona .icns in questo percorso
                iconFile.set(project.file("src/main/resources/app_icon.icns"))
                // Puoi personalizzare altre opzioni qui, come il bundle ID
                bundleID = "com.wifiaudiostreaming"
            }

            linux {
                // IMPORTANTE: Devi creare e inserire un'icona .png in questo percorso
                iconFile.set(project.file("src/main/resources/app_icon.png"))
                // Nome del pacchetto per i gestori di pacchetti Linux
                packageName = "wifi-audio-streaming"
                // Aggiungiamo una categoria per il menu delle applicazioni (es. "AudioVideo", "Network")
                appCategory = "AudioVideo"
            }
        }
    }
}

// Torniamo a Java 17, che è stabile e compatibile
kotlin {
    jvmToolchain(17)
}


