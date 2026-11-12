package com.maichimod.prototype

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.view.View
import android.widget.TextView
import android.widget.Toast

internal class VolumeControls(page: View) {
    private val context = page.context
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val hint = page.findViewById<TextView>(R.id.volume_hint)
    private val rows = listOf(
        VolumeRow(page.findViewById(R.id.media_volume), AudioManager.STREAM_MUSIC, R.string.media_volume),
        VolumeRow(page.findViewById(R.id.call_volume), AudioManager.STREAM_VOICE_CALL, R.string.call_volume),
    )

    fun refresh() {
        val fixed = audio.isVolumeFixed
        hint.setText(if (fixed) R.string.volume_fixed else R.string.volume_immediate)
        rows.forEach { it.refresh(fixed) }
    }

    private inner class VolumeRow(row: View, private val stream: Int, labelRes: Int) {
        private val label = context.getString(labelRes)
        private val decrease = row.findViewById<View>(R.id.volume_decrease)
        private val increase = row.findViewById<View>(R.id.volume_increase)
        private val value = row.findViewById<TextView>(R.id.volume_value)

        init {
            row.findViewById<TextView>(R.id.volume_label).text = label
            decrease.contentDescription = context.getString(R.string.decrease_volume, label)
            increase.contentDescription = context.getString(R.string.increase_volume, label)
            decrease.setOnClickListener { changeBy(-1) }
            increase.setOnClickListener { changeBy(1) }
        }

        fun refresh(fixed: Boolean) {
            val current = audio.getStreamVolume(stream)
            val maximum = audio.getStreamMaxVolume(stream)
            value.text = context.getString(R.string.volume_value, current, maximum)
            value.contentDescription = context.getString(R.string.volume_announcement, label, current, maximum)
            decrease.isEnabled = !fixed && current > minimum
            increase.isEnabled = !fixed && current < maximum
        }

        private val minimum: Int
            get() = when {
                Build.VERSION.SDK_INT >= 28 -> audio.getStreamMinVolume(stream)
                stream == AudioManager.STREAM_VOICE_CALL -> 1
                else -> 0
            }

        private fun changeBy(step: Int) {
            if (audio.isVolumeFixed) return
            val current = audio.getStreamVolume(stream)
            val maximum = audio.getStreamMaxVolume(stream)
            val next = (current + step).coerceIn(minimum.coerceAtMost(maximum), maximum)
            if (next == current) {
                this@VolumeControls.refresh()
                return
            }
            val changed = try {
                audio.setStreamVolume(stream, next, 0)
                audio.getStreamVolume(stream) != current
            } catch (_: SecurityException) {
                false
            }
            if (!changed) Toast.makeText(context, R.string.volume_unavailable, Toast.LENGTH_SHORT).show()
            this@VolumeControls.refresh()
        }
    }
}
