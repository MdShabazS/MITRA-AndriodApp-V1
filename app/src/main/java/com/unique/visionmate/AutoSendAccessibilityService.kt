package com.unique.visionmate

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import kotlin.math.abs
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AutoSendAccessibilityService : AccessibilityService() {

    companion object {
        // Live handle to the running accessibility service. Accessibility services are trusted to
        // act for the user, so launching an app through this works from the background where a
        // normal service's startActivity is blocked (Android 12+/OEM restrictions).
        @Volatile
        var instance: AutoSendAccessibilityService? = null

        /** Closes the current app by going to the home screen. Returns true if performed. */
        fun goHome(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_HOME) ?: false

        /** Presses the system Back button. Returns true if performed. */
        fun goBack(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false

        fun openRecents(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_RECENTS) ?: false
        fun openNotifications(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) ?: false
        fun openQuickSettings(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) ?: false

        fun lockScreen(): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false
            else false

        fun takeScreenshot(): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                instance?.performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT) ?: false
            else false

        /** Returns true if the launch was performed via the accessibility service. */
        fun tryLaunch(intent: Intent): Boolean {
            val svc = instance
            if (svc == null) {
                Log.w("MITRA_ACC", "tryLaunch: accessibility service NOT connected (instance null)")
                return false
            }
            return svc.launchIntent(intent)
        }
    }

    /** Launches an activity from the accessibility service context. */
    fun launchIntent(intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Log.i("MITRA_ACC", "launched via accessibility: ${intent.component ?: intent.`package`}")
            true
        } catch (e: Exception) {
            Log.w("MITRA_ACC", "accessibility launch failed: ${e.message}")
            false
        }
    }

    private val takePictureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BackgroundService.ACTION_TAKE_PICTURE) {
                triggerCameraShutter()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i("MITRA_ACC", "accessibility service connected")
        val filter = IntentFilter(BackgroundService.ACTION_TAKE_PICTURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(takePictureReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(takePictureReceiver, filter)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val rootNode = rootInActiveWindow ?: return
        clickWhatsAppSend(rootNode)
        clickFirstYouTubeVideo(rootNode)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        try { unregisterReceiver(takePictureReceiver) } catch (_: Exception) {}
    }

    // ================= CAMERA SHUTTER =================

    private fun triggerCameraShutter() {
        val root = rootInActiveWindow

        // Strategy 1: find by shutter-related keyword in resource ID or content description
        if (root != null) {
            val shutterKeywords = listOf(
                "shutter", "take_photo", "take photo", "take picture", "capture",
                "shoot", "btn_shutter", "camera_button", "shutter_button", "photo_button"
            )
            if (findAndClickNode(root, shutterKeywords)) return
        }

        // Strategy 2: find any clickable ImageButton / Button in the bottom 40% of screen
        if (root != null && clickBottomCenterButton(root)) return

        // Strategy 3: tap multiple y-positions (different camera apps place shutter differently)
        tapAt(0.82f)
    }

    private fun findAndClickNode(node: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val resId = node.viewIdResourceName?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""

        val matches = keywords.any { kw -> desc.contains(kw) || resId.contains(kw) || text.contains(kw) }
        if (matches && node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickNode(child, keywords)) return true
        }
        return false
    }

    /** Finds the most centered clickable button in the bottom 40% of the screen. */
    private fun clickBottomCenterButton(root: AccessibilityNodeInfo): Boolean {
        val screenH = resources.displayMetrics.heightPixels
        val screenW = resources.displayMetrics.widthPixels
        val bottomThreshold = screenH * 0.60f

        var bestNode: AccessibilityNodeInfo? = null
        var bestDist = Float.MAX_VALUE

        fun scan(node: AccessibilityNodeInfo) {
            val isButton = node.className?.toString()?.let {
                it.contains("ImageButton") || it.contains("Button")
            } == true
            if (isButton && node.isClickable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.centerY() > bottomThreshold) {
                    val distFromCenter = abs(rect.centerX() - screenW / 2f)
                    if (distFromCenter < bestDist) {
                        bestDist = distFromCenter
                        bestNode = node
                    }
                }
            }
            for (i in 0 until node.childCount) {
                scan(node.getChild(i) ?: continue)
            }
        }
        scan(root)

        bestNode?.let {
            it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        return false
    }

    private fun tapAt(yFraction: Float) {
        val display = resources.displayMetrics
        val x = display.widthPixels / 2f
        val y = display.heightPixels * yFraction
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    // ================= WHATSAPP SEND =================

    private fun clickWhatsAppSend(node: AccessibilityNodeInfo) {
        if (node.className == "android.widget.ImageButton") {
            val description = node.contentDescription?.toString()?.lowercase()
            if (description != null && description.contains("send")) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) clickWhatsAppSend(child)
        }
    }

    // ================= YOUTUBE AUTO PLAY =================

    private fun clickFirstYouTubeVideo(node: AccessibilityNodeInfo?) {
        if (node == null) return
        val className = node.className?.toString()
        val text = node.text?.toString()?.lowercase()
        val description = node.contentDescription?.toString()?.lowercase()
        if (className == "android.view.ViewGroup") {
            if (
                (text != null && text.contains("views")) ||
                (description != null && description.contains("video"))
            ) {
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return
                }
            }
        }
        for (i in 0 until node.childCount) {
            clickFirstYouTubeVideo(node.getChild(i))
        }
    }
}
