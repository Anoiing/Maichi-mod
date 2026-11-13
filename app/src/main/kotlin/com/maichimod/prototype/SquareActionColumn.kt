package com.maichimod.prototype

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/** 标题或系统字体占用更多高度时，两个通话操作等比缩小，始终保持方形。 */
internal class SquareActionColumn(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val gap = (0 until childCount).sumOf { index ->
            val params = getChildAt(index).layoutParams as LayoutParams
            params.topMargin + params.bottomMargin
        }
        val maximum = resources.getDimensionPixelSize(R.dimen.call_action_size)
        val side = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) maximum else {
            minOf(maximum, (MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom - gap) / childCount.coerceAtLeast(1))
        }.coerceAtLeast(1)
        for (index in 0 until childCount) {
            getChildAt(index).layoutParams.apply { width = side; height = side }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
