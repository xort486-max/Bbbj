package com.jarvis.app.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.vosk.Model
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** 호출어 모델 상태. */
sealed interface HotwordModelStatus {
    data object NotDownloaded : HotwordModelStatus
    data class Downloading(val progress: Float) : HotwordModelStatus
    data object Ready : HotwordModelStatus
    data class Failed(val message: String) : HotwordModelStatus
}

/**
 * 오프라인 호출어 인식용 Vosk 한국어 소형 모델 관리 (다운로드 · 압축 해제 · 로드).
 *
 * 모델: vosk-model-small-ko-0.22 (Apache 2.0, 약 87MB, alphacephei.com 공식 배포).
 * "자비스"가 모델 어휘에 포함되어 있음을 확인함 (2026-11-22).
 */
class HotwordModelManager(
    context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build(),
) {
    private val root = File(context.filesDir, "hotword")
    private val modelDir = File(root, MODEL_NAME)
    private val completeMarker = File(modelDir, ".complete")

    private val _status = MutableStateFlow(if (completeMarker.exists()) HotwordModelStatus.Ready else HotwordModelStatus.NotDownloaded)
    val status: StateFlow<HotwordModelStatus> = _status.asStateFlow()

    @Volatile private var loaded: Model? = null

    val isReady: Boolean get() = completeMarker.exists()

    suspend fun download() = withContext(Dispatchers.IO) {
        if (isReady) {
            _status.value = HotwordModelStatus.Ready
            return@withContext
        }
        _status.value = HotwordModelStatus.Downloading(0f)
        val zip = File(root, "$MODEL_NAME.zip.part")
        try {
            root.mkdirs()
            client.newCall(Request.Builder().url(MODEL_URL).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("다운로드 실패 (HTTP ${response.code})")
                val total = response.body.contentLength().takeIf { it > 0 } ?: EXPECTED_BYTES
                response.body.byteStream().use { input ->
                    zip.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            _status.value = HotwordModelStatus.Downloading((done.toFloat() / total).coerceIn(0f, 0.99f))
                        }
                    }
                }
            }
            unzip(zip, root)
            if (!File(modelDir, "am/final.mdl").exists()) throw IOException("모델 파일이 올바르지 않습니다")
            completeMarker.writeText("ok")
            _status.value = HotwordModelStatus.Ready
        } catch (e: Exception) {
            modelDir.deleteRecursively()
            _status.value = HotwordModelStatus.Failed(e.message ?: "다운로드 실패")
        } finally {
            zip.delete()
        }
    }

    /** 모델을 메모리에 올린다 (수 초 걸릴 수 있어 IO 스레드에서). */
    suspend fun model(): Model = withContext(Dispatchers.IO) {
        loaded ?: run {
            if (!isReady) throw HotwordUnavailableException("호출어 모델이 없습니다. 설정에서 먼저 다운로드하세요.")
            Model(modelDir.absolutePath).also { loaded = it }
        }
    }

    fun delete() {
        loaded?.close()
        loaded = null
        root.deleteRecursively()
        _status.value = HotwordModelStatus.NotDownloaded
    }

    private fun unzip(zip: File, target: File) {
        val canonicalTarget = target.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val out = File(target, entry.name)
                // Zip Slip 방지: 대상 폴더 밖으로 나가는 경로 거부
                if (!out.canonicalPath.startsWith(canonicalTarget)) throw IOException("잘못된 압축 항목: ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
            }
        }
    }

    companion object {
        const val MODEL_NAME = "vosk-model-small-ko-0.22"
        const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
        const val EXPECTED_BYTES = 86_914_329L
        const val SIZE_LABEL = "약 87MB"
    }
}
