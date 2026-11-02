package com.drivelog.app.feature.card

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.drivelog.app.feature.drive.DriveUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DriveCardScreen(state: DriveUiState, allowed: Boolean, onTemplate: (Int) -> Unit,
        onPhoto: (String?) -> Unit, onSaved: (String) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { onPhoto(it.toString()) } }
    LaunchedEffect(state.summary, state.template, state.photoUri) {
        state.summary?.let { session ->
            busy = true
            preview = null
            try { preview = withContext(Dispatchers.Default) { DriveCardRenderer.render(context, session, state.template, state.photoUri) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onError("사진을 읽거나 카드를 생성할 수 없습니다. 데모 사진을 다시 선택하세요.") }
            finally { busy = false }
        }
    }
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("드라이브 카드", style = MaterialTheme.typography.headlineLarge)
            Text("정차 후 편집 · ${state.summary?.mode ?: "주행 종료 후 이용"}")
        }
        if (state.summary == null) item { Text("주행 기록을 먼저 시작하고 종료하세요.") }
        else {
            if (allowed) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { onPhoto(null) }, enabled = !busy, modifier = Modifier.testTag("demo-photo")) { Text("데모 사진") }
                        OutlinedButton(onClick = { picker.launch("image/*") }, enabled = !busy) { Text("로컬 사진 선택") }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf("저널", "포토").forEachIndexed { index, title ->
                            FilterChip(selected = state.template == index, onClick = { onTemplate(index) }, enabled = !busy, label = { Text(title) }, modifier = Modifier.testTag("template-$index"))
                        }
                    }
                }
                item {
                    Button(onClick = {
                        val bitmap = preview ?: return@Button
                        scope.launch {
                            busy = true
                            try { onSaved(withContext(Dispatchers.IO) { DriveCardRenderer.save(context, bitmap) }) }
                            catch (_: Exception) { onError("PNG 저장에 실패했습니다. 저장 공간과 사진 접근을 확인하세요.") }
                            finally { busy = false }
                        }
                    }, enabled = !busy && preview != null, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("export-card")) { Text("PNG 생성 · 갤러리에 저장") }
                }
            } else item { Text("차량 UX 제한 활성 · 사진 편집 및 저장 잠금", color = MaterialTheme.colorScheme.error) }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.exportPath?.let { path -> item { Text("이미지 생성 완료 · 갤러리 Pictures/DRIVELOG", modifier = Modifier.testTag("card-export-success")); Text(path, style = MaterialTheme.typography.bodySmall) } }
            preview?.let { bitmap -> item { Image(bitmap.asImageBitmap(), "완성된 ${state.summary.mode} 카드 미리보기", Modifier.fillMaxWidth().heightIn(max = 720.dp).testTag("card-preview")) } }
        }
        state.message?.let { message -> item { Text(message) } }
    }
}
