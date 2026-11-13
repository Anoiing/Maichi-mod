package com.maichimod.prototype

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CallAudioTest {
    @Test fun losingFocusDuringPlaybackCreationNeverStartsUnfocusedAudio() {
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE == "ranchu")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val creating = CountDownLatch(1)
        val release = CountDownLatch(1)
        val focusLost = CountDownLatch(1)
        val settled = CountDownLatch(2)
        var played = false
        val audio = CallAudio(context, createTrack = { format, attributes, size ->
            creating.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            android.media.AudioTrack.Builder().setAudioFormat(format).setAudioAttributes(attributes)
                .setBufferSizeInBytes(size).setTransferMode(android.media.AudioTrack.MODE_STREAM).build()
        }) { direction, health ->
            if (direction == AudioDirection.PLAYBACK && health == AudioHealth.UNAVAILABLE) {
                focusLost.countDown(); settled.countDown()
            }
            if (direction == AudioDirection.PLAYBACK && health == AudioHealth.AVAILABLE) played = true
        }
        val manager = context.getSystemService(android.media.AudioManager::class.java)
        val other = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener { }.build()
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { audio.start(false) {} }
            assertTrue(creating.await(2, TimeUnit.SECONDS))
            assertEquals(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(other))
            assertTrue(focusLost.await(2, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(settled.await(2, TimeUnit.SECONDS))
            assertFalse(played)
        } finally {
            release.countDown(); manager.abandonAudioFocusRequest(other)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { audio.stop() }
            scenario.close()
        }
    }

    @Test fun permanentAudioFocusLossIsReportedWithoutEndingTheCall() {
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE == "ranchu")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val ready = CountDownLatch(1)
        val lost = CountDownLatch(1)
        val audio = CallAudio(context) { direction, health ->
            if (direction == AudioDirection.PLAYBACK && health == AudioHealth.AVAILABLE) ready.countDown()
            if (direction == AudioDirection.PLAYBACK && health == AudioHealth.UNAVAILABLE) lost.countDown()
        }
        val manager = context.getSystemService(android.media.AudioManager::class.java)
        val other = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener { }.build()
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { audio.start(false) {} }
            assertTrue(ready.await(3, TimeUnit.SECONDS))
            assertEquals(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(other))
            assertTrue("permanent focus loss was ignored", lost.await(3, TimeUnit.SECONDS))
        } finally {
            manager.abandonAudioFocusRequest(other)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { audio.stop() }
            scenario.close()
        }
    }

    @Test fun microphoneFailureKeepsPlaybackAlive() {
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE == "ranchu")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun checkSelfPermission(permission: String) =
                if (permission == Manifest.permission.RECORD_AUDIO) PackageManager.PERMISSION_DENIED
                else super.checkSelfPermission(permission)
        }
        val microphoneFailed = CountDownLatch(1)
        val playbackReady = CountDownLatch(1)
        var playbackFailed = false
        val audio = CallAudio(context) { direction, health ->
            if (direction == AudioDirection.CAPTURE && health == AudioHealth.UNAVAILABLE) microphoneFailed.countDown()
            if (direction == AudioDirection.PLAYBACK && health == AudioHealth.AVAILABLE) playbackReady.countDown()
            if (direction == AudioDirection.PLAYBACK && health == AudioHealth.UNAVAILABLE) playbackFailed = true
        }
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { audio.start(true) { fail("denied microphone sent audio") } }
            assertTrue(playbackReady.await(3, TimeUnit.SECONDS))
            assertTrue(microphoneFailed.await(3, TimeUnit.SECONDS))
            assertFalse(playbackFailed)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { audio.stop() }
            scenario.close()
        }
    }
}
