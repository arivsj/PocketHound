package com.pockethound.app.core.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pockethound.app.core.model.SessionSnapshot
import com.pockethound.app.core.transport.TransportMode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "pockethound_settings")

/** Tudo o que não é segredo mora aqui: o token fica no SecureStore. */
@Singleton
class SettingsStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val deviceId = stringPreferencesKey("device_id")
        val deviceName = stringPreferencesKey("device_name")
        val pcName = stringPreferencesKey("pc_name")
        val directBaseUrl = stringPreferencesKey("direct_base_url")
        val p2pTicket = stringPreferencesKey("p2p_ticket")
        val transportMode = stringPreferencesKey("transport_mode")
        val lastSeq = longPreferencesKey("last_seq")
        val approvalTimeout = intPreferencesKey("approval_timeout_seconds")
        val perguntasResolvidas = stringPreferencesKey("perguntas_resolvidas")
    }

    val session: Flow<SessionSnapshot> = context.settingsDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            SessionSnapshot(
                deviceId = preferences[Keys.deviceId],
                deviceName = preferences[Keys.deviceName] ?: SessionSnapshot.DEFAULT_DEVICE_NAME,
                pcName = preferences[Keys.pcName],
                directBaseUrl = preferences[Keys.directBaseUrl].orEmpty(),
                p2pTicket = preferences[Keys.p2pTicket].orEmpty(),
                transportMode = TransportMode.fromWire(preferences[Keys.transportMode]),
                lastSeq = preferences[Keys.lastSeq] ?: 0L,
                pendingApprovalTimeoutSeconds = preferences[Keys.approvalTimeout]
                    ?: SessionSnapshot.DEFAULT_APPROVAL_TIMEOUT_SECONDS,
            )
        }

    suspend fun read(): SessionSnapshot = session.first()

    suspend fun updatePairing(
        deviceId: String,
        pcName: String,
        directBaseUrl: String,
        p2pTicket: String,
    ) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.deviceId] = deviceId
            preferences[Keys.pcName] = pcName
            preferences[Keys.directBaseUrl] = directBaseUrl.trim()
            preferences[Keys.p2pTicket] = p2pTicket.trim()
        }
    }

    suspend fun updateDeviceName(value: String) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.deviceName] = value.trim()
        }
    }

    suspend fun updateDirectBaseUrl(value: String) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.directBaseUrl] = value.trim()
        }
    }

    suspend fun updateTransportMode(mode: TransportMode) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.transportMode] = mode.name
        }
    }

    /** Cursor de replay: último `seq` processado. */
    suspend fun updateLastSeq(value: Long) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.lastSeq] = value
        }
    }

    suspend fun updateApprovalTimeout(seconds: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.approvalTimeout] = seconds.coerceIn(15, 600)
        }
    }

    /**
     * Perguntas já respondidas (ids) — a memória contra o replay ressuscitar
     * uma pergunta. Separado do SessionSnapshot de propósito: isto é histórico,
     * não pareamento, e não pode mudar o significado do fluxo de pareamento.
     */
    suspend fun readPerguntasResolvidas(): List<String> =
        context.settingsDataStore.data.first()[Keys.perguntasResolvidas]
            ?.split(',')
            ?.filter { it.isNotBlank() }
            .orEmpty()

    /** Grava a lista inteira; o teto de itens é garantido por quem escreve. */
    suspend fun updatePerguntasResolvidas(ids: List<String>) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.perguntasResolvidas] = ids.joinToString(",")
        }
    }

    /** Remove só o vínculo com o PC, mantendo as preferências de interface. */
    suspend fun clearPairing() {
        context.settingsDataStore.edit { preferences ->
            preferences.remove(Keys.deviceId)
            preferences.remove(Keys.pcName)
            preferences.remove(Keys.directBaseUrl)
            preferences.remove(Keys.p2pTicket)
            preferences.remove(Keys.lastSeq)
        }
    }

    suspend fun clear() {
        context.settingsDataStore.edit { preferences -> preferences.clear() }
    }
}
