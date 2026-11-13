package com.maichimod.prototype

import android.content.Context

/** 与 Mediora design-tokens.css 同名同色的九种品牌主题。 */
internal enum class ThemeColor(val key: String, val label: Int, val overlay: Int) {
    LIME("lime", R.string.theme_lime, R.style.ThemeOverlay_Maichi_Lime),
    EMERALD("emerald", R.string.theme_emerald, R.style.ThemeOverlay_Maichi_Emerald),
    CHARCOAL("charcoal", R.string.theme_charcoal, R.style.ThemeOverlay_Maichi_Charcoal),
    BLUE("blue", R.string.theme_blue, R.style.ThemeOverlay_Maichi_Blue),
    ANTDBLUE("antd-blue", R.string.theme_antd_blue, R.style.ThemeOverlay_Maichi_AntdBlue),
    VIOLET("violet", R.string.theme_violet, R.style.ThemeOverlay_Maichi_Violet),
    ROSE("rose", R.string.theme_rose, R.style.ThemeOverlay_Maichi_Rose),
    AMBER("amber", R.string.theme_amber, R.style.ThemeOverlay_Maichi_Amber),
    CORAL("coral", R.string.theme_coral, R.style.ThemeOverlay_Maichi_Coral),
    ;
    companion object {
        fun fromKey(key: String?): ThemeColor = entries.firstOrNull { it.key == key } ?: LIME
    }
}

internal object Appearance {
    private fun preferences(context: Context) = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    fun current(context: Context): ThemeColor = ThemeColor.fromKey(preferences(context).getString("theme", null))
    fun apply(context: Context) { context.theme.applyStyle(current(context).overlay, true) }
    fun save(context: Context, color: ThemeColor) { preferences(context).edit().putString("theme", color.key).apply() }
}
