package io.github.aitm.pleosapp.speech

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.widget.TextView
import java.io.File
import org.json.JSONObject

/** Tests ACTION_ASSIST from the ordinary app UID in the foreground, not from the ADB shell UID. */
class GleoLaunchProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))) {
            finish(); return
        }
        setContentView(TextView(this).apply {
            text = "Gleo 표준 ASSIST 실행 진단 · 사고 자동 감지나 선제 질문 검증이 아닙니다."
        })
        if (savedInstanceState != null) return
        val report = JSONObject().put("uid", Process.myUid()).put("action", Intent.ACTION_ASSIST)
            .put("scope", "foreground app launch only; no incident context or question supplied")
        runCatching {
            startActivity(Intent(Intent.ACTION_ASSIST).setPackage("ai.umos.ambientai"))
        }.onSuccess {
            report.put("result", "START_ACTIVITY_RETURNED")
        }.onFailure {
            report.put("result", "FAILED").put("error", "${it.javaClass.simpleName}: ${it.message}")
        }
        File(filesDir, "gleo-launch-probe.json").writeText(report.toString(2))
        Log.i("GleoLaunchProbe", report.toString())
    }
}
