package io.github.aitm.pleosapp.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import io.github.aitm.pleosapp.accident.AccidentLocation
import io.github.aitm.pleosapp.accident.LocationOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

interface AccidentLocationGateway : AutoCloseable {
    suspend fun read(): AccidentLocation?
    fun needsPermission(): Boolean
    override fun close()
}

/** Foreground-only GPS reads, optionally followed by the Pleos NaviHelper reporting request. */
internal class DeviceAccidentLocationGateway(
    context: Context,
    private val navigation: ReportLocationGateway? = null,
) : AccidentLocationGateway {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(LocationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val reads = Mutex()
    @Volatile private var closed = false
    // Accessed only on the main looper; close() posts there when necessary.
    private var closeGpsRead: (() -> Unit)? = null

    override fun needsPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) != PackageManager.PERMISSION_GRANTED

    override suspend fun read(): AccidentLocation? = reads.withLock {
        if (closed) return@withLock null
        val gps = readGps()
        if (closed) return@withLock null
        if (gps != null) return@withLock gps
        // NaviHelper can still be available when this app lacks Android GPS permission.
        val report = try {
            navigation?.read()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (closed || report == null) null else accidentLocationFromNavigation(report, System.currentTimeMillis())
    }

    @SuppressLint("MissingPermission") // Checked immediately before both LocationManager calls; revocation is caught.
    private suspend fun readGps(): AccidentLocation? = withContext(Dispatchers.Main.immediate) {
        val locationManager = manager ?: return@withContext null
        if (closed || needsPermission()) return@withContext null
        if (!runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)) {
            return@withContext null
        }
        val cached = runCatching { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
        cached?.asAccidentLocation()?.let { return@withContext it }
        suspendCancellableCoroutine { continuation ->
            var active = true
            val cancellation = CancellationSignal()
            var timeout: Runnable? = null
            var closeAction: (() -> Unit)? = null
            fun cleanup() {
                if (!active) return
                active = false
                if (closeGpsRead === closeAction) closeGpsRead = null
                timeout?.let(handler::removeCallbacks)
                // AndroidX removes its API 28/29 listener as well as cancelling newer platform requests.
                runCatching { cancellation.cancel() }
            }
            fun finish(location: AccidentLocation?) {
                if (!active) return
                cleanup()
                if (continuation.isActive) continuation.resume(location)
            }
            closeAction = { finish(null) }
            closeGpsRead = closeAction
            continuation.invokeOnCancellation {
                if (Looper.myLooper() == Looper.getMainLooper()) cleanup()
                else handler.post { cleanup() }
            }
            if (!continuation.isActive || closed) {
                finish(null)
                return@suspendCancellableCoroutine
            }
            timeout = Runnable { finish(null) }
            handler.postDelayed(requireNotNull(timeout), GPS_REQUEST_TIMEOUT_MILLIS)
            runCatching {
                // Core 1.15 uses getCurrentLocation on API 30+ and a cancellable listener on API 28/29.
                LocationManagerCompat.getCurrentLocation(
                    locationManager, LocationManager.GPS_PROVIDER, cancellation,
                    ContextCompat.getMainExecutor(context),
                ) { location ->
                    if (active) finish(location?.asAccidentLocation())
                }
            }.onFailure { finish(null) }
        }
    }

    override fun close() {
        closed = true
        if (Looper.myLooper() == Looper.getMainLooper()) closeGpsRead?.invoke()
        else handler.post { closeGpsRead?.invoke() }
        navigation?.close()
    }

    @Suppress("DEPRECATION")
    private fun Location.asAccidentLocation(): AccidentLocation? {
        val receivedAt = System.currentTimeMillis()
        val receivedElapsedNanos = SystemClock.elapsedRealtimeNanos()
        if (provider != LocationManager.GPS_PROVIDER) return null
        return accidentLocationFromGps(
            GpsFix(
                latitude = latitude,
                longitude = longitude,
                fixObservedAt = time,
                elapsedRealtimeNanos = elapsedRealtimeNanos.takeIf { it > 0 },
                accuracyMeters = accuracy.toDouble().takeIf { hasAccuracy() },
                headingDegrees = bearing.toDouble().takeIf { hasBearing() },
                mock = if (Build.VERSION.SDK_INT >= 31) isMock else isFromMockProvider,
            ),
            receivedAt, receivedElapsedNanos,
        )
    }
}

private const val GPS_REQUEST_TIMEOUT_MILLIS = 3_000L

/** Provider values are copied without changing a cached fix's original timestamp. */
internal data class GpsFix(
    val latitude: Double,
    val longitude: Double,
    val fixObservedAt: Long,
    val elapsedRealtimeNanos: Long?,
    val accuracyMeters: Double?,
    val headingDegrees: Double? = null,
    val mock: Boolean = false,
)

internal fun accidentLocationFromGps(
    fix: GpsFix,
    receivedAt: Long,
    receivedElapsedNanos: Long,
): AccidentLocation? {
    // Monotonic time rejects an old fix even if the wall clock changed or was restamped externally.
    fix.elapsedRealtimeNanos?.let { observed ->
        if (observed < 0 || receivedElapsedNanos < 0 ||
            receivedElapsedNanos - observed !in -5_000_000_000L..15_000_000_000L) return null
    }
    val result = AccidentLocation(
        latitude = fix.latitude,
        longitude = fix.longitude,
        receivedAt = receivedAt,
        fixObservedAt = fix.fixObservedAt,
        accuracyMeters = fix.accuracyMeters,
        headingDegrees = fix.headingDegrees?.takeIf { it.isFinite() && it in 0.0..<360.0 },
        origin = LocationOrigin.GPS,
        mock = fix.mock,
    )
    return result.takeIf { it.structurallyValid() }
}

internal fun accidentLocationFromNavigation(report: ReportLocation, now: Long): AccidentLocation? {
    if (report.status != ReportLocationStatus.AVAILABLE || !report.hasCoordinates || report.sample) return null
    val receivedAt = report.receivedAt ?: return null
    return AccidentLocation(
        latitude = requireNotNull(report.latitude),
        longitude = requireNotNull(report.longitude),
        receivedAt = receivedAt,
        // NaviHelper supplies neither field. Callback receipt must not be presented as a fix time.
        fixObservedAt = null,
        accuracyMeters = null,
        headingDegrees = report.headingDegrees?.takeIf { it.isFinite() && it in 0.0..<360.0 },
        address = report.address?.trim()?.takeIf { it.isNotEmpty() && it.length <= 300 },
        origin = LocationOrigin.NAVI,
    ).takeIf { it.current(now) }
}
