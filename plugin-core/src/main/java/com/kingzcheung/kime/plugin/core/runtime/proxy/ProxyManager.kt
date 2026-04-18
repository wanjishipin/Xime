package com.kingzcheung.kime.plugin.core.runtime.proxy

import android.app.Application
import android.content.ContentProvider
import android.net.Uri
import android.util.Log
import com.kingzcheung.kime.plugin.core.model.ProviderInfo
import com.kingzcheung.kime.plugin.core.runtime.PluginManager
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class ProxyManager(private val context: Application) {

    companion object {
        private const val TAG = "ProxyManager"
    }

    private val providerRegistry = ConcurrentHashMap<String, Pair<String, ProviderInfo>>()
    private val authorityToProviderMap = ConcurrentHashMap<String, String>()
    private var hostProviderAuthority: String? = null
    private val providerInstanceCache = ConcurrentHashMap<String, ContentProvider>()

    fun setHostProviderAuthority(authority: String) {
        this.hostProviderAuthority = authority
        Log.i(TAG, "ContentProvider proxy authority configured: $authority")
    }

    fun getHostProviderAuthority(): String? {
        if (hostProviderAuthority == null) {
            Log.e(TAG, "HostProvider authority not configured!")
        }
        return hostProviderAuthority
    }

    fun registerProviders(pluginId: String, providers: List<ProviderInfo>) {
        if (providers.isEmpty()) return

        providers.forEach { provider ->
            if (provider.enabled) {
                providerRegistry[provider.className] = Pair(pluginId, provider)
                provider.authorities.forEach { authority ->
                    authorityToProviderMap[authority] = provider.className
                    Log.d(TAG, "Registered Provider Authority: [$authority] -> ${provider.className}")
                }
            } else {
                Log.d(TAG, "Skipped disabled Provider: ${provider.className}")
            }
        }
    }

    fun unregisterProviders(pluginId: String) {
        val providersToRemove = providerRegistry.filter { it.value.first == pluginId }

        if (providersToRemove.isEmpty()) return

        providersToRemove.forEach { (className, pair) ->
            val info = pair.second
            providerRegistry.remove(className)
            info.authorities.forEach { authority ->
                authorityToProviderMap.remove(authority)
                Log.d(TAG, "Unregistered Provider Authority: [$authority]")
            }
            providerInstanceCache.remove(className)
            Log.d(TAG, "Removed Provider instance from cache: $className")
        }
        Log.i(TAG, "Completed unregistration of all Providers for plugin [$pluginId]")
    }

    fun findProviderInfoByAuthority(authority: String): ProviderInfo? {
        val className = authorityToProviderMap[authority] ?: return null
        return providerRegistry[className]?.second
    }

    fun getOrInstantiateProvider(className: String): ContentProvider? {
        providerInstanceCache[className]?.let { return it }

        return try {
            val instance = PluginManager.getInterface(ContentProvider::class.java, className)
            if (instance != null) {
                instance.attachInfo(context, null)
                providerInstanceCache[className] = instance
                Log.d(TAG, "Created and cached plugin Provider instance: $className")
            } else {
                Log.e(TAG, "Failed to create plugin Provider instance: $className")
            }
            instance
        } catch (e: Exception) {
            Log.e(TAG, "Error creating plugin Provider instance: $className", e)
            null
        }
    }

    fun buildProxyUri(pluginUri: Uri): Uri {
        val hostAuthority = getHostProviderAuthority()
            ?: throw IllegalStateException("HostProvider authority not configured")

        val pluginAuthority = pluginUri.authority
            ?: throw IllegalArgumentException("Input URI has no Authority: $pluginUri")

        findProviderInfoByAuthority(pluginAuthority)
            ?: throw IllegalArgumentException("Authority [$pluginAuthority] not registered as plugin Provider")

        val encodedPluginAuthority = URLEncoder.encode(pluginAuthority, "UTF-8")

        val path = pluginUri.path
        val proxyPath = if (path.isNullOrEmpty() || path == "/") {
            encodedPluginAuthority
        } else {
            "$encodedPluginAuthority$path"
        }

        return pluginUri.buildUpon()
            .authority(hostAuthority)
            .encodedPath(proxyPath)
            .build()
    }

    fun rewriteUri(proxyUri: Uri, providerInfo: ProviderInfo): Uri {
        val pathSegments = proxyUri.pathSegments
        val originalAuthority = providerInfo.authorities.first()

        val originalPath = pathSegments.drop(1).joinToString("/")

        return proxyUri.buildUpon()
            .authority(originalAuthority)
            .path(originalPath)
            .clearQuery()
            .fragment(null)
            .build()
    }
}