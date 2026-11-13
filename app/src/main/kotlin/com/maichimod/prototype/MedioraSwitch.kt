package com.maichimod.prototype

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.widget.CompoundButton

/** 保留原生 checked、点击及辅助功能语义，以固定令牌绘制 32×20 的开关。 */
internal class MedioraSwitch(context: Context, attrs: AttributeSet) : CompoundButton(context, attrs) {
    init {
        buttonDrawable = null
        buttonTintList = null
        background = null
        isAllCaps = false
        // CompoundButton 的无样式构造器没有原生 Switch 的点击/焦点默认值。
        isClickable = true
        isFocusable = true
        compoundDrawablePadding = (16 * resources.displayMetrics.density).toInt()
        setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, SwitchMark(context), null)
    }

    override fun getAccessibilityClassName(): CharSequence = "android.widget.Switch"
}

private class SwitchMark(context: Context) : Drawable() {
    private val unit = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = RectF()
    private val ink = context.getColor(R.color.text)
    private val primary = context.getColor(R.color.primary)
    private val onPrimary = context.getColor(R.color.on_primary)
    private val surface = context.getColor(R.color.control_surface)
    private val disabledSurface = context.getColor(R.color.disabled_surface)
    private val disabledInk = context.getColor(R.color.muted_text)
    private var opacity = 255
    override fun getIntrinsicWidth(): Int = (42 * unit).toInt()
    override fun getIntrinsicHeight(): Int = (30 * unit).toInt()
    override fun isStateful(): Boolean = true
    override fun onStateChange(state: IntArray): Boolean { invalidateSelf(); return true }
    override fun draw(canvas: Canvas) {
        val checked = android.R.attr.state_checked in state
        val enabled = android.R.attr.state_enabled in state
        track.set(bounds.left + 5 * unit, bounds.top + 5 * unit, bounds.left + 37 * unit, bounds.top + 25 * unit)
        paint.style = Paint.Style.FILL
        paint.color = if (!enabled) disabledSurface else if (checked) primary else surface
        paint.alpha = opacity; canvas.drawRoundRect(track, 10 * unit, 10 * unit, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = unit
        paint.color = if (enabled) ink else disabledInk
        paint.alpha = opacity; canvas.drawRoundRect(track, 10 * unit, 10 * unit, paint)
        paint.style = Paint.Style.FILL
        paint.color = if (enabled && checked) onPrimary else if (enabled) ink else disabledInk
        canvas.drawCircle(track.left + (if (checked) 22 else 10) * unit, track.centerY(), 8 * unit, paint)
        if (enabled && android.R.attr.state_focused in state) {
            paint.color = ink
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * unit
            canvas.drawRoundRect(RectF(track).apply { inset(-4 * unit, -4 * unit) }, 14 * unit, 14 * unit, paint)
        }
    }
    override fun setAlpha(alpha: Int) { opacity = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
