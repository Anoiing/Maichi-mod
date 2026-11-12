package com.maichimod.prototype.protocol

internal class JpegAssembler {
    private class PendingFrame(val startedAt: Long, total: Int) {
        val parts = arrayOfNulls<ByteArray>(total)
        var received = 0
        var byteCount = 0
    }

    private val pending = LinkedHashMap<Int, PendingFrame>()
    private var lastCompleted: Int? = null

    fun <Frame : Any> accept(fragment: MediaFragment, nowMs: Long, decode: (ByteArray) -> Frame?): Frame? {
        if (fragment.kind != MediaFragment.JPEG || fragment.total !in 1..MAX_FRAGMENTS ||
            fragment.index !in 1..fragment.total || fragment.bytes.isEmpty()
        ) return null

        val frameNumber = fragment.frameNumber
        if (lastCompleted?.let { !isNewer(frameNumber, it) } == true) return null
        pending.entries.removeAll { nowMs - it.value.startedAt >= MAX_FRAME_AGE_MS }

        val frame = pending.getOrPut(frameNumber) {
            if (pending.size == MAX_PENDING_FRAMES) pending.remove(pending.keys.first())
            PendingFrame(nowMs, fragment.total)
        }
        if (frame.parts.size != fragment.total) return null
        val index = fragment.index - 1
        if (frame.parts[index] != null) return null
        if (fragment.bytes.size > MAX_FRAME_BYTES - frame.byteCount) {
            pending.remove(frameNumber)
            return null
        }

        frame.parts[index] = fragment.bytes
        frame.received++
        frame.byteCount += fragment.bytes.size
        if (frame.received != frame.parts.size) return null

        pending.remove(frameNumber)
        val jpeg = ByteArray(frame.byteCount)
        var offset = 0
        for (part in frame.parts) {
            val bytes = checkNotNull(part)
            bytes.copyInto(jpeg, offset)
            offset += bytes.size
        }
        if (jpeg.size < 4 || jpeg[0] != 0xff.toByte() || jpeg[1] != 0xd8.toByte() ||
            jpeg[jpeg.lastIndex - 1] != 0xff.toByte() || jpeg.last() != 0xd9.toByte()
        ) return null
        val decoded = decode(jpeg) ?: return null
        lastCompleted = frameNumber
        pending.entries.removeAll { !isNewer(it.key, frameNumber) }
        return decoded
    }

    fun clear() {
        pending.clear()
        lastCompleted = null
    }

    private fun isNewer(candidate: Int, previous: Int): Boolean =
        ((candidate - previous) and 0xffff) in 1..0x7fff

    companion object {
        private const val MAX_FRAGMENTS = 256
        private const val MAX_PENDING_FRAMES = 8
        private const val MAX_FRAME_AGE_MS = 500L
        private const val MAX_FRAME_BYTES = 1024 * 1024
    }
}
