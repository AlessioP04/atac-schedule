package io.github.alessiop04.atacschedule

import android.app.Application
import io.github.alessiop04.atacschedule.data.FavoritesStore
import io.github.alessiop04.atacschedule.data.TimetableRepository
import io.github.alessiop04.atacschedule.data.UpdateWorker
import java.time.ZoneId

class App : Application() {
    val timetable by lazy { TimetableRepository(this) }
    val favorites by lazy { FavoritesStore(this) }

    override fun onCreate() {
        super.onCreate()
        UpdateWorker.schedule(this)
    }

    companion object {
        /** Gli orari sono sempre quelli di Roma, ovunque si trovi il telefono. */
        val ROME: ZoneId = ZoneId.of("Europe/Rome")
    }
}
