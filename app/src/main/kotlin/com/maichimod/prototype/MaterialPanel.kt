package com.maichimod.prototype

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ImageView

internal class MaterialPanel(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {
    init { MedioraUi.applySurface(this, MedioraUi.role(context, attrs, SurfaceRole.CARD)) }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        childrenState(this) { child ->
            child.isEnabled = enabled
            updateIconTint(child)
        }
    }

    override fun setActivated(activated: Boolean) {
        super.setActivated(activated)
        childrenState(this) { child -> child.isActivated = activated; updateIconTint(child) }
    }

    private fun updateIconTint(child: View) {
        if (child is ImageView) child.imageTintList = if (!isEnabled && !isActivated) ColorStateList.valueOf(context.getColor(R.color.muted_text)) else null
    }

    private fun childrenState(parent: ViewGroup, update: (View) -> Unit) {
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            update(child)
            if (child is ViewGroup) childrenState(child, update)
        }
    }
}
