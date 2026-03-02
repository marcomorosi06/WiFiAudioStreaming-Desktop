import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    // Aggiornato a Kotlin 2.x
    kotlin("jvm") version "2.1.0"
    // Plugin ufficiale di Compose Multiplatform
    id("org.jetbrains.compose") version "1.7.3"
    // NOVITÀ KOTLIN 2: Il compilatore Compose è ora un plugin di Kotlin
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
}

group = "com.wifiaudiostreaming"
version = "0.2-beta"

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    // Dipendenze UI Compose
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Aggiornato a Ktor 3.x
    val ktorVersion = "3.0.3"
    implementation("io.ktor:ktor-network:$ktorVersion")
    implementation("io.ktor:ktor-network-tls:$ktorVersion")

    // Aggiornato Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Aggiornato BouncyCastle (jdk15on è obsoleto, si usa jdk18on per Java 17+)
    val bcVersion = "1.78.1"
    implementation("org.bouncycastle:bcprov-jdk18on:$bcVersion")
    implementation("org.bouncycastle:bctls-jdk18on:$bcVersion")
    implementation("org.bouncycastle:bcpkix-jdk18on:$bcVersion")
    val javacvVersion = "1.5.10"
    implementation("org.bytedeco:javacv-platform:$javacvVersion")
}

compose.desktop {
    application {
        mainClass = "MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Rpm)

            packageName = "WiFi Audio Streaming"
            packageVersion = "2.0.0"

            buildTypes.release.proguard {
                isEnabled.set(true)
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