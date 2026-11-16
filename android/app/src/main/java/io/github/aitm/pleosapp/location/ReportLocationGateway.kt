package io.github.aitm.pleosapp.location

enum class ReportLocationStatus { NOT_REQUESTED, LOADING, AVAILABLE, UNAVAILABLE }

/** A reporting aid, independent from the explicit TEST road position used for sharing. */
data class ReportLocation(
    val status: ReportLocationStatus = ReportLocationStatus.NOT_REQUESTED,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val address: String? = null,
    val headingDegrees: Double? = null,
    val receivedAt: Long? = null,
    val fixObservedAt: Long? = null,
    val accuracyMeters: Double? = null,
    val sample: Boolean = false,
    val detail: String = "",
) {
    val hasCoordinates: Boolean
        get() = latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0

    init {
        require(status != ReportLocationStatus.AVAILABLE || hasCoordinates || !address.isNullOrBlank()) {
            "Available report location needs valid coordinates or an address"
        }
    }
}

interface ReportLocationGateway : AutoCloseable {
    suspend fun read(): ReportLocation
    override fun close()
}

class UnavailableReportLocationGateway(
    private val detail: String = "이 빌드에서는 신고 위치를 조회할 수 없어요. 주변 표지나 휴대전화 지도를 확인해주세요.",
) : ReportLocationGateway {
    override suspend fun read() = ReportLocation(status = ReportLocationStatus.UNAVAILABLE, detail = detail)
    override fun close() = Unit
}

/** NaviHelper has no provider fix time or accuracy; callback receipt never fills those fields. */
internal fun reportLocationFromProvider(
    latitude: Double?, longitude: Double?, address: String?, heading: Double?, receivedAt: Long,
): ReportLocation {
    val coordinatePair = latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0
    val knownAddress = address?.trim()?.takeIf { it.isNotEmpty() }
    return ReportLocation(
        status = if (coordinatePair || knownAddress != null) ReportLocationStatus.AVAILABLE else ReportLocationStatus.UNAVAILABLE,
        latitude = latitude.takeIf { coordinatePair },
        longitude = longitude.takeIf { coordinatePair },
        address = knownAddress,
        headingDegrees = heading?.takeIf { it.isFinite() && it >= 0.0 && it < 360.0 },
        receivedAt = receivedAt,
        detail = if (coordinatePair || knownAddress != null) "내비게이션에서 받은 위치예요. 측위 시각과 정확도는 제공되지 않았어요."
            else "내비게이션 응답에 사용할 수 있는 좌표나 주소가 없어요.",
    )
}
