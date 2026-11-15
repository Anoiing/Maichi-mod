package com.maichimod.prototype

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/** Equal cards at tablet size; content grows and rows stack when space or type requires it. */
internal class HomeTileRow(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val primary = childCount == 2
        val stacked = MeasureSpec.getSize(widthMeasureSpec) / density < (if (primary) 368 else 512) || resources.configuration.fontScale > 1.3f
        orientation = if (stacked) VERTICAL else HORIZONTAL
        val gap = (16 * density).toInt()
        val target = ((if (primary) 264 else 160) * density).toInt()
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            child.minimumHeight = target
            (child.layoutParams as LayoutParams).apply {
                width = if (stacked) LayoutParams.MATCH_PARENT else 0
                height = if (stacked) LayoutParams.WRAP_CONTENT else LayoutParams.MATCH_PARENT
                weight = if (stacked) 0f else 1f
                marginStart = if (!stacked && index > 0) gap else 0
                bottomMargin = if (stacked && index < childCount - 1) gap else 0
                resolveLayoutDirection(layoutDirection)
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
