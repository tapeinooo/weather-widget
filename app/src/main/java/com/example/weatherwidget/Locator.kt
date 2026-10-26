package com.example.weatherwidget

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** 端末の現在地（おおよそ）と、その地名を調べる。呼ぶ前に位置情報の許可を取っておく。 */
object Locator {

    @SuppressLint("MissingPermission")
    suspend fun currentPlace(context: Context): Place? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { manager.isProviderEnabled(it) } ?: return null

        val location: Location = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                LocationManagerCompat.getCurrentLocation(
                    manager, provider, signal, ContextCompat.getMainExecutor(context),
                ) { if (cont.isActive) cont.resume(it) }
            }
        } ?: manager.getLastKnownLocation(provider) ?: return null

        val name = placeName(context, location.latitude, location.longitude) ?: "現在地"
        return Place(name, location.latitude, location.longitude)
    }

    private suspend fun placeName(context: Context, lat: Double, lon: Double): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                Geocoder(context, Locale.JAPAN).getFromLocation(lat, lon, 1)?.firstOrNull()
                    ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
            }.getOrNull()
        }
}
