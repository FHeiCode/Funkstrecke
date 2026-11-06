package de.funkstrecke.util

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Sprache der Oberfläche. [SYSTEM] folgt der Gerätesprache (Deutsch, sonst Englisch). */
enum class AppLanguage {
    SYSTEM,
    GERMAN,
    ENGLISH,
    ;

    val label: String
        get() = when (this) {
            SYSTEM -> tr("Systemsprache", "System language")
            GERMAN -> "Deutsch"
            ENGLISH -> "English"
        }
}

/**
 * Aktuelle Sprache. Der Zustand ist ein Compose-State: Oberflächen, die [tr] beim Zeichnen
 * aufrufen, wechseln die Sprache sofort.
 */
object Lang {
    private const val FILE = "settings"
    private const val KEY = "language"

    var language by mutableStateOf(AppLanguage.SYSTEM)
        private set

    /** true = Englisch anzeigen */
    var english by mutableStateOf(!systemIsGerman())
        private set

    val locale: Locale get() = if (english) Locale.UK else Locale.GERMANY

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        apply(AppLanguage.entries.firstOrNull { it.name == p.getString(KEY, null) } ?: AppLanguage.SYSTEM)
    }

    fun set(context: Context, value: AppLanguage) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY, value.name).apply()
        apply(value)
    }

    private fun apply(value: AppLanguage) {
        language = value
        english = when (value) {
            AppLanguage.SYSTEM -> !systemIsGerman()
            AppLanguage.GERMAN -> false
            AppLanguage.ENGLISH -> true
        }
    }

    private fun systemIsGerman(): Boolean = Locale.getDefault().language == "de"
}

/** Text in der eingestellten Sprache. */
fun tr(de: String, en: String): String = if (Lang.english) en else de
