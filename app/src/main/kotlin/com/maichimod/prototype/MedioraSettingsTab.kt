package com.maichimod.prototype

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.widget.RadioButton

/** Mediora 分段导航：使用原生单选状态，触屏不实现 hover。 */
internal class MedioraSettingsTab(context: Context, attrs: AttributeSet) : RadioButton(context, attrs) {
    init {
        buttonDrawable = null
        buttonTintList = null
        backgroundTintList = null
        background = SettingsTabSurface(context)
        elevation = 0f
        stateListAnimator = null
        compoundDrawableTintList = null
    }
}

private class SettingsTabSurface(context: Context) : Drawable() {
    private val unit = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val face = RectF()
    private val ink = context.getColor(R.color.text)
    private val paper = context.getColor(R.color.panel)
    private val primary = context.getColor(R.color.primary)
    private val pressedSurface = context.getColor(R.color.control_surface)
    private var opacity = 255

    override fun isStateful(): Boolean = true
    override fun onStateChange(state: IntArray): Boolean { invalidateSelf(); return true }
    override fun draw(canvas: Canvas) {
        val checked = android.R.attr.state_checked in state
        val focused = android.R.attr.state_focused in state
        val pressed = android.R.attr.state_pressed in state
        face.set(bounds.left + 5 * unit, bounds.top + 5 * unit, bounds.right - 5 * unit, bounds.bottom - 5 * unit)
        paint.style = Paint.Style.FILL; paint.alpha = opacity
        if (checked || pressed) {
            paint.color = if (checked) paper else pressedSurface
            paint.alpha = opacity
            canvas.drawRoundRect(face, 9 * unit, 9 * unit, paint)
        }
        if (checked) {
            paint.color = primary; paint.alpha = opacity
            canvas.drawRoundRect(face.left + 12 * unit, face.bottom - 2 * unit, face.right - 12 * unit, face.bottom, unit, unit, paint)
        }
        if (focused) {
            paint.color = ink; paint.alpha = opacity; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * unit
            canvas.drawRoundRect(RectF(face).apply { inset(-4 * unit, -4 * unit) }, 13 * unit, 13 * unit, paint)
        }
    }
    override fun setAlpha(alpha: Int) { opacity = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
