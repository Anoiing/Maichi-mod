package com.maichimod.prototype

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.CheckBox
import android.view.Gravity

internal enum class SurfaceRole { PRIMARY, OUTLINE, CARD, FEATURE, DESTRUCTIVE, INPUT, LIVE, DIALOG, ACTION, CLOSE }

/** Mediora 共享边框、触屏硬阴影、焦点与禁用规则。 */
internal class MedioraSurface(context: Context, private val role: SurfaceRole) : Drawable() {
    private val unit = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val face = RectF()
    private val ink = context.getColor(R.color.text)
    private val rule = context.getColor(R.color.divider)
    private val paper = context.getColor(R.color.panel)
    private val primary = context.getColor(R.color.primary)
    private val surface = context.getColor(R.color.control_surface)
    private val selection = context.getColor(R.color.control_selected)
    private val disabledSurface = context.getColor(R.color.disabled_surface)
    private val error = context.getColor(R.color.status_error)
    private val errorSurface = context.getColor(R.color.destructive_surface)
    private val errorBorder = context.getColor(R.color.destructive_border)
    private val liveSurface = context.getColor(R.color.live_surface)
    private val liveBorder = context.getColor(R.color.live_border)
    private val radius = context.resources.getDimension(if (role in listOf(SurfaceRole.CARD, SurfaceRole.FEATURE, SurfaceRole.DIALOG)) R.dimen.card_corner else R.dimen.control_corner)
    private val featureFinish = if (role == SurfaceRole.FEATURE) ThemeCardFinish(context) else null
    private var opacity = 255

    override fun isStateful(): Boolean = true
    override fun onStateChange(state: IntArray): Boolean { invalidateSelf(); return true }
    override fun draw(canvas: Canvas) {
        val busy = android.R.attr.state_activated in state
        val disabled = role != SurfaceRole.DIALOG && android.R.attr.state_enabled !in state && !busy
        val pressed = !disabled && android.R.attr.state_pressed in state
        val invalid = role == SurfaceRole.INPUT && android.R.attr.state_selected in state
        val inset = if (role == SurfaceRole.CLOSE) 0f else 5f * unit
        face.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
        if (pressed && role != SurfaceRole.INPUT) face.offset(unit, unit)
        paint.shader = null; paint.style = Paint.Style.FILL; paint.color = ink; paint.alpha = opacity
        if (role != SurfaceRole.INPUT && !busy) {
            val offset = (if (pressed) 1f else 2f) * unit
            canvas.drawRoundRect(face.left + offset, face.top + offset, face.right + offset, face.bottom + offset, radius, radius, paint)
        }
        paint.color = when {
            disabled -> disabledSurface
            role == SurfaceRole.PRIMARY -> primary
            role == SurfaceRole.CARD -> surface
            role == SurfaceRole.DESTRUCTIVE -> errorSurface
            role == SurfaceRole.LIVE -> liveSurface
            android.R.attr.state_checked in state -> selection
            else -> paper
        }
        paint.alpha = opacity
        canvas.drawRoundRect(face, radius, radius, paint)
        if (featureFinish != null && !disabled) {
            featureFinish.setBounds(face.left.toInt(), face.top.toInt(), face.right.toInt(), face.bottom.toInt())
            featureFinish.alpha = opacity; featureFinish.draw(canvas)
        } else {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = unit
            paint.color = when {
                disabled -> rule
                invalid -> error
                role == SurfaceRole.DESTRUCTIVE -> errorBorder
                role == SurfaceRole.LIVE -> liveBorder
                role == SurfaceRole.CARD -> rule
                else -> ink
            }
            paint.alpha = opacity; canvas.drawRoundRect(face, radius, radius, paint)
        }
        if (android.R.attr.state_focused in state && !disabled) {
            // 2dp 轮廓与表面相隔 3dp，5dp 绘制余量包含在触摸区域内。
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f * unit
            paint.color = if (invalid) error else ink; paint.alpha = opacity
            val ring = RectF(face).apply { inset(-4f * unit, -4f * unit) }
            canvas.drawRoundRect(ring, radius + 4f * unit, radius + 4f * unit, paint)
        }
    }
    override fun getPadding(padding: Rect): Boolean { val p = if (role == SurfaceRole.CLOSE) 0 else (5 * unit).toInt(); padding.set(p, p, p, p); return true }
    override fun getOutline(outline: Outline) { val p = if (role == SurfaceRole.CLOSE) 0 else (5 * unit).toInt(); outline.setRoundRect(bounds.left + p, bounds.top + p, bounds.right - p, bounds.bottom - p, radius) }
    override fun setAlpha(alpha: Int) { opacity = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

internal object MedioraUi {
    fun role(context: Context, attrs: AttributeSet, fallback: SurfaceRole): SurfaceRole {
        val values = context.obtainStyledAttributes(attrs, R.styleable.MedioraSurface)
        val result = SurfaceRole.entries.getOrElse(values.getInt(R.styleable.MedioraSurface_surfaceRole, fallback.ordinal)) { fallback }
        values.recycle(); return result
    }
    fun applySurface(view: View, role: SurfaceRole, addGutter: Boolean = true) {
        val left = view.paddingLeft; val top = view.paddingTop; val right = view.paddingRight; val bottom = view.paddingBottom
        view.backgroundTintList = null; view.background = MedioraSurface(view.context, role)
        view.elevation = 0f; view.stateListAnimator = null; view.clipToOutline = false
        val gutter = if (addGutter) (5 * view.resources.displayMetrics.density).toInt() else 0
        view.setPadding(left + gutter, top + gutter, right + gutter, bottom + gutter)
    }
    fun buttonText(context: Context, role: SurfaceRole): ColorStateList {
        val normal = context.getColor(when (role) {
            SurfaceRole.PRIMARY -> R.color.on_primary
            SurfaceRole.DESTRUCTIVE -> R.color.status_error
            SurfaceRole.FEATURE -> R.color.on_primary
            SurfaceRole.ACTION -> R.color.secondary_text
            else -> R.color.text
        })
        return ColorStateList(arrayOf(intArrayOf(android.R.attr.state_activated), intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(normal, context.getColor(R.color.muted_text), normal))
    }
}

internal open class MedioraButton(context: Context, attrs: AttributeSet) : Button(context, attrs) {
    init {
        val role = MedioraUi.role(context, attrs, SurfaceRole.OUTLINE)
        MedioraUi.applySurface(this, role)
        setTextColor(MedioraUi.buttonText(context, role))
        compoundDrawableTintList = MedioraUi.buttonText(context, role)
    }
}
internal class MedioraRadioButton(context: Context, attrs: AttributeSet) : RadioButton(context, attrs) {
    init {
        buttonDrawable = null; buttonTintList = null
        setCompoundDrawablesRelativeWithIntrinsicBounds(context.getDrawable(R.drawable.mediora_radio), null, null, null)
        compoundDrawablePadding = (8 * resources.displayMetrics.density).toInt()
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        val inset = (12 * resources.displayMetrics.density).toInt()
        setPadding(inset, paddingTop, inset, paddingBottom)
        MedioraUi.applySurface(this, SurfaceRole.OUTLINE)
        setTextColor(MedioraUi.buttonText(context, SurfaceRole.OUTLINE))
    }
}

internal class MedioraCheckBox(context: Context, attrs: AttributeSet) : CheckBox(context, attrs) {
    init {
        buttonDrawable = null; buttonTintList = null
        background = null
        setCompoundDrawablesRelativeWithIntrinsicBounds(CheckMark(context), null, null, null)
        compoundDrawablePadding = (3 * resources.displayMetrics.density).toInt()
        setTextColor(context.getColorStateList(R.color.secondary_text_state))
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
    }
}

private class CheckMark(context: Context) : Drawable() {
    private val unit = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val mark = Path()
    private val ink = context.getColor(R.color.text)
    private val primary = context.getColor(R.color.primary)
    private val contextOnPrimary = context.getColor(R.color.on_primary)
    private val paper = context.getColor(R.color.panel)
    private val disabledSurface = context.getColor(R.color.disabled_surface)
    private val disabledInk = context.getColor(R.color.muted_text)
    private var opacity = 255
    override fun getIntrinsicWidth(): Int = (26 * unit).toInt()
    override fun getIntrinsicHeight(): Int = (26 * unit).toInt()
    override fun isStateful(): Boolean = true
    override fun onStateChange(state: IntArray): Boolean { invalidateSelf(); return true }
    override fun draw(canvas: Canvas) {
        val checked = android.R.attr.state_checked in state
        val enabled = android.R.attr.state_enabled in state
        box.set(bounds.left + 5 * unit, bounds.top + 5 * unit, bounds.left + 21 * unit, bounds.top + 21 * unit)
        paint.style = Paint.Style.FILL
        paint.color = if (!enabled) disabledSurface else if (checked) primary else paper
        paint.alpha = opacity; canvas.drawRoundRect(box, 4 * unit, 4 * unit, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = unit
        paint.color = if (enabled) ink else disabledInk; paint.alpha = opacity
        canvas.drawRoundRect(box, 4 * unit, 4 * unit, paint)
        if (checked) {
            mark.reset(); mark.moveTo(box.left + 3.5f * unit, box.top + 8 * unit)
            mark.lineTo(box.left + 6.5f * unit, box.top + 11 * unit); mark.lineTo(box.left + 12.5f * unit, box.top + 5 * unit)
            paint.color = contextOnPrimary
            paint.strokeWidth = 1.5f * unit; paint.strokeJoin = Paint.Join.ROUND; paint.strokeCap = Paint.Cap.ROUND
            canvas.drawPath(mark, paint)
        }
        if (enabled && android.R.attr.state_focused in state) {
            paint.color = ink
            paint.strokeWidth = 2 * unit
            canvas.drawRoundRect(RectF(box).apply { inset(-4 * unit, -4 * unit) }, 8 * unit, 8 * unit, paint)
        }
    }
    override fun setAlpha(alpha: Int) { opacity = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
