package dev.herdr.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")
private val URL_KEY = stringPreferencesKey("companion_url")

class Settings(private val context: Context) {
    val companionUrl: Flow<String?> = context.dataStore.data.map { it[URL_KEY] }
    suspend fun setCompanionUrl(url: String) { context.dataStore.edit { it[URL_KEY] = url } }
}
