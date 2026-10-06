package com.ccatq.xdylupdater2.core

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.*
private val Context.preferences by preferencesDataStore("settings")
data class Settings(val cellular: Boolean = true, val updates: Boolean = true, val developer: Boolean = false, val continuous: Boolean = false)
class Preferences(private val context: Context) {
    val settings = context.preferences.data.map { Settings(it[booleanPreferencesKey("cellular")] ?: true, it[booleanPreferencesKey("updates")] ?: true, it[booleanPreferencesKey("developer")] ?: false, it[booleanPreferencesKey("continuous")] ?: false) }
    suspend fun set(key: String, value: Boolean) { context.preferences.edit { it[booleanPreferencesKey(key)] = value } }
}
