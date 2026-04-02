import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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

// Helper per determinare la piattaforma senza importare Loader direttamente
val osName = System.getProperty("os.name").lowercase()
val osArch = System.getProperty("os.arch").lowercase()
val targetPlatform = when {
    osName.contains("win") -> if (osArch.contains("64")) "windows-x86_64" else "windows-x86"
    osName.contains("mac") -> if (osArch.contains("aarch64") || osArch.contains("arm64")) "macosx-arm64" else "macosx-x86_64"
    else -> "linux-x86_64"
}

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

    val javacvVersion = "1.5.10"
    implementation("org.bytedeco:javacv:$javacvVersion")
    implementation("org.bytedeco:ffmpeg:$javacvVersion:$targetPlatform")
    implementation("org.bytedeco:javacpp:$javacvVersion:$targetPlatform")
}

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