package com.maichimod.prototype

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

internal class ContentLayout(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {
    private val maxWidth: Int
    private val maxHeight: Int

    init {
        val bounds = context.obtainStyledAttributes(attrs, R.styleable.ContentLayout)
        maxWidth = bounds.getDimensionPixelSize(R.styleable.ContentLayout_android_maxWidth, 0)
        maxHeight = bounds.getDimensionPixelSize(R.styleable.ContentLayout_android_maxHeight, 0)
        bounds.recycle()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(boundedSpec(widthMeasureSpec, maxWidth), boundedSpec(heightMeasureSpec, maxHeight))
    }

    private fun boundedSpec(spec: Int, limit: Int): Int {
        // A wrap-content axis fills the offered space up to the declared maximum.
        if (limit == 0 || MeasureSpec.getMode(spec) != MeasureSpec.AT_MOST) return spec
        return MeasureSpec.makeMeasureSpec(minOf(MeasureSpec.getSize(spec), limit), MeasureSpec.EXACTLY)
    }
}
