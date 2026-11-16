package com.phoneagent.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AgentAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun performTap(normX: Float, normY: Float) {
        val dm = resources.displayMetrics
        val x = (normX.coerceIn(0f, 1000f) / 1000f) * dm.widthPixels
        val y = (normY.coerceIn(0f, 1000f) / 1000f) * dm.heightPixels
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun performSwipe(nx1: Float, ny1: Float, nx2: Float, ny2: Float) {
        val dm = resources.displayMetrics
        val x1 = (nx1.coerceIn(0f, 1000f) / 1000f) * dm.widthPixels
        val y1 = (ny1.coerceIn(0f, 1000f) / 1000f) * dm.heightPixels
        val x2 = (nx2.coerceIn(0f, 1000f) / 1000f) * dm.widthPixels
        val y2 = (ny2.coerceIn(0f, 1000f) / 1000f) * dm.heightPixels
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun performType(text: String) {
        val root = rootInActiveWindow ?: return
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focus != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
    }

    fun openAppByName(name: String): Boolean {
        val map = mapOf(
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana",
            "telegram" to "org.telegram.messenger",
            "settings" to "com.android.settings",
            "maps" to "com.google.android.apps.maps",
            "gmail" to "com.google.android.gm"
        )
        val pkg = map[name.lowercase().trim()] ?: name.trim()
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return true
    }

    suspend fun captureScreenBase64(): String? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bmp = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    val soft = bmp?.copy(Bitmap.Config.ARGB_8888, false)
                    screenshot.hardwareBuffer.close()
                    if (soft != null) {
                        val scaled = Bitmap.createScaledBitmap(soft, 540, (540f * soft.height / soft.width).toInt(), true)
                        val out = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
                        cont.resume(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
                    } else {
                        cont.resume(null)
                    }
                }
                override fun onFailure(errorCode: Int) {
                    cont.resume(null)
                }
            })
        } else {
            cont.resume(null)
        }
    }
}
