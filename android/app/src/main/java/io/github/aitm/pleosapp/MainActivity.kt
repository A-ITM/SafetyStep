package io.github.aitm.pleosapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF173B66))) {
                SetupScreen()
            }
        }
    }
}

@Composable
private fun SetupScreen(model: SetupViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("A+ ITM", style = MaterialTheme.typography.headlineLarge)
            Text("개발 환경 점검 · ${BuildConfig.FLAVOR}", style = MaterialTheme.typography.titleLarge)
            Text("개발용 화면입니다. 정차된 테스트 환경에서 사용해 주세요.")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("차량 데이터", style = MaterialTheme.typography.titleLarge)
                    Text(model.sourceLabel)
                    if (BuildConfig.FLAVOR == "pleos") {
                        Text("Vehicle ${BuildConfig.VEHICLE_SDK_VERSION} · NaviHelper ${BuildConfig.NAVI_SDK_VERSION}")
                        Text("NaviHelper는 의존성만 준비되어 있습니다.")
                    }
                    Text(state.vehicleMessage)
                    Button(onClick = model::readVehicle, enabled = !state.readingVehicle) {
                        Text(if (state.readingVehicle) "조회 중…" else "운전석 문 상태 조회")
                    }
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Supabase 연결", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (model.backendConfigured) state.backendMessage
                        else "미설정 · 프로젝트 연결 없이 차량 앱을 개발할 수 있습니다.",
                    )
                    Button(
                        onClick = model::checkBackend,
                        enabled = model.backendConfigured && !state.checkingBackend,
                    ) { Text(if (state.checkingBackend) "확인 중…" else "서버 연결 확인") }
                }
            }
        }
    }
}
