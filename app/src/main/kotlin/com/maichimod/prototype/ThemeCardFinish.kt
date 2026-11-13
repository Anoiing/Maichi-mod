package com.maichimod.prototype

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** 主题色主卡，装饰只使用当前主题的前景色，不改变卡片尺寸。 */
internal class ThemeCardFinish(context: Context) : Drawable() {
    private val unit = context.resources.displayMetrics.density
    private val primary = context.getColor(R.color.primary)
    private val foreground = context.getColor(R.color.on_primary)
    private val ink = context.getColor(R.color.text)
    private val radius = context.resources.getDimension(R.dimen.card_corner)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val face = RectF()
    private val clip = Path()
    private var opacity = 255
    override fun draw(canvas: Canvas) {
        face.set(bounds)
        paint.style = Paint.Style.FILL; paint.color = primary; paint.alpha = opacity
        canvas.drawRoundRect(face, radius, radius, paint)
        val save = canvas.save()
        clip.reset(); clip.addRoundRect(face, radius, radius, Path.Direction.CW); canvas.clipPath(clip)
        paint.color = foreground; paint.style = Paint.Style.STROKE; paint.strokeWidth = unit
        paint.alpha = opacity * 13 / 100
        val cx = face.right - face.width() * 0.07f
        val cy = face.bottom + face.height() * 0.18f
        val base = face.height() * 0.62f
        for (index in 0..3) canvas.drawCircle(cx, cy, base + index * 24 * unit, paint)
        paint.style = Paint.Style.FILL; paint.alpha = opacity * 5 / 100
        canvas.drawCircle(cx, cy, base - 28 * unit, paint)
        canvas.restoreToCount(save)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = unit; paint.color = ink; paint.alpha = opacity
        canvas.drawRoundRect(face, radius, radius, paint)
    }
    override fun setAlpha(alpha: Int) { opacity = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
