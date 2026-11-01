package com.stealth.browser.network

import com.stealth.browser.data.db.ProxyConfigDao
import com.stealth.browser.data.db.ProxyConfigEntity
import com.stealth.browser.data.secure.SecureCredentialStore
import com.stealth.browser.engine.core.NetworkConfig
import com.stealth.browser.engine.core.ProxyConfig
import com.stealth.browser.engine.core.ProxyType
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class ProxyStore @Inject constructor(
    private val proxyDao: ProxyConfigDao,
    private val credentials: SecureCredentialStore,
) {
    fun observeAll(): Flow<List<ProxyConfigEntity>> = proxyDao.observeAll()

    suspend fun get(id: String): ProxyConfigEntity? = proxyDao.byId(id)

    suspend fun add(
        name: String,
        type: ProxyType,
        host: String,
        port: Int,
        username: String?,
        password: CharArray?,
    ): ProxyConfigEntity {
        val id = UUID.randomUUID().toString()
        val passwordRef = password?.takeIf { it.isNotEmpty() }?.let {
            val ref = "proxy_pw/$id"
            credentials.store(ref, it)
            it.fill('\u0000')
            ref
        }
        val entity = ProxyConfigEntity(
            id = id, name = name, type = type.name, host = host, port = port,
            username = username, passwordRef = passwordRef,
            createdAtMs = System.currentTimeMillis(),
        )
        proxyDao.upsert(entity)
        return entity
    }

    /** Updates all fields; pass null password to keep the stored one. */
    suspend fun update(id: String, name: String, host: String, port: Int, password: CharArray?) {
        proxyDao.byId(id)?.let { current ->
            var passwordRef = current.passwordRef
            if (password != null && password.isNotEmpty()) {
                passwordRef = current.passwordRef ?: "proxy_pw/$id"
                credentials.store(passwordRef, password)
                password.fill('\u0000')
            }
            proxyDao.upsert(current.copy(name = name, host = host, port = port, passwordRef = passwordRef))
        }
    }

    suspend fun delete(id: String) {
        proxyDao.byId(id)?.passwordRef?.let { credentials.delete(it) }
        proxyDao.delete(id)
    }

    /** Resolves a stored config to engine material. Password is decrypted in memory only. */
    suspend fun toProxyConfig(entity: ProxyConfigEntity): ProxyConfig? =
        runCatching {
            ProxyConfig(
                type = ProxyType.valueOf(entity.type),
                host = entity.host,
                port = entity.port,
                username = entity.username,
                password = entity.passwordRef?.let { credentials.read(it)?.toCharArray() },
            )
        }.getOrNull()
}
