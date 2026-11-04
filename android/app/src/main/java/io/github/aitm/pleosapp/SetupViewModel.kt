package io.github.aitm.pleosapp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.aitm.pleosapp.backend.BackendHealthClient
import io.github.aitm.pleosapp.vehicle.VehicleGatewayFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SetupState(
    val vehicleMessage: String = "버튼을 눌러 조회해 주세요.",
    val backendMessage: String = "연결 확인 전",
    val readingVehicle: Boolean = false,
    val checkingBackend: Boolean = false,
)

class SetupViewModel(application: Application) : AndroidViewModel(application) {
    private val gateway = VehicleGatewayFactory.create(application)
    private val backend = BackendHealthClient(BuildConfig.SUPABASE_URL)
    private val mutableState = MutableStateFlow(SetupState())
    val state = mutableState.asStateFlow()
    val sourceLabel = gateway.sourceLabel
    val backendConfigured = backend.isConfigured

    fun readVehicle() {
        if (state.value.readingVehicle) return
        mutableState.update { it.copy(readingVehicle = true) }
        viewModelScope.launch {
            try {
                val message = gateway.readDriverDoor()
                mutableState.update { it.copy(vehicleMessage = message) }
            } catch (_: TimeoutCancellationException) {
                mutableState.update { it.copy(vehicleMessage = "SDK 응답 시간 초과 · Pleos 서비스를 확인해 주세요.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(vehicleMessage = "조회 실패: ${error.message ?: error.javaClass.simpleName}") }
            } finally {
                mutableState.update { it.copy(readingVehicle = false) }
            }
        }
    }

    fun checkBackend() {
        if (!backendConfigured || state.value.checkingBackend) return
        mutableState.update { it.copy(checkingBackend = true) }
        viewModelScope.launch {
            try {
                val message = backend.check()
                mutableState.update { it.copy(backendMessage = message) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(backendMessage = "연결 실패: ${error.message ?: error.javaClass.simpleName}") }
            } finally {
                mutableState.update { it.copy(checkingBackend = false) }
            }
        }
    }

    override fun onCleared() {
        gateway.close()
        super.onCleared()
    }
}
