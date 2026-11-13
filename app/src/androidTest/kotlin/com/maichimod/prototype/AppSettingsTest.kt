package com.maichimod.prototype

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSettingsTest {
    @Test fun freshInstallWaitsForExplicitReceptionChoice() {
        val store = AppSettings(context()) { true }
        store.load()
        assertFalse(store.isReceptionEnabled())
        assertTrue(store.setReceptionEnabled(true))
        assertTrue(store.isReceptionEnabled())
    }
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val name = "settings-test-${UUID.randomUUID()}"
    private val root = File(base.cacheDir, name).apply { mkdirs() }
    private var credentialReads = 0
    private fun context(device: Boolean = false): Context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun createDeviceProtectedStorageContext(): Context = context(true)
        override fun getFilesDir(): File {
            if (!device) credentialReads++
            return File(root, if (device) "device" else "credential").apply { mkdirs() }
        }
        override fun getSharedPreferences(key: String, mode: Int): android.content.SharedPreferences {
            if (!device) credentialReads++
            return base.getSharedPreferences("$name-$key", mode)
        }
    }

    @After fun cleanUp() {
        root.deleteRecursively()
        base.deleteSharedPreferences("$name-MainActivity")
        base.deleteSharedPreferences("$name-appearance")
    }

    @Test fun settingsMigrationPreservesAllOptionsAndLeavesAppearanceAlone() {
        val ctx = context()
        ctx.getSharedPreferences("MainActivity", 0).edit().putBoolean("wired", true)
            .putBoolean("simulateTimeout", true).putBoolean("autoHangUpAfterUnlock", false)
            .putString("indoorId", "S00000000001").putString("peerId", "M00000000002")
            .putString("centerIp", "192.0.2.30").commit()
        ctx.getSharedPreferences("appearance", 0).edit().putString("theme", "charcoal").commit()
        val store = AppSettings(ctx) { true }
        val options = store.load()
        assertTrue(store.isReceptionEnabled())
        assertTrue(options.wired)
        assertTrue(options.simulateTimeout)
        assertFalse(options.autoHangUpAfterUnlock)
        assertEquals("S00000000001", options[ConnectionField.INDOOR_ID])
        assertEquals("192.0.2.30", options[ConnectionField.CENTER_IP])
        ctx.getSharedPreferences("MainActivity", 0).edit().putString("indoorId", "S00000000099").commit()
        assertEquals(options, AppSettings(ctx) { true }.load())
        assertEquals("charcoal", ctx.getSharedPreferences("appearance", 0).getString("theme", null))
    }

    @Test fun lockedReadDoesNotAccessCredentialStorage() {
        val ctx = context()
        val original = ConnectionOptions(wired = true).withField(ConnectionField.INDOOR_ID, "00000000001")!!
        assertTrue(AppSettings(ctx) { true }.save(original))
        credentialReads = 0
        val locked = AppSettings(ctx) { false }.load()
        assertEquals(original[ConnectionField.INDOOR_ID], locked[ConnectionField.INDOOR_ID])
        assertEquals(0, credentialReads)
    }

    @Test fun corruptCanonicalRecordNeverFallsBackToLegacyIdentity() {
        val ctx = context()
        assertTrue(AppSettings(ctx) { true }.save(ConnectionOptions(wired = true)))
        File(ctx.createDeviceProtectedStorageContext().filesDir, "intercom.json").writeText("broken")
        try { AppSettings(ctx) { true }.load(); fail("corrupt record must be reported") }
        catch (_: IllegalStateException) { }
    }

    @Test fun corruptLegacyBooleanIsReportedAsConfigurationFailure() {
        val ctx = context()
        ctx.getSharedPreferences("MainActivity", 0).edit().putString("wired", "broken").commit()
        assertThrows(IllegalStateException::class.java) { AppSettings(ctx) { true }.load() }
    }

    @Test fun explicitConfigurationResetRepairsCorruptionWithReceptionDisabled() {
        val ctx = context()
        val store = AppSettings(ctx) { true }
        assertTrue(store.save(ConnectionOptions(wired = true)))
        ctx.getSharedPreferences("appearance", 0).edit().putString("theme", "rose").commit()
        File(ctx.createDeviceProtectedStorageContext().filesDir, "intercom.json").writeText("broken")
        assertTrue(store.resetConnectionOptions())
        assertFalse(store.isReceptionEnabled())
        assertTrue(store.load().wired)
        assertFalse(store.load().hasTerminalIds)
        assertEquals("rose", ctx.getSharedPreferences("appearance", 0).getString("theme", null))
    }

    @Test fun failedResetPreservesBothRecords() {
        val ctx = context()
        val store = AppSettings(ctx) { true }
        assertTrue(store.save(ConnectionOptions(wired = true).withField(ConnectionField.CENTER_IP, "192.0.2.30")!!))
        val runtime = File(ctx.createDeviceProtectedStorageContext().filesDir, "intercom.json")
        runtime.writeText("broken")
        val notes = File(ctx.filesDir, "intercom-notes.json").readBytes()
        val blocked = File(runtime.path + ".new").apply { mkdirs() }
        File(blocked, "blocker").writeText("x")
        assertFalse(store.resetConnectionOptions())
        assertEquals("broken", runtime.readText())
        assertArrayEquals(notes, File(ctx.filesDir, "intercom-notes.json").readBytes())
    }

    @Test fun failedSavePreservesActiveIdentity() {
        val ctx = context()
        val store = AppSettings(ctx) { true }
        val original = ConnectionOptions(wired = true).withField(ConnectionField.INDOOR_ID, "00000000001")!!
        assertTrue(store.save(original))
        val temporary = File(ctx.createDeviceProtectedStorageContext().filesDir, "intercom.json.new")
        temporary.mkdirs()
        File(temporary, "blocker").writeText("x")
        assertFalse(store.save(original.withField(ConnectionField.INDOOR_ID, "00000000002")!!))
        assertEquals(original, store.load())
    }
}
