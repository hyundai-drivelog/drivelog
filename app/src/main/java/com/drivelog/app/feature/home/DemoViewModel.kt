package com.drivelog.app.feature.home

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drivelog.app.BuildConfig
import com.drivelog.app.core.drive.DrivePayload
import com.drivelog.app.core.drive.MockDriveDataSource
import com.drivelog.app.core.drive.PleosDriveDataSource
import com.drivelog.app.core.drive.PleosUnavailableException
import com.drivelog.app.core.network.DriveRecord
import com.drivelog.app.core.network.createDriveApi
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import retrofit2.HttpException

data class DemoState(
    val busy: Boolean = false,
    val serverStatus: String = "연결 확인 중",
    val providerStatus: String = "Pleos 환경 확인 중",
    val preview: DrivePayload? = null,
    val recent: List<DriveRecord> = emptyList(),
    val saved: DriveRecord? = null,
    val detail: DriveRecord? = null,
    val error: String? = null,
    val message: String? = null,
    val uncertainSave: Boolean = false,
)

class DemoViewModel(application: Application) : AndroidViewModel(application) {
    var state by mutableStateOf(DemoState())
        private set
    private val api = BuildConfig.API_BASE_URL.takeIf { it.isNotBlank() }?.let(::createDriveApi)

    init {
        viewModelScope.launch {
            state = state.copy(busy = true)
            try { preparePreview(); refreshFromServer() } catch (e: Exception) { report(e) }
            finally { state = state.copy(busy = false) }
        }
    }

    private suspend fun preparePreview() {
        val reason: String
        val payload = try {
            PleosDriveDataSource(getApplication()).capture().also {
                state = state.copy(providerStatus = "PLEOS · 실제 차량 세션")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            reason = (e as? PleosUnavailableException)?.explanation ?: "Pleos 연결·권한 확인 불가"
            state = state.copy(providerStatus = "$reason · MOCK으로 전환")
            MockDriveDataSource().capture()
        }
        state = state.copy(preview = payload, saved = null, detail = null, uncertainSave = false)
    }

    fun newPreview() = runAction {
        preparePreview()
        state = state.copy(message = "새 데모 샘플을 준비했습니다. 실제 차량 데이터가 아닙니다.")
    }

    fun refresh() = runAction {
        refreshFromServer()
        state = state.copy(message = "서버에서 최근 기록을 조회했습니다.")
    }

    fun loadDetail(id: Long) = runAction {
        state = state.copy(detail = requireNotNull(api) { "서버 주소 미설정" }.detail(id), message = "상세 재조회 완료 #$id")
    }

    fun save() {
        if (state.saved != null || state.uncertainSave) return
        runAction {
            val service = requireNotNull(api) { "서버 주소 미설정" }
            val draft = requireNotNull(state.preview)
            // No automatic POST retry: a timeout may still have committed a record.
            state = state.copy(uncertainSave = true)
            val saved = service.create(draft)
            state = state.copy(saved = saved, uncertainSave = false, message = "저장 완료 #${saved.id}")
            val detail = service.detail(saved.id)
            state = state.copy(detail = detail)
            state = state.copy(recent = service.recent(), message = "저장 후 서버 재조회 완료 #${saved.id}")
        }
    }

    private suspend fun refreshFromServer() {
        val service = requireNotNull(api) { "운영 서버 주소 미설정" }
        val health = service.health()
        check(health.status == "UP" && health.service == "drivelog-server")
        state = state.copy(serverStatus = "연결됨 · DRIVELOG UP")
        state = state.copy(recent = service.recent())
    }

    private fun runAction(block: suspend () -> Unit) {
        if (state.busy) return
        state = state.copy(busy = true, error = null, message = null)
        viewModelScope.launch {
            try { block() } catch (e: Exception) { report(e) }
            finally { state = state.copy(busy = false) }
        }
    }

    private fun report(e: Exception) {
        if (e is CancellationException) throw e
        val explanation = when (e) {
            is HttpException -> "API 오류 · HTTP ${e.code()}"
            is IOException -> "서버 접근 실패 · 서버 실행과 네트워크를 확인하세요."
            else -> "요청 처리 실패 · 서버 주소와 응답을 확인하세요."
        }
        state = state.copy(
            error = explanation + if (state.uncertainSave) " 저장 결과가 불확실하므로 목록을 먼저 확인하세요." else "",
            serverStatus = if (e is IOException) "연결 실패" else state.serverStatus,
        )
    }
}
