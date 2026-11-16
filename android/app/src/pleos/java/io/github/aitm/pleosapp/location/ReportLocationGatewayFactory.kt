package io.github.aitm.pleosapp.location

import android.content.Context
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

object ReportLocationGatewayFactory {
    fun create(context: Context): ReportLocationGateway = NaviReportLocationGateway(context.applicationContext)
}

/** Read-only NaviHelper request. Each read releases its listener and ignores late callbacks. */
private class NaviReportLocationGateway(private val context: Context) : ReportLocationGateway {
    private val handler = Handler(Looper.getMainLooper())
    private val reads = Mutex()
    @Volatile private var closed = false
    private var closeRead: (() -> Unit)? = null

    override suspend fun read(): ReportLocation = reads.withLock {
        withContext(Dispatchers.Main.immediate) {
            if (closed) return@withContext unavailable("위치 조회가 종료됐어요.")
            suspendCancellableCoroutine { continuation ->
                var active = true
                var helper: NaviHelper? = null
                var listener: NaviHelperEventListener? = null
                var timeout: Runnable? = null
                var closeAction: (() -> Unit)? = null
                fun cleanup() {
                    if (!active) return
                    active = false
                    if (closeRead === closeAction) closeRead = null
                    timeout?.let(handler::removeCallbacks)
                    runCatching { listener?.let { helper?.removeListener(it) } }
                    runCatching { helper?.release() }
                }
                fun finish(result: ReportLocation) {
                    if (!active || !continuation.isActive) return
                    cleanup()
                    continuation.resume(result)
                }
                fun callback(block: () -> Unit) { handler.post { if (active && continuation.isActive) block() } }
                fun fail(error: Throwable) = finish(unavailable(
                    if (error is SecurityException) "신고 위치를 읽을 권한이 없어요."
                    else "내비게이션 위치 연결 실패 · ${error.javaClass.simpleName}",
                ))
                closeAction = { finish(unavailable("위치 조회가 종료됐어요.")) }
                closeRead = closeAction
                continuation.invokeOnCancellation { handler.post { cleanup() } }
                runCatching {
                    helper = NaviHelper(context)
                    listener = object : NaviHelperEventListener {
                        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
                            val receivedAt = System.currentTimeMillis()
                            callback {
                                finish(reportLocationFromProvider(
                                    currentLocationInfo.latitude, currentLocationInfo.longitude,
                                    currentLocationInfo.address, currentLocationInfo.heading, receivedAt,
                                ))
                            }
                        }
                        override fun onError(errorCode: NaviErrorCode) = callback {
                            finish(unavailable("내비게이션 위치 조회 오류 · ${errorCode.name}"))
                        }
                    }
                    helper?.addListener(requireNotNull(listener))
                    helper?.initialize(
                        onConnected = { callback { runCatching { helper?.getCurrentLocationInfo() }.onFailure(::fail) } },
                        onFailed = { error -> callback { fail(error) } },
                    )
                    timeout = Runnable { finish(unavailable("신고 위치 응답이 없어요. 주변 표지나 휴대전화 지도를 확인해주세요.")) }
                    handler.postDelayed(requireNotNull(timeout), 10_000)
                }.onFailure(::fail)
            }
        }
    }

    override fun close() {
        closed = true
        if (Looper.myLooper() == Looper.getMainLooper()) closeRead?.invoke()
        else handler.post { closeRead?.invoke() }
    }

    private fun unavailable(detail: String) = ReportLocation(status = ReportLocationStatus.UNAVAILABLE, detail = detail)
}
