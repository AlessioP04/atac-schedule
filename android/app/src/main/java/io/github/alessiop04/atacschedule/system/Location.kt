package io.github.alessiop04.atacschedule.system

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Posizione attuale con il solo LocationManager di Android: niente Google Play Services. */
object Locator {
    val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasPermission(context: Context) = PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun isEnabled(context: Context): Boolean =
        context.getSystemService(LocationManager::class.java)?.let(LocationManagerCompat::isLocationEnabled) == true

    /** Una posizione recente (< 2 minuti) se c'è, altrimenti ne chiede una nuova (max 15 s). */
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): Location? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = lm.getProviders(true)
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 120_000) return last

        val provider = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && LocationManager.FUSED_PROVIDER in providers -> LocationManager.FUSED_PROVIDER
            LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
            LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
            else -> return last
        }
        return withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { cont ->
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                LocationManagerCompat.getCurrentLocation(lm, provider, cancel, ContextCompat.getMainExecutor(context)) { location ->
                    if (cont.isActive) cont.resume(location ?: last)
                }
            }
        } ?: last
    }
}
