package io.github.aitm.pleosapp.vehicle

import android.content.Context

object OccupancyReaderFactory {
    fun create(@Suppress("UNUSED_PARAMETER") context: Context): SeatOccupancyReader = object : SeatOccupancyReader {
        override suspend fun read(area: Int) = VehicleReading<Boolean>(detail = "SDK 착좌 의미 검증 필요")
        override fun close() = Unit
    }
}
