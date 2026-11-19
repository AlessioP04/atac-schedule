package io.github.alessiop04.atacschedule

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import io.github.alessiop04.atacschedule.data.AppPrefs
import io.github.alessiop04.atacschedule.data.FavoritesStore
import io.github.alessiop04.atacschedule.data.TimetableRepository
import io.github.alessiop04.atacschedule.data.UpdateWorker
import io.github.alessiop04.atacschedule.system.FavoritesWidget
import io.github.alessiop04.atacschedule.system.Shortcuts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.ZoneId

class App : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration get() = Configuration.Builder().build()

    val timetable by lazy { TimetableRepository(this) }
    val favorites by lazy { FavoritesStore(this) }
    val prefs by lazy { AppPrefs(this) }

    /** Lavori in background che non devono rallentare l'apertura dell'app. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        scope.launch { UpdateWorker.schedule(this@App) } // fuori dal main thread: non rallenta l'apertura
        scope.launch {
            var first = true
            favorites.items.collectLatest { list ->
                try {
                    Shortcuts.update(this@App, list)
                } catch (e: Exception) {
                    Log.w("App", "scorciatoie non aggiornate", e)
                }
                if (!first) FavoritesWidget.refresh(this@App)
                first = false
            }
        }
        scope.launch {
            // un nuovo database (aggiornamento) cambia gli orari mostrati dal widget
            timetable.meta.filterNotNull().map { it.generatedAt }.distinctUntilChanged().drop(1)
                .collectLatest { FavoritesWidget.refresh(this@App) }
        }
    }

    companion object {
        /** Gli orari sono sempre quelli di Roma, ovunque si trovi il telefono. */
        val ROME: ZoneId = ZoneId.of("Europe/Rome")
    }
}
