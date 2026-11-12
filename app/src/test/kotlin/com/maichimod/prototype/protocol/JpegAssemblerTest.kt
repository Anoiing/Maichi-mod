package com.maichimod.prototype.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JpegAssemblerTest {
    @Test
    fun joinsFragmentsByIndexAndIgnoresDuplicates() {
        val assembler = JpegAssembler()
        val first = fragment(42, 1, "ff d8 01")
        val second = fragment(42, 2, "02 ff d9")

        assertNull(assembler.acceptBytes(second, 0))
        assertNull(assembler.acceptBytes(second, 1))
        assertArrayEquals(hex("ff d8 01 02 ff d9"), assembler.acceptBytes(first, 2))
        assertNull(assembler.acceptBytes(second, 3))
    }

    @Test
    fun anExpiredFrameCannotCompleteAfterItsMissingPieceArrives() {
        val assembler = JpegAssembler()

        assertNull(assembler.acceptBytes(fragment(42, 1, "ff d8 01"), 0))
        assertNull(assembler.acceptBytes(fragment(42, 2, "02 ff d9"), 1_000))
        assertNull(assembler.acceptBytes(fragment(43, 1, "ff d8 03"), 1_002))
        assertArrayEquals(hex("ff d8 03 04 ff d9"), assembler.acceptBytes(fragment(43, 2, "04 ff d9"), 1_003))
    }

    @Test
    fun dropsCorruptJpegAndAcceptsSequenceNumberWrap() {
        val assembler = JpegAssembler()

        assertNull(assembler.acceptBytes(fragment(65534, 1, "01 02"), 0))
        assertNull(assembler.acceptBytes(fragment(65534, 2, "03 04"), 1))
        assertNull(assembler.acceptBytes(fragment(65535, 1, "ff d8 05"), 2))
        assertArrayEquals(hex("ff d8 05 06 ff d9"), assembler.acceptBytes(fragment(65535, 2, "06 ff d9"), 3))
        assertNull(assembler.acceptBytes(fragment(0, 1, "ff d8 07"), 4))
        assertArrayEquals(hex("ff d8 07 08 ff d9"), assembler.acceptBytes(fragment(0, 2, "08 ff d9"), 5))
    }

    @Test
    fun anUndecodableNewerFrameDoesNotDiscardAPendingFrame() {
        val assembler = JpegAssembler()
        assertNull(assembler.acceptBytes(fragment(42, 1, "ff d8 01"), 0))
        val malformed = MediaFragment(MediaFragment.JPEG, 43, 1, 1, hex("ff d8 ff d9"))
        assertNull(assembler.accept<ByteArray>(malformed, 1) { null })
        assertArrayEquals(hex("ff d8 01 02 ff d9"), assembler.acceptBytes(fragment(42, 2, "02 ff d9"), 2))
    }

    @Test
    fun aRejectedFrameCanBeDecodedAgainWithTheSameSequenceNumber() {
        val assembler = JpegAssembler()
        val frame = MediaFragment(MediaFragment.JPEG, 42, 1, 1, hex("ff d8 01 02 ff d9"))
        assertNull(assembler.accept<ByteArray>(frame, 0) { null })
        assertArrayEquals(frame.bytes, assembler.acceptBytes(frame, 1))
    }

    private fun JpegAssembler.acceptBytes(fragment: MediaFragment, nowMs: Long) = accept(fragment, nowMs) { it }

    private fun fragment(frame: Int, index: Int, bytes: String) = MediaFragment(1, frame, 2, index, hex(bytes))
}
