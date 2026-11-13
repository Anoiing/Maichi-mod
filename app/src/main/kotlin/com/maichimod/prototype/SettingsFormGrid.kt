package com.maichimod.prototype

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.GridLayout

/** 表单按可用宽度分栏，大字体使用单列，保留完整字段和值。 */
internal class SettingsFormGrid(context: Context, attrs: AttributeSet) : GridLayout(context, attrs) {
    private var arrangedChildren = -1

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val availableWidthDp = MeasureSpec.getSize(widthMeasureSpec) / density
        val columns = if (availableWidthDp >= 640 && resources.configuration.fontScale <= 1.3f) 2 else 1
        if (columns != columnCount || arrangedChildren != childCount) {
            // GridLayout 会立即用旧子项的列范围校验 count；先解除限制再重排，允许同一实例收窄。
            columnCount = UNDEFINED
            arrangedChildren = childCount
            for (index in 0 until childCount) {
                val column = index % columns
                getChildAt(index).layoutParams = LayoutParams().apply {
                    width = 0
                    height = LayoutParams.WRAP_CONTENT
                    columnSpec = spec(column, 1f)
                    rowSpec = spec(index / columns)
                    setGravity(Gravity.FILL_HORIZONTAL or Gravity.TOP)
                    marginStart = if (column == 0) 0 else (12 * density).toInt()
                    marginEnd = if (column == columns - 1) 0 else (12 * density).toInt()
                    bottomMargin = (16 * density).toInt()
                }
            }
            columnCount = columns
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
