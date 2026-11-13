package com.maichimod.prototype

import android.content.Context
import android.util.AttributeSet

/** 将原生按钮的顶部图标和文字作为一组居中，保留按钮语义及全部触摸状态。 */
internal class CallActionButton(context: Context, attrs: AttributeSet) : MedioraButton(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val icon = compoundDrawables[1] ?: return
        val contentHeight = icon.bounds.height() + compoundDrawablePadding + (layout?.height ?: lineHeight)
        val inset = ((measuredHeight - contentHeight) / 2).coerceAtLeast(0)
        if (paddingTop != inset || paddingBottom != inset) {
            setPadding(paddingLeft, inset, paddingRight, inset)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
