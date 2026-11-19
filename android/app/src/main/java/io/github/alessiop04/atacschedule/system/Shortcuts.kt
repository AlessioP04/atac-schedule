package io.github.alessiop04.atacschedule.system

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import io.github.alessiop04.atacschedule.MainActivity
import io.github.alessiop04.atacschedule.data.Favorite
import io.github.alessiop04.atacschedule.ui.lineColor
import io.github.alessiop04.atacschedule.ui.lineLabel

/**
 * Scorciatoie verso gli orari di una linea:
 * - dinamiche: tenendo premuta l'icona dell'app compaiono le prime linee salvate
 * - fissate: "Aggiungi a Home" crea un'icona dedicata sulla schermata principale
 */
object Shortcuts {
    const val SCHEME = "atacschedule"

    fun lineUri(id: Long): Uri = Uri.parse("$SCHEME://line/$id")

    fun lineIntent(context: Context, id: Long): Intent =
        Intent(Intent.ACTION_VIEW, lineUri(id), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    /** Allinea le scorciatoie dinamiche ai preferiti (le prime N, nell'ordine scelto dall'utente). */
    fun update(context: Context, favorites: List<Favorite>) {
        val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtMost(4)
        ShortcutManagerCompat.setDynamicShortcuts(context, favorites.take(max).mapIndexed { i, f -> build(context, f).setRank(i).build() })
        // aggiorna nomi e icone anche delle scorciatoie fissate; quelle di linee rimosse vengono disattivate
        ShortcutManagerCompat.updateShortcuts(context, favorites.map { build(context, it).build() })
        val removed = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
            .map { it.id }.filter { id -> favorites.none { shortcutId(it) == id } }
        if (removed.isNotEmpty()) ShortcutManagerCompat.disableShortcuts(context, removed, "Linea non più tra i preferiti")
    }

    fun canPin(context: Context) = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    fun pin(context: Context, favorite: Favorite) {
        ShortcutManagerCompat.requestPinShortcut(context, build(context, favorite).build(), null)
    }

    private fun shortcutId(f: Favorite) = "line-${f.id}"

    private fun build(context: Context, f: Favorite) = ShortcutInfoCompat.Builder(context, shortcutId(f))
        .setShortLabel(f.toLabel.take(20))
        .setLongLabel("${f.routeName} · ${f.fromLabel} → ${f.toLabel}".take(40))
        .setIcon(IconCompat.createWithAdaptiveBitmap(badgeBitmap(f.routeName, f.routeType)))
        .setIntent(lineIntent(context, f.id))

    /** Icona adattiva: sfondo del colore della linea e numero al centro (nella "safe zone" di 66/108). */
    private fun badgeBitmap(routeName: String, routeType: Int): Bitmap {
        val size = 216
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        canvas.drawColor(lineColor(routeName, routeType).toArgb())
        val label = lineLabel(routeName, routeType)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = when {
                // i launcher sovrappongono l'icona dell'app in basso a destra: numeri non troppo larghi
                label.length <= 2 -> 70f
                label.length == 3 -> 54f
                label.length == 4 -> 44f
                else -> 36f
            }
        }
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(label, size / 2f, y, paint)
        return bitmap
    }
}
