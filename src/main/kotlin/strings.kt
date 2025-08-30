import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.util.Locale
import java.util.ResourceBundle

/**
 * Gestore per le stringhe internazionalizzate.
 * Carica il file .properties corretto (strings_it.properties o strings.properties)
 * in base alla lingua del sistema.
 */
object Strings {
    private val bundle: ResourceBundle

    init {
        // Determina la lingua da usare. Se è 'it', usa l'italiano, altrimenti l'inglese di default.
        val locale = if (Locale.getDefault().language == "it") {
            Locale("it", "IT")
        } else {
            Locale.ENGLISH
        }
        // Carica il resource bundle. Il nome base "strings" corrisponde ai file
        // strings.properties e strings_it.properties.
        bundle = ResourceBundle.getBundle("strings", locale)
    }

    /**
     * Recupera una stringa dalla sua chiave.
     * @param key La chiave della stringa nel file .properties.
     * @return La stringa tradotta o la chiave stessa se non trovata.
     */
    fun get(key: String): String {
        return try {
            bundle.getString(key)
        } catch (e: Exception) {
            key // Restituisce la chiave se la traduzione non è trovata (utile per il debug)
        }
    }

    /**
     * Recupera una stringa formattata con argomenti.
     * @param key La chiave della stringa nel file .properties (es. "user_welcome=Welcome, %s").
     * @param args Gli argomenti da inserire nella stringa.
     * @return La stringa formattata e tradotta.
     */
    fun get(key: String, vararg args: Any): String {
        return try {
            String.format(get(key), *args)
        } catch (e: Exception) {
            key
        }
    }
}

/**
 * Funzione Composable di utilità per accedere facilmente alle stringhe dalla UI.
 */
@Composable
fun stringResource(key: String): String {
    return remember(key) { Strings.get(key) }
}

/**
 * Funzione Composable di utilità per accedere a stringhe formattate.
 */
@Composable
fun stringResource(key: String, vararg args: Any): String {
    return remember(key, args) { Strings.get(key, *args) }
}