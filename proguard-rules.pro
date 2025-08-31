# =================================================================
# ==                  CONFIGURAZIONE FONDAMENTALE                ==
# =================================================================
# RISOLUTIVO: Dice a ProGuard dove trovare le librerie del JDK 17.
# Questo risolve i "Warning: unresolved references" in modo definitivo.
-libraryjars <java.home>/lib/jrt-fs.jar

# Ignora i warning rimanenti, per massima sicurezza.
-ignorewarnings

# =================================================================
# ==                  REGOLE GENERALI KOTLIN                     ==
# =================================================================
# Mantiene i metadati di Kotlin, essenziali per la reflection.
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.** { @kotlin.Metadata <methods>; }
-keepclassmembers enum * { *; }
-keepclassmembers class ** implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# =================================================================
# ==                  REGOLE PER LE LIBRERIE                     ==
# =================================================================
# Questa è la sezione più importante per risolvere i "Warning".
# Dice a ProGuard di non bloccarsi se non trova classi referenziate
# da queste librerie.
-dontwarn kotlin.**
-dontwarn kotlinx.**
-dontwarn androidx.compose.**
-dontwarn org.jetbrains.skia.**
-dontwarn org.jetbrains.skiko.**
-dontwarn io.ktor.**
-dontwarn org.bouncycastle.**
-dontwarn java.awt.**
-dontwarn java.beans.**

# =================================================================
# ==              REGOLE DI CONSERVAZIONE (KEEP)                 ==
# =================================================================
# Mantiene intatte le classi necessarie per evitare crash a runtime.

# Coroutines
-keep public class * extends kotlin.coroutines.jvm.internal.BaseContinuationImpl
-keep class kotlinx.coroutines.** { *; }

# Compose
-keepclasseswithmembers public class * { @androidx.compose.runtime.Composable <methods>; }
-keepclassmembers class * { @androidx.compose.runtime.Stable <methods>; @androidx.compose.runtime.Immutable <fields>; }
-keep public class androidx.compose.runtime.** { *; }
-keep public class androidx.compose.ui.node.** { *; }
-keep class androidx.compose.material.** { *; }
-keep class androidx.compose.material3.** { *; }

# Skia/Skiko (per il rendering su desktop)
-keep class org.jetbrains.skia.** { *; }
-keep class org.jetbrains.skiko.** { *; }

# Ktor & Bouncy Castle
-keep class io.ktor.** { *; }
-keep class org.bouncycastle.** { *; }

# Java AWT e Sound
-keep class java.awt.Desktop { *; }
-keep class javax.sound.sampled.** { *; }

# Le tue classi di dati
-keep class com.wifiaudiostreaming.** { *; }

