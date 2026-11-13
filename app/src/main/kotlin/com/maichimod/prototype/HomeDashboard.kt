package com.maichimod.prototype

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.PI
import java.util.Random

/** Mediora LightRays 的七束错相摆动与稀疏流星；离开首页、失焦或关闭动画时停止刷新。 */
internal class HomeDashboard(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {
    private data class Ray(val left: Float, val angle: Float, val width: Float, val swing: Float, val drift: Float, val delay: Double, val duration: Double, val phase: Float)
    private val unit = resources.displayMetrics.density
    private val primary = context.getColor(R.color.primary)
    private val charcoal = Appearance.current(context) == ThemeColor.CHARCOAL
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val started = SystemClock.uptimeMillis()
    private val rays = List(7) { index ->
        val random = Random(19183L + index * 1327L)
        Ray(0.04f + index / 6f * 0.92f + (random.nextFloat() - 0.5f) * 0.08f,
            -18f + random.nextFloat() * 36f, 100f + random.nextFloat() * 80f,
            12f + random.nextFloat() * 8f, 70f + random.nextFloat() * 45f,
            random.nextDouble() * 0.4, 6 * (0.75 + random.nextDouble() * 0.5), if (index % 2 == 0) 1f else -1f)
    }
    private val beamShaders = ArrayList<Shader>()
    private val beamWidths = ArrayList<Float>()
    private val meteor = Paint(Paint.ANTI_ALIAS_FLAG)
    private val meteorShaders = ArrayList<Shader>()
    private val tails = FloatArray(9) { (40 + it % 3 * 14) * unit }
    private val meteorColor = Color.rgb(
        (Color.red(primary) * 0.7f + 38 * 0.3f).toInt(),
        (Color.green(primary) * 0.7f + 38 * 0.3f).toInt(),
        (Color.blue(primary) * 0.7f + 38 * 0.3f).toInt(),
    )
    init { setWillNotDraw(false); clipToOutline = true }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        beamShaders.clear(); beamWidths.clear()
        meteorShaders.clear()
        for (tail in tails) meteorShaders.add(LinearGradient(0f, 0f, tail, -tail, meteorColor, transparent(meteorColor), Shader.TileMode.CLAMP))
        for (ray in rays) {
            val half = min(ray.width * unit, (w * ray.width / 1400f).coerceAtLeast(48 * unit)) / 2
            beamWidths.add(half)
            val alpha = if (charcoal) 0.06f else 0.22f
            val horizontal = LinearGradient(-half, 0f, half, 0f, intArrayOf(transparent(primary), withAlpha(primary, alpha), transparent(primary)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            val vertical = LinearGradient(0f, 0f, 0f, h * 1.25f, intArrayOf(Color.WHITE, 0xC7FFFFFF.toInt(), 0x38FFFFFF, 0), floatArrayOf(0f, 0.38f, 0.78f, 1f), Shader.TileMode.CLAMP)
            beamShaders.add(ComposeShader(horizontal, vertical, PorterDuff.Mode.DST_IN))
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val animated = ValueAnimator.areAnimatorsEnabled()
        val time = (SystemClock.uptimeMillis() - started) / 1000.0
        for (index in rays.indices) {
            val ray = rays[index]
            if (index >= beamShaders.size) continue
            val wave = if (animated) cos((time - ray.delay) / ray.duration * 2 * PI).toFloat() * ray.phase else 0f
            val save = canvas.save()
            canvas.translate(width * ray.left - ray.drift * wave * unit, -height * 0.12f)
            canvas.rotate(ray.angle - ray.swing * wave)
            paint.shader = beamShaders[index]
            paint.alpha = if (animated) (255 * (0.04f + 0.96f * (wave + 1) / 2)).toInt() else 46
            canvas.drawRect(-beamWidths[index], 0f, beamWidths[index], height * 1.25f, paint)
            canvas.restoreToCount(save)
        }
        if (animated) {
            paint.shader = null
            for (index in 0..8) {
                val cycle = 7.5 + index % 4
                val progress = ((time + index * 0.91) % cycle / cycle).toFloat()
                if (progress > 0.3f) continue
                val fade = when { progress < 0.02f -> progress / 0.02f; progress < 0.2f -> 1f; else -> (0.3f - progress) / 0.1f }
                val travel = (width + height) * progress / 0.3f
                val x = width * (0.15f + index * 0.16f) - travel * 0.7071f
                val y = -2 * unit + travel * 0.7071f
                if (index >= meteorShaders.size) continue
                val tail = tails[index]
                meteor.shader = meteorShaders[index]
                meteor.alpha = (255 * fade * if (charcoal) 0.4f else 0.6f).toInt()
                meteor.strokeWidth = 1.5f * unit
                val save = canvas.save()
                canvas.translate(x, y)
                canvas.drawLine(0f, 0f, tail, -tail, meteor)
                canvas.restoreToCount(save)
            }
        }
        if (animated && isShown && hasWindowFocus() && windowVisibility == View.VISIBLE) postInvalidateDelayed(33)
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) { super.onWindowFocusChanged(hasWindowFocus); if (hasWindowFocus) invalidate() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); if (visibility == View.VISIBLE) invalidate() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); if (visibility == View.VISIBLE) invalidate() }
    private fun transparent(color: Int): Int = color and 0x00FFFFFF
    private fun withAlpha(color: Int, opacity: Float): Int = transparent(color) or ((255 * opacity.coerceIn(0f, 1f)).toInt() shl 24)
}
