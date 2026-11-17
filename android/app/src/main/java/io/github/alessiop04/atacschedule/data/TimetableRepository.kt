package io.github.alessiop04.atacschedule.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.GZIPInputStream
import kotlin.concurrent.read
import kotlin.concurrent.write

sealed interface UpdateResult {
    data object UpToDate : UpdateResult
    data class Updated(val meta: DbMeta) : UpdateResult
    /** Il nuovo database richiede una versione più recente dell'app. */
    data object AppTooOld : UpdateResult
    data class Failed(val reason: String) : UpdateResult
}

/**
 * Possiede il database degli orari: lo installa dall'APK, lo aggiorna dalla release "db" su GitHub
 * e lo sostituisce senza interrompere le letture in corso.
 */
class TimetableRepository(private val context: Context) {

    private val lock = ReentrantReadWriteLock()
    private val updateMutex = Mutex()
    private var db: TimetableDb? = null
    @Volatile private var opened = false

    private val dbFile = File(context.filesDir, DB_NAME)
    private val prefs = context.getSharedPreferences("timetable", Context.MODE_PRIVATE)

    private val _meta = MutableStateFlow<DbMeta?>(null)
    /** Metadati del database in uso (null se non c'è ancora un database). Cambia a ogni aggiornamento. */
    val meta: StateFlow<DbMeta?> = _meta.asStateFlow()

    val lastCheckMillis: Long get() = prefs.getLong(KEY_LAST_CHECK, 0)

    /** Esegue `block` sul database corrente (da chiamare fuori dal main thread). Null se non c'è un database. */
    fun <T> read(block: (TimetableDb) -> T): T? {
        if (!opened) lock.write { if (!opened) openInitial() }
        return lock.read { db?.let(block) }
    }

    suspend fun <T> query(block: (TimetableDb) -> T): T? = withContext(Dispatchers.IO) { read(block) }

    private fun openInitial() {
        opened = true
        try {
            installBundledIfNewer()
        } catch (e: Exception) {
            Log.w(TAG, "installazione del database incluso fallita", e)
        }
        db = openOrNull(dbFile)
        _meta.value = db?.meta
    }

    private fun openOrNull(file: File): TimetableDb? =
        if (!file.exists()) null
        else try {
            TimetableDb.open(file)
        } catch (e: Exception) {
            Log.w(TAG, "database non valido: ${file.name}", e)
            null
        }

    /** Copia il database incluso nell'APK se non ce n'è uno installato o se quello incluso è più recente. */
    private fun installBundledIfNewer() {
        val bundled = try {
            context.assets.open(BUNDLED_MANIFEST).use { JSONObject(it.reader().readText()) }
        } catch (_: IOException) {
            return // APK senza database: si scaricherà
        }
        val installed = openOrNull(dbFile)?.use { it.meta }
        val bundledIsNewer = installed == null ||
            (bundled.optInt("schema_version") == TimetableDb.SCHEMA_VERSION &&
                bundled.optString("generated_at") > installed.generatedAt)
        if (!bundledIsNewer) return

        val tmp = File(context.filesDir, "$DB_NAME.new")
        GZIPInputStream(context.assets.open("$DB_NAME.gzip").buffered()).use { input -> tmp.outputStream().use { input.copyTo(it) } }
        replaceWith(tmp)
    }

    /** Sostituisce il file del database (chiamare con il write lock o prima dell'apertura). */
    private fun replaceWith(newFile: File) {
        db?.close()
        db = null
        File(dbFile.path + "-journal").delete()
        if (!newFile.renameTo(dbFile)) throw IOException("rename ${newFile.name} fallito")
    }

    /**
     * Scarica il database più recente se il feed è cambiato.
     * @param force ignora il confronto e riscarica comunque
     */
    suspend fun update(force: Boolean = false): UpdateResult = updateMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                doUpdate(force).also { prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply() }
            } catch (e: Exception) {
                Log.w(TAG, "aggiornamento fallito", e)
                UpdateResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun doUpdate(force: Boolean): UpdateResult {
        val manifest = JSONObject(httpGet(URL(MANIFEST_URL)).use { it.reader().readText() })
        if (manifest.getInt("schema_version") != TimetableDb.SCHEMA_VERSION) return UpdateResult.AppTooOld

        val current = read { it.meta }
        val remoteFeed = manifest.optString("feed_sha256")
        val upToDate = current != null && when {
            remoteFeed.isNotEmpty() && current.feedSha256.isNotEmpty() -> remoteFeed == current.feedSha256
            else -> manifest.getString("generated_at") <= current.generatedAt
        }
        if (upToDate && !force) return UpdateResult.UpToDate

        val gz = File(context.cacheDir, "timetable.db.gz.part")
        val digest = MessageDigest.getInstance("SHA-256")
        httpGet(URL(URL(MANIFEST_URL), manifest.getString("file"))).use { input ->
            gz.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (sha != manifest.getString("sha256")) {
            gz.delete()
            return UpdateResult.Failed("file scaricato corrotto")
        }

        val tmp = File(context.filesDir, "$DB_NAME.new")
        GZIPInputStream(gz.inputStream().buffered()).use { input -> tmp.outputStream().use { input.copyTo(it) } }
        gz.delete()
        TimetableDb.open(tmp).close() // verifica prima di sostituire

        val meta = lock.write {
            replaceWith(tmp)
            db = TimetableDb.open(dbFile)
            opened = true
            db!!.meta
        }
        _meta.value = meta
        return UpdateResult.Updated(meta)
    }

    private fun httpGet(url: URL): java.io.InputStream {
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "atac-schedule-android")
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            conn.disconnect()
            throw IOException("HTTP $code da ${url.host}")
        }
        return conn.inputStream.buffered()
    }

    companion object {
        private const val TAG = "TimetableRepository"
        const val DB_NAME = "timetable.db"
        private const val BUNDLED_MANIFEST = "timetable.json"
        private const val KEY_LAST_CHECK = "last_check"

        /** Pubblicato ogni notte da .github/workflows/update-db.yml. */
        const val MANIFEST_URL = "https://github.com/AlessioP04/atac-schedule/releases/download/db/manifest.json"
    }
}
