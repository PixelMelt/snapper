package com.snapper.android.overlay

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper

private const val ACCESSIBILITY_BUTTON_CLASS = "android.widget.Button"

internal class SelectionAccessibility(private val view: SelectionView) : ExploreByTouchHelper(view) {
    private val bounds = Rect()

    override fun getVirtualViewAt(x: Float, y: Float): Int = view.virtualViewAt(x, y)

    override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
        view.collectVisibleVirtualViews(virtualViewIds)
    }

    override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
        node.className = ACCESSIBILITY_BUTTON_CLASS
        node.contentDescription = view.accessibilityLabel(virtualViewId)
        node.isClickable = true
        node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
        if (!view.virtualViewBounds(virtualViewId, bounds)) {
            bounds.set(0, 0, 0, 0)
        }
        node.setBoundsInParent(bounds)
    }

    override fun onPopulateEventForVirtualView(virtualViewId: Int, event: AccessibilityEvent) {
        event.className = ACCESSIBILITY_BUTTON_CLASS
        event.contentDescription = view.accessibilityLabel(virtualViewId)
    }

    override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
        if (!view.activateVirtualView(virtualViewId)) return false
        sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
        return true
    }

    override fun onPopulateNodeForHost(node: AccessibilityNodeInfoCompat) {
        node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_DISMISS)
        val maximum = view.maximumActionScroll()
        val scrollable = maximum > 0f
        node.isScrollable = scrollable
        if (scrollable && view.actionScrollX < maximum - 0.5f) {
            node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
            node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_RIGHT)
        }
        if (scrollable && view.actionScrollX > 0.5f) {
            node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_BACKWARD)
            node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_LEFT)
        }
    }

    override fun onInitializeAccessibilityEvent(host: View, event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(host, event)
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            view.populateScrollEvent(event)
        }
    }

    override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean =
        view.performHostAccessibilityAction(action) || super.performAccessibilityAction(host, action, args)
}
