package com.maichimod.prototype

import android.content.Context
import android.content.ContextWrapper
import android.net.Network
import androidx.test.core.app.ApplicationProvider
import com.maichimod.prototype.protocol.DEMO_PROFILE
import com.maichimod.prototype.transport.WiredLink
import java.io.File
import java.util.UUID

internal class IsolatedContext(private val base: Context = ApplicationProvider.getApplicationContext(),
    private val key: String = "test-${UUID.randomUUID()}", private val device: Boolean = false) : ContextWrapper(base) {
    private val root get() = File(base.cacheDir, key)
    override fun getApplicationContext(): Context = this
    override fun createDeviceProtectedStorageContext(): Context = IsolatedContext(base, key, true)
    override fun getFilesDir(): File = File(root, if (device) "device" else "credential").apply { mkdirs() }
    override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$key-$name", mode)
    fun clear() { root.deleteRecursively(); base.deleteSharedPreferences("$key-MainActivity") }
}

internal fun testLink() = WiredLink(Network.fromNetworkHandle(0x7cafed00dL), DEMO_PROFILE.localAddress, 24, null)
internal fun wiredTestOptions() = ConnectionOptions(wired = true)
    .withField(ConnectionField.INDOOR_ID, DEMO_PROFILE.indoorId)!!
    .withField(ConnectionField.PEER_ID, DEMO_PROFILE.peerId)!!
