package com.jarvis.app

import android.content.Intent
import android.speech.RecognitionService
import org.robolectric.RuntimeEnvironment
import com.jarvis.app.assist.AssistActivity
import com.jarvis.app.assist.JarvisInteractionService
import com.jarvis.app.assist.JarvisRecognitionService
import com.jarvis.app.assist.JarvisTileService
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 매니페스트 등록 검증: 시스템이 JARVIS를 어시스턴트로 찾을 수 있어야 한다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AssistantRegistrationTest {
    private val context = RuntimeEnvironment.getApplication()
    private val pm = context.packageManager

    @Test
    fun assistIntentOpensOverlay() {
        val activities = pm.queryIntentActivities(Intent(Intent.ACTION_ASSIST).setPackage(context.packageName), 0)
        assertEquals(AssistActivity::class.java.name, activities.single().activityInfo.name)
        val voice = pm.queryIntentActivities(Intent(Intent.ACTION_VOICE_COMMAND).setPackage(context.packageName), 0)
        assertEquals(AssistActivity::class.java.name, voice.single().activityInfo.name)
    }

    @Test
    fun voiceInteractionServiceIsDeclaredWithPermissionAndMetadata() {
        val services = pm.queryIntentServices(
            Intent("android.service.voice.VoiceInteractionService").setPackage(context.packageName),
            android.content.pm.PackageManager.GET_META_DATA,
        )
        val info = services.single().serviceInfo
        assertEquals(JarvisInteractionService::class.java.name, info.name)
        assertEquals(android.Manifest.permission.BIND_VOICE_INTERACTION, info.permission)
        assertTrue(info.metaData.containsKey("android.voice_interaction"))
    }

    @Test
    fun recognitionProxyAndTileAreDeclared() {
        val recognizers = pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE).setPackage(context.packageName), 0)
        assertEquals(JarvisRecognitionService::class.java.name, recognizers.single().serviceInfo.name)
        val tiles = pm.queryIntentServices(Intent("android.service.quicksettings.action.QS_TILE").setPackage(context.packageName), 0)
        assertEquals(JarvisTileService::class.java.name, tiles.single().serviceInfo.name)
    }
}
