package com.godavin.vince

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * v2.4 - VINCE's hands and eyes. Does nothing on its own: it only reads the screen
 * or taps/types when PhoneAgent asks, and PhoneAgent only runs when the user
 * gives a command. All sends, payments and trades go through a confirmation.
 */
class VinceAccessibilityService : AccessibilityService() {

    class Snapshot(val packageName: String, val text: String, val nodes: Map<Int, AccessibilityNodeInfo>) {
        fun label(id: Int): String {
            val n = nodes[id] ?: return ""
            return (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()
        }
    }

    companion object {
        @Volatile var instance: VinceAccessibilityService? = null
        val isRunning: Boolean get() = instance != null

        /** True if the user has switched VINCE phone control on in Android settings. */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(context, VinceAccessibilityService::class.java).flattenToString()
            return enabled.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* VINCE only acts on command */ }
    override fun onInterrupt() {}

    // ------------------------------------------------------------------
    // Reading

    fun currentPackage(): String = rootInActiveWindow?.packageName?.toString().orEmpty()

    /** Compact numbered list of what is on screen. Null if the window is hidden/secure. */
    fun snapshot(): Snapshot? {
        val root = rootInActiveWindow ?: return null
        val nodes = LinkedHashMap<Int, AccessibilityNodeInfo>()
        val sb = StringBuilder()
        var id = 0

        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || id >= 150) return
            if (!n.isVisibleToUser) return
            val text = n.text?.toString()?.trim()?.replace("\n", " ")?.take(140)
            val desc = n.contentDescription?.toString()?.trim()?.replace("\n", " ")?.take(100)
            val hasInfo = !text.isNullOrBlank() || !desc.isNullOrBlank() || n.isClickable || n.isEditable || n.isScrollable
            if (hasInfo) {
                id++
                nodes[id] = n
                sb.append('[').append(id).append("] ")
                    .append(n.className?.toString()?.substringAfterLast('.') ?: "View")
                if (!text.isNullOrBlank()) sb.append(" \"").append(text).append('"')
                if (!desc.isNullOrBlank() && desc != text) sb.append(" (desc: ").append(desc).append(')')
                if (n.isEditable) sb.append(" [editable]")
                if (n.isClickable) sb.append(" [click]")
                if (n.isScrollable) sb.append(" [scroll]")
                if (n.isChecked) sb.append(" [checked]")
                sb.append('\n')
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return Snapshot(root.packageName?.toString().orEmpty(), sb.toString(), nodes)
    }

    // ------------------------------------------------------------------
    // Acting

    fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < 6) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
            hops++
        }
        return tapCenter(node)
    }

    fun tapCenter(node: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 70))
            .build()
        return dispatchGesture(g, null, null)
    }

    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scroll(snapshot: Snapshot, forward: Boolean): Boolean {
        val target = snapshot.nodes.values.firstOrNull { it.isScrollable } ?: return false
        return target.performAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )
    }

    fun global(action: Int): Boolean = performGlobalAction(action)

    /** Finds a visible node whose text/description equals (or, failing that, contains) [label]. */
    fun findByLabel(label: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val hits = root.findAccessibilityNodeInfosByText(label)
        if (hits.isNullOrEmpty()) return null
        val visible = hits.filter { it.isVisibleToUser }
        val exact = visible.firstOrNull {
            (it.text?.toString() ?: it.contentDescription?.toString() ?: "").trim().equals(label, ignoreCase = true)
        }
        return exact ?: visible.firstOrNull()
    }
}
