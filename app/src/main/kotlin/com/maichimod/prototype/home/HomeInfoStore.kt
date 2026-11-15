package com.maichimod.prototype.home

import android.content.Context

/** Credential-protected home records are separate from the intercom's direct-boot configuration. */
internal class HomeInfoStore(context: Context, name: String = "home-information") {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    fun boundId(key: String) = runCatching { preferences.getInt("$key.bound", -1) }.getOrDefault(-1)
    fun pendingId(key: String) = runCatching { preferences.getInt("$key.pending", -1) }.getOrDefault(-1)
    fun saveIds(key: String, bound: Int, pending: Int) = runCatching { preferences.edit().putInt("$key.bound", bound).putInt("$key.pending", pending).commit() }.getOrDefault(false)
    fun text(key: String) = runCatching { preferences.getString(key, null) }.getOrNull()
    fun saveText(key: String, value: String?) = runCatching { preferences.edit().putString(key, value).commit() }.getOrDefault(false)
    fun number(key: String) = runCatching { preferences.getLong(key, 0) }.getOrDefault(0)
    fun saveNumber(key: String, value: Long) = runCatching { preferences.edit().putLong(key, value).commit() }.getOrDefault(false)
}
