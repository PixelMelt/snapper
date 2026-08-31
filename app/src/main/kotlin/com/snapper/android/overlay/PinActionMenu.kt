package com.snapper.android.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.ContextThemeWrapper
import android.view.Menu
import android.view.View
import android.widget.PopupMenu
import com.snapper.android.R
import com.snapper.android.actions.SnapperActionRegistry

internal const val MENU_REMOVE = 1
internal const val MENU_ACTION_BASE = 100
private const val MENU_GROUP_DESTRUCTIVE = 2

internal fun showPinActionMenu(
    context: Context,
    anchor: View,
    providers: List<SnapperActionRegistry.Action>,
    actionIcons: Array<Bitmap>,
    onItem: (Int) -> Boolean,
    onDismiss: () -> Unit,
): PopupMenu {
    val popupContext: Context = ContextThemeWrapper(context, R.style.SnapperPinPopupTheme)
    val darkArtwork = SnapperActionRegistry.usesDarkMenuArtwork(popupContext)
    val density = context.resources.displayMetrics.density
    val popup = PopupMenu(popupContext, anchor)
    val menu = popup.menu
    menu.setGroupDividerEnabled(true)

    providers.forEachIndexed { index, action ->
        val item = menu.add(
            Menu.NONE, MENU_ACTION_BASE + action.selectionAction, index, action.menuTitle(context),
        )
        val icon = action.providerIcon
            ?: SnapperActionRegistry.menuIcon(context, darkArtwork, action.id)
            ?: actionIcons.getOrNull(action.iconIndex)
        if (icon != null) {
            item.icon = PopupMenuIconDrawable(icon, density)
        }
    }

    val errorColor = popupContext.getColor(R.color.snapper_menu_error)
    val removeTitle = SpannableString(popupContext.getString(R.string.pin_menu_remove))
    removeTitle.setSpan(ForegroundColorSpan(errorColor), 0, removeTitle.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    val remove = menu.add(MENU_GROUP_DESTRUCTIVE, MENU_REMOVE, providers.size, removeTitle)
    remove.icon = requireNotNull(popupContext.getDrawable(R.drawable.snapper_menu_trash))
        .mutate()
        .apply { setTint(errorColor) }

    popup.setForceShowIcon(true)
    popup.setOnMenuItemClickListener { item -> onItem(item.itemId) }
    popup.setOnDismissListener { onDismiss() }
    popup.show()
    return popup
}

private class PopupMenuIconDrawable(private val bitmap: Bitmap, density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val source = Rect(0, 0, bitmap.width, bitmap.height)
    private val target = RectF()
    private val intrinsicSize = Math.max(1, Math.round(BOX_DP * density))
    private val contentInset = CONTENT_INSET_DP * density

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val availableWidth = Math.max(0f, bounds.width() - 2f * contentInset)
        val availableHeight = Math.max(0f, bounds.height() - 2f * contentInset)
        if (availableWidth == 0f || availableHeight == 0f) return
        val scale = Math.min(availableWidth / bitmap.width, availableHeight / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = bounds.exactCenterX() - width * 0.5f
        val top = bounds.exactCenterY() - height * 0.5f
        target.set(left, top, left + width, top + height)
        canvas.drawBitmap(bitmap, source, target, paint)
    }

    override fun getIntrinsicWidth(): Int = intrinsicSize

    override fun getIntrinsicHeight(): Int = intrinsicSize

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val BOX_DP = 24f
        const val CONTENT_INSET_DP = 1f
    }
}
