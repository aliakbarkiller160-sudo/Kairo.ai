package com.phoneagent.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
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

    /**
     * Extracts visible clickable/text UI elements with normalized (0-1000) coordinates
     * so Gemini knows the exact position of buttons, search bars, and contacts.
     */
    fun getUiTreeSummary(): String {
        val root = rootInActiveWindow ?: return ""
        val dm = resources.displayMetrics
        val width = dm.widthPixels.coerceAtLeast(1)
        val height = dm.heightPixels.coerceAtLeast(1)
        val lines = mutableListOf<String>()

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || lines.size >= 45) return
            val rect = Rect()
            node.getBoundsInScreen(rect)

            val label = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()
            val isImportant = node.isClickable || node.isEditable || label.isNotEmpty()

            if (isImportant && rect.width() > 0 && rect.height() > 0) {
                val cx = ((rect.centerX().toFloat() / width) * 1000).toInt().coerceIn(0, 1000)
                val cy = ((rect.centerY().toFloat() / height) * 1000).toInt().coerceIn(0, 1000)
                val tag = when {
                    node.isEditable -> "INPUT"
                    node.isClickable -> "BTN"
                    else -> "TXT"
                }
                val cleanLabel = label.replace("\n", " ").take(35)
                if (cleanLabel.isNotEmpty() || node.isEditable) {
                    lines.add("[$tag] \"$cleanLabel\" @ ($cx,$cy)")
                }
            }

            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        traverse(root)
        return lines.joinToString("\n")
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

    fun performKey(key: String): Boolean {
        return when (key.lowercase().trim()) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            else -> false
        }
    }

    fun performType(text: String) {
        val root = rootInActiveWindow ?: return
        var target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (target == null) {
            target = findFirstEditableNode(root)
        }
        if (target != null) {
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = findFirstEditableNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    fun openAppByName(name: String): Boolean {
        val query = name.lowercase().trim()

        val aliases = mapOf(
            "whatsapp" to "com.whatsapp",
            "whatsapp business" to "com.whatsapp.w4b",
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "browser" to "com.android.chrome",
            "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana",
            "tiktok" to "com.zhiliaoapp.musically",
            "snapchat" to "com.snapchat.android",
            "telegram" to "org.telegram.messenger",
            "settings" to "com.android.settings",
            "wifi" to "com.android.settings",
            "bluetooth" to "com.android.settings",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "gmail" to "com.google.android.gm",
            "play store" to "com.android.vending",
            "spotify" to "com.spotify.music",
            "jazzcash" to "com.techlogix.mobilinkcustomer",
            "easypaisa" to "pk.com.telenor.phoenix"
        )

        aliases[query]?.let { pkg ->
            packageManager.getLaunchIntentForPackage(pkg)?.let { intent ->
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return true
            }
        }

        try {
            when {
                query.contains("camera") -> {
                    val i = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                    return true
                }
                query == "phone" || query == "dialer" || query == "call" -> {
                    val i = Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                    return true
                }
                query == "messages" || query == "sms" -> {
                    val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                    return true
                }
                query == "settings" -> {
                    val i = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                    return true
                }
            }
        } catch (_: Exception) {}

        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(mainIntent, 0)
        }

        val exactMatch = apps.firstOrNull {
            it.loadLabel(packageManager).toString().lowercase().trim() == query
        }
        val partialMatch = exactMatch ?: apps.firstOrNull {
            val label = it.loadLabel(packageManager).toString().lowercase()
            val pkgName = it.activityInfo.packageName.lowercase()
            label.contains(query) || query.contains(label) || pkgName.contains(query)
        }

        val targetPkg = partialMatch?.activityInfo?.packageName ?: query
        val launchIntent = packageManager.getLaunchIntentForPackage(targetPkg) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)
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
