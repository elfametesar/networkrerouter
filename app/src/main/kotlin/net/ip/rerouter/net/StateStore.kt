package net.ip.rerouter.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import net.ip.rerouter.model.AppState
import java.io.File

/**
 * Persists which interfaces and rules the app created, so "reset all" and
 * the UI can recover state after a process restart without re-deriving it
 * from live kernel state (which is possible but slower and lossier for
 * things like exclusion lists).
 */
class StateStore(context: Context) {

    private val file = File(context.filesDir, "app_state.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    suspend fun load(): AppState = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext AppState()
        try {
            json.decodeFromString(AppState.serializer(), file.readText())
        } catch (e: Exception) {
            AppState()
        }
    }

    suspend fun save(state: AppState) = withContext(Dispatchers.IO) {
        file.writeText(json.encodeToString(AppState.serializer(), state))
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        if (file.exists()) file.delete()
    }
}
