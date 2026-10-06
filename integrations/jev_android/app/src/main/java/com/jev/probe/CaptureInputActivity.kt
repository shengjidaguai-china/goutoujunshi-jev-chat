package com.jev.probe

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jev.probe.capture.ocr.CaptureHandoff
import com.jev.probe.capture.ocr.ProjectionCaptureService
import com.jev.probe.capture.ocr.ScreenCapture

/** Each explicit system capture opens Android's consent; never reuse a grant. */
class CaptureInputActivity : AppCompatActivity() {
    private val requestId get() = intent.getStringExtra(EXTRA_REQUEST) ?: ""
    private var handedOff = false

    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = requestId
        if (CaptureHandoff.get(id) == null) { finish(); return@registerForActivityResult }
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            fail("已取消系统截屏授权")
        } else {
            runCatching {
                ContextCompat.startForegroundService(this, Intent(this, ProjectionCaptureService::class.java)
                    .putExtra(EXTRA_REQUEST, id).putExtra("grant", result.data)
                    .putExtra("resultCode", result.resultCode))
                handedOff = true
                finish()
            }.onFailure { fail("无法启动系统截屏，请检查截屏方式或重试") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CaptureHandoff.get(requestId) == null) { finish(); return }
        if (savedInstanceState != null) return
        runCatching {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val consent = if (Build.VERSION.SDK_INT >= 34)
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else manager.createScreenCaptureIntent()
            projection.launch(consent)
        }.onFailure { fail("无法打开系统入口，请返回聊天后重试") }
    }

    private fun fail(message: String) {
        val id = requestId
        handedOff = true
        finish()
        Handler(Looper.getMainLooper()).postDelayed({
            CaptureHandoff.complete(id, ScreenCapture.Result.Failed(-10, message))
        }, 200)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && !handedOff) CaptureHandoff.cancel(requestId)
    }

    companion object {
        const val EXTRA_REQUEST = "captureRequest"
    }
}
