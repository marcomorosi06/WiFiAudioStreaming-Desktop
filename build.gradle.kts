import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "1.9.23"
    id("org.jetbrains.compose") version "1.7.0"
}

group = "com.wifiaudiostreaming"
version = "1.0.0"

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    // Compose Multiplatform per l'interfaccia utente
    implementation(compose.desktop.currentOs)
    // FIX: Aggiungiamo esplicitamente la dipendenza per Material 3
    // per risolvere i problemi di "Unresolved reference".
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
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "WiFiAudioStreaming"
            packageVersion = "1.0.0"

            // Specifica le icone per ogni sistema operativo
            windows {
                iconFile.set(project.file("src/main/resources/app_icon.ico")) // Windows preferisce file .ico
            }
            macOS {
                iconFile.set(project.file("src/main/resources/app_icon.icns")) // macOS usa file .icns
            }
            linux {
                iconFile.set(project.file("src/main/resources/app_icon.png")) // Linux va bene con .png
            }
        }
    }
}

// Aggiungiamo questo blocco per allineare la versione della JVM
// usata da Kotlin e Java, risolvendo l'errore di compatibilità.
kotlin {
    jvmToolchain(21)
}
