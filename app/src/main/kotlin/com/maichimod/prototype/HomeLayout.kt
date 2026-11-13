package com.maichimod.prototype

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/** 保持规范字号；窄窗口或放大字体时改为上下布局，不缩小正文和时钟。 */
internal class HomeLayout(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val stacked = MeasureSpec.getSize(widthMeasureSpec) / resources.displayMetrics.density < 840 || resources.configuration.fontScale > 1.3f
        orientation = if (stacked) VERTICAL else HORIZONTAL
        for (index in 0 until childCount) {
            val params = getChildAt(index).layoutParams as LayoutParams
            params.width = if (stacked) LayoutParams.MATCH_PARENT else 0
            params.height = if (stacked) LayoutParams.WRAP_CONTENT else LayoutParams.MATCH_PARENT
            params.weight = if (stacked) 0f else if (index == 0) 0.67f else 1.33f
            params.marginEnd = if (!stacked && index == 0) resources.getDimensionPixelSize(R.dimen.home_column_gap) else 0
            params.bottomMargin = if (stacked && index == 0) resources.getDimensionPixelSize(R.dimen.page_gap) else 0
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
