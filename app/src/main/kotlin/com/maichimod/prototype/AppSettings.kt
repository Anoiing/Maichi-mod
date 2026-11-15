package com.maichimod.prototype

import android.content.Context
import android.os.UserManager
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import org.json.JSONObject

internal data class IdleReturnOptions(val enabled: Boolean = true, val minutes: Int = 3, val valid: Boolean = true)

/** Runtime identity has one device-protected record. Appearance remains in its own preferences. */
internal class AppSettings(
    private val context: Context,
    private val userUnlocked: () -> Boolean = { context.getSystemService(UserManager::class.java).isUserUnlocked },
) {
    private val runtime = AtomicFile(File(context.createDeviceProtectedStorageContext().filesDir, "intercom.json"))
    private fun notes() = AtomicFile(File(context.filesDir, "intercom-notes.json"))
    private fun idleFile() = AtomicFile(File(context.filesDir, "idle-return.json"))
    private fun standbyFile() = AtomicFile(File(context.filesDir, "standby.json"))

    fun standbyOptions(): StandbyOptions = synchronized(lock) {
        if (!userUnlocked()) return@synchronized StandbyOptions(valid = false)
        try {
            val record = read(standbyFile()) ?: return@synchronized StandbyOptions()
            check(record.get("version") == 1)
            StandbyOptions(
                enabled = record.get("enabled") as? Boolean ?: error("Invalid standby setting"),
                presenceEnabled = record.get("presenceEnabled") as? Boolean ?: error("Invalid presence setting"),
            )
        } catch (failure: Exception) {
            Log.w(TAG, "Unable to read standby settings", failure)
            StandbyOptions(valid = false)
        }
    }

    fun saveStandby(options: StandbyOptions): Boolean = synchronized(lock) {
        if (!userUnlocked()) return@synchronized false
        write(standbyFile(), JSONObject().put("version", 1).put("enabled", options.enabled)
            .put("presenceEnabled", options.presenceEnabled))
    }

    fun idleReturnOptions(): IdleReturnOptions = synchronized(lock) {
        if (!userUnlocked()) return@synchronized IdleReturnOptions(false, valid = false)
        try {
            val record = read(idleFile()) ?: return@synchronized IdleReturnOptions()
            check(record.get("version") == 1)
            val minutes = record.get("minutes") as? Int ?: error("Invalid idle duration")
            check(minutes in 1..30)
            val enabled = record.get("enabled") as? Boolean ?: error("Invalid idle setting")
            IdleReturnOptions(enabled, minutes)
        } catch (failure: Exception) {
            Log.w(TAG, "Unable to read idle return settings", failure)
            IdleReturnOptions(false, valid = false)
        }
    }

    fun isIdleReturnEnabled(): Boolean = idleReturnOptions().enabled
    fun idleReturnMinutes(): Int = idleReturnOptions().minutes

    fun saveIdleReturn(enabled: Boolean, minutes: Int): Boolean = synchronized(lock) {
        if (!userUnlocked() || minutes !in 1..30) return@synchronized false
        write(idleFile(), JSONObject().put("version", 1).put("enabled", enabled).put("minutes", minutes))
    }

    fun load(): ConnectionOptions = synchronized(lock) {
        val record = read(runtime)
        if (record != null) return@synchronized decode(record, if (userUnlocked()) read(notes()) else null)
        if (!userUnlocked()) return@synchronized ConnectionOptions(wired = true)
        val legacy = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        try {
            var options = ConnectionOptions(
                wired = legacy.getBoolean("wired", !BuildConfig.DEBUG),
                simulateTimeout = legacy.getBoolean("simulateTimeout", false),
                autoHangUpAfterUnlock = legacy.getBoolean("autoHangUpAfterUnlock", true),
            )
            for (field in ConnectionField.entries) {
                options = options.withField(field, legacy.getString(field.key, "").orEmpty())
                    ?: throw IllegalStateException("Invalid legacy field: ${field.key}")
            }
            check(save(options, initialEnabled = legacy.contains("wired"))) { "Unable to migrate intercom configuration" }
            options
        } catch (failure: ClassCastException) {
            throw IllegalStateException("Invalid legacy configuration", failure)
        }
    }

    fun save(options: ConnectionOptions): Boolean = save(options, initialEnabled = false)

    private fun save(options: ConnectionOptions, initialEnabled: Boolean): Boolean = synchronized(lock) {
        try {
            val previous = read(runtime)
            val record = encode(options, runtimeFields).put("enabled", previous?.getBoolean("enabled") ?: initialEnabled)
            val unlocked = userUnlocked()
            val previousNotes = if (unlocked) read(notes()) else null
            val newNotes = encode(options, noteFields)
            // Notes are non-operational. A failed identity write restores their previous draft as well.
            if (unlocked && !write(notes(), newNotes)) return@synchronized false
            if (!write(runtime, record)) {
                if (unlocked) {
                    if (previousNotes == null) notes().delete() else write(notes(), previousNotes)
                }
                return@synchronized false
            }
            true
        } catch (failure: Exception) {
            Log.w(TAG, "Unable to save intercom configuration", failure)
            false
        }
    }

    fun isReceptionEnabled(): Boolean = synchronized(lock) {
        val record = read(runtime) ?: return@synchronized false
        decode(record, null) // Never interpret a malformed canonical record as an enabled default.
        record.getBoolean("enabled")
    }

    fun setReceptionEnabled(enabled: Boolean): Boolean = synchronized(lock) {
        try {
            if (read(runtime) == null) load()
            val record = read(runtime) ?: return@synchronized false
            decode(record, null)
            write(runtime, record.put("enabled", enabled))
        } catch (failure: Exception) {
            Log.w(TAG, "Unable to save reception setting", failure)
            false
        }
    }

    fun resetConnectionOptions(): Boolean = synchronized(lock) {
        if (!userUnlocked()) return@synchronized false
        try {
            val empty = ConnectionOptions(wired = true)
            val notesFile = notes()
            val previousNotes = readBytes(notesFile)
            if (!write(notesFile, encode(empty, noteFields))) return@synchronized false
            if (write(runtime, encode(empty, runtimeFields).put("enabled", false))) true
            else {
                if (previousNotes == null) notesFile.delete() else write(notesFile, previousNotes)
                false
            }
        } catch (failure: Exception) {
            Log.w(TAG, "Unable to reset intercom configuration", failure)
            false
        }
    }

    private fun decode(record: JSONObject, notes: JSONObject?): ConnectionOptions {
        try {
            check(record.getInt("version") == 1) { "Unsupported configuration version" }
            record.getBoolean("enabled")
            var options = ConnectionOptions(record.getBoolean("wired"), record.getBoolean("simulateTimeout"),
                autoHangUpAfterUnlock = record.getBoolean("autoHangUpAfterUnlock"))
            for (field in runtimeFields + noteFields) {
                val value = if (field in runtimeFields) record.getString(field.key) else notes?.getString(field.key).orEmpty()
                options = options.withField(field, value) ?: error("Invalid field: ${field.key}")
            }
            return options
        } catch (failure: Exception) {
            throw IllegalStateException("Intercom configuration is unreadable", failure)
        }
    }

    private fun encode(options: ConnectionOptions, fields: List<ConnectionField>) = JSONObject().apply {
        put("version", 1)
        put("wired", options.wired)
        put("simulateTimeout", options.simulateTimeout)
        put("autoHangUpAfterUnlock", options.autoHangUpAfterUnlock)
        for (field in fields) put(field.key, options[field])
    }

    private fun read(file: AtomicFile): JSONObject? = try {
        readBytes(file)?.let { JSONObject(it.toString(Charsets.UTF_8)) }
    } catch (failure: Exception) { throw IllegalStateException("Unable to read configuration", failure) }

    private fun readBytes(file: AtomicFile): ByteArray? {
        try { return file.readFully() }
        catch (failure: FileNotFoundException) {
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
            throw IllegalStateException("Unable to read configuration", failure)
        }
    }

    private fun write(file: AtomicFile, record: JSONObject): Boolean = write(file, record.toString().toByteArray(Charsets.UTF_8))

    private fun write(file: AtomicFile, bytes: ByteArray): Boolean {
        var output: java.io.FileOutputStream? = null
        return try {
            output = file.startWrite()
            output.write(bytes)
            output.fd.sync()
            file.finishWrite(output)
            output = null
            // AtomicFile logs some commit failures instead of throwing. Verify publication before activation.
            file.readFully().contentEquals(bytes)
        } catch (failure: Exception) {
            file.failWrite(output)
            Log.w(TAG, "Unable to persist configuration", failure)
            false
        }
    }

    companion object {
        private const val TAG = "Maichi-settings"
        private val lock = Any()
        private val runtimeFields = ConnectionField.entries.filter { it.affectsSession }
        private val noteFields = ConnectionField.entries.filterNot { it.affectsSession }
    }
}
