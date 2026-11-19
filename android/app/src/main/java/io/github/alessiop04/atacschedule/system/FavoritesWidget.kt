package io.github.alessiop04.atacschedule.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.alessiop04.atacschedule.App
import io.github.alessiop04.atacschedule.MainActivity
import io.github.alessiop04.atacschedule.data.Favorite
import io.github.alessiop04.atacschedule.domain.Format
import io.github.alessiop04.atacschedule.ui.lineColor
import io.github.alessiop04.atacschedule.ui.lineLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDateTime

private data class WidgetRow(val favorite: Favorite, val next: List<LocalDateTime>)

/** Widget con le linee salvate e i prossimi passaggi; toccando una riga si aprono i suoi orari. */
class FavoritesWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as App
        val favorites = app.favorites.items.value
        val now = LocalDateTime.now(App.ROME)
        val rows = app.timetable.query { db -> favorites.map { WidgetRow(it, db.schedule(it.key).upcoming(now, 3)) } }
            ?: favorites.map { WidgetRow(it, emptyList()) }
        scheduleRefresh(context, rows)
        provideContent { GlanceTheme { Content(rows, now) } }
    }

    @Composable
    private fun Content(rows: List<WidgetRow>, now: LocalDateTime) {
        Column(
            GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(20.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                "Prossimi passaggi",
                style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>()),
            )
            Spacer(GlanceModifier.height(6.dp))
            if (rows.isEmpty()) {
                Text(
                    "Nessuna linea salvata: tocca per aprire l'app e aggiungerne una.",
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp),
                    modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>()),
                )
            } else {
                LazyColumn {
                    items(rows, itemId = { it.favorite.id }) { row -> RowContent(row, now) }
                }
            }
        }
    }

    @Composable
    private fun RowContent(row: WidgetRow, now: LocalDateTime) {
        val f = row.favorite
        Row(
            GlanceModifier.fillMaxWidth().padding(vertical = 5.dp)
                .clickable(actionStartActivity(Shortcuts.lineIntent(LocalContext.current, f.id))),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                GlanceModifier.width(46.dp).background(ColorProvider(lineColor(f.routeName, f.routeType))).cornerRadius(6.dp)
                    .padding(vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                val label = lineLabel(f.routeName, f.routeType)
                Text(label, style = TextStyle(color = ColorProvider(Color.White), fontSize = if (label.length > 4) 11.sp else 13.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            }
            Spacer(GlanceModifier.width(8.dp))
            Text(
                "${f.fromLabel} → ${f.toLabel}",
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            Spacer(GlanceModifier.width(8.dp))
            Text(
                row.next.joinToString("  ") { t ->
                    val hm = Format.time(t.hour * 60 + t.minute)
                    if (t.toLocalDate() == now.toLocalDate()) hm else "${Format.weekday(t.toLocalDate())} $hm"
                }.ifEmpty { "—" },
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
                maxLines = 1,
            )
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Ridisegna tutti i widget (dopo modifiche ai preferiti o un nuovo database). */
        fun refresh(context: Context) {
            scope.launch { FavoritesWidget().updateAll(context.applicationContext) }
        }

        /**
         * Ridisegna il widget appena il primo passaggio mostrato diventa passato.
         * Allarme non "wakeup": se il telefono dorme scatta alla riaccensione dello schermo, cioè quando serve.
         */
        private fun scheduleRefresh(context: Context, rows: List<WidgetRow>) {
            val next = rows.mapNotNull { it.next.firstOrNull() }.minOrNull() ?: return
            val at = next.plusMinutes(1).atZone(App.ROME).toInstant().toEpochMilli()
            val alarm = context.getSystemService(AlarmManager::class.java) ?: return
            alarm.set(AlarmManager.RTC, at, tickIntent(context))
        }

        private fun tickIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(context, WidgetTickReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

class FavoritesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FavoritesWidget()
}

/** Riceve l'allarme di [FavoritesWidget.scheduleRefresh]. */
class WidgetTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                FavoritesWidget().updateAll(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}
