package io.github.aitm.pleosapp.accident

import io.github.aitm.pleosapp.prototype.EventFreshness
import io.github.aitm.pleosapp.prototype.ReceiverStatus
import kotlin.math.*

enum class LocationOrigin { GPS, NAVI, TEST }
data class AccidentLocation(
    val latitude: Double,
    val longitude: Double,
    val receivedAt: Long,
    val fixObservedAt: Long? = null,
    val accuracyMeters: Double? = null,
    val headingDegrees: Double? = null,
    val address: String? = null,
    val origin: LocationOrigin,
    val mock: Boolean = false,
) {
    fun structurallyValid(): Boolean = latitude.isFinite() && latitude in -90.0..90.0 &&
        longitude.isFinite() && longitude in -180.0..180.0 && receivedAt > 0 &&
        (headingDegrees == null || headingDegrees.isFinite() && headingDegrees in 0.0..<360.0) &&
        (accuracyMeters == null || accuracyMeters.isFinite() && accuracyMeters > 0 && accuracyMeters <= 100) &&
        (fixObservedAt == null || fixObservedAt > 0 && receivedAt - fixObservedAt in -5_000..15_000) &&
        (origin != LocationOrigin.GPS || fixObservedAt != null && accuracyMeters != null) &&
        (address == null || address.length <= 300)
    fun current(now: Long): Boolean = structurallyValid() && now - receivedAt in -5_000..15_000 &&
        (fixObservedAt == null || now - fixObservedAt in -5_000..15_000)
}

data class AccidentEvent(
    val id: String,
    val owner: String,
    val source: String,
    val occurredAt: Long,
    val location: AccidentLocation,
    val expiresAt: Long = occurredAt + 300_000,
    val version: Int = 1,
    val status: String = "ACTIVE",
)
enum class AccidentShareStatus { IDLE, CONSENT_REQUIRED, LOCATING, QUEUED, REGISTERED, FAILED, EXPIRED }
data class NearbyAccident(val event: AccidentEvent, val distanceMeters: Double?, val freshness: EventFreshness)
data class AccidentNetworkUi(
    val shareStatus: AccidentShareStatus = AccidentShareStatus.IDLE,
    val sharedEvent: AccidentEvent? = null,
    val currentLocation: AccidentLocation? = null,
    val nearby: List<NearbyAccident> = emptyList(),
    val receiverStatus: ReceiverStatus = ReceiverStatus.OFF,
    val detail: String = "",
    val locationPermissionNeeded: Boolean = false,
    val environmentLabel: String = "로컬 시험",
    val environmentDetail: String = "",
    val legacyTransportEnabled: Boolean = true,
)

fun accidentDistance(a: AccidentLocation, b: AccidentLocation): Double {
    val lat1 = Math.toRadians(a.latitude); val lat2 = Math.toRadians(b.latitude)
    val dlat = lat2 - lat1; val dlon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dlat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dlon / 2).pow(2)
    return 6_371_000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}
