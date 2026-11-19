package io.github.alessiop04.atacschedule.data

import android.content.Context
import android.util.Log
import io.github.alessiop04.atacschedule.BuildConfig
import io.github.alessiop04.atacschedule.domain.Versions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Una versione dell'app pubblicata su GitHub, più recente di quella installata. */
data class AppRelease(val version: String, val pageUrl: String)

/** Piccole preferenze dell'app e avviso di nuove versioni. */
class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    private val _compactHome = MutableStateFlow(prefs.getBoolean(KEY_COMPACT, false))
    /** Home a griglia (due tragitti per riga) invece che a schede complete. */
    val compactHome: StateFlow<Boolean> = _compactHome.asStateFlow()

    fun setCompactHome(value: Boolean) {
        _compactHome.value = value
        prefs.edit().putBoolean(KEY_COMPACT, value).apply()
    }

    private val _newRelease = MutableStateFlow(storedRelease())
    /** Versione più recente disponibile su GitHub, se diversa da quella installata. */
    val newRelease: StateFlow<AppRelease?> = _newRelease.asStateFlow()

    private fun storedRelease(): AppRelease? {
        val version = prefs.getString(KEY_RELEASE_VERSION, null) ?: return null
        val url = prefs.getString(KEY_RELEASE_URL, null) ?: return null
        return AppRelease(version, url).takeIf { Versions.isNewer(version, BuildConfig.VERSION_NAME) }
    }

    /** Chiede a GitHub l'ultima release dell'app (l'API pubblica non richiede token). */
    suspend fun checkForNewRelease() = withContext(Dispatchers.IO) {
        try {
            val conn = URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "atac-schedule-android")
            val json = try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext
                JSONObject(conn.inputStream.reader().readText())
            } finally {
                conn.disconnect()
            }
            val tag = json.getString("tag_name")
            val url = json.getString("html_url")
            prefs.edit().putString(KEY_RELEASE_VERSION, tag).putString(KEY_RELEASE_URL, url).apply()
            _newRelease.value = AppRelease(tag.removePrefix("v"), url).takeIf { Versions.isNewer(tag, BuildConfig.VERSION_NAME) }
        } catch (e: Exception) {
            Log.w("AppPrefs", "controllo nuova versione fallito", e)
        }
    }

    private companion object {
        const val KEY_COMPACT = "compact_home"
        const val KEY_RELEASE_VERSION = "latest_release"
        const val KEY_RELEASE_URL = "latest_release_url"
        const val LATEST_RELEASE_API = "https://api.github.com/repos/AlessioP04/atac-schedule/releases/latest"
    }
}
