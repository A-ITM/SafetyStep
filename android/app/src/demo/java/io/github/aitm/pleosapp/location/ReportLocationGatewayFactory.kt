package io.github.aitm.pleosapp.location

import android.content.Context

object ReportLocationGatewayFactory {
    fun create(@Suppress("UNUSED_PARAMETER") context: Context): ReportLocationGateway =
        UnavailableReportLocationGateway("Demo는 실제 신고 위치를 조회하지 않아요. 주변 표지나 휴대전화 지도를 확인해주세요.")
}
