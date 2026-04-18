package com.kingzcheung.kime.plugin.core.component

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Process
import com.kingzcheung.kime.plugin.core.model.ProviderInfo
import com.kingzcheung.kime.plugin.core.runtime.PluginManager
import java.net.URLDecoder

open class BaseHostProvider : ContentProvider() {

    companion object {
        const val KEY_TARGET_URI = "com.kingzcheung.kime.plugin.core.BaseHostProvider.TARGET_URI"
    }

    override fun onCreate(): Boolean {
        return true
    }

    private fun getTargetProvider(className: String): ContentProvider? {
        return PluginManager.proxyManager.getOrInstantiateProvider(className)
    }

    private fun rewriteUri(proxyUri: Uri, providerInfo: ProviderInfo): Uri {
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

    private inline fun <T> withForwardedRequest(
        uri: Uri,
        block: (provider: ContentProvider, rewrittenUri: Uri) -> T?
    ): T? {
        val pluginAuthority = uri.pathSegments.getOrNull(0)?.let { URLDecoder.decode(it, "UTF-8") }
            ?: throw IllegalArgumentException("Cannot parse plugin Authority from URI: $uri")

        val providerInfo = PluginManager.proxyManager.findProviderInfoByAuthority(pluginAuthority)
            ?: throw SecurityException("Blocked: Target Provider Authority [$pluginAuthority] not registered in PluginManager")

        val className = providerInfo.className

        if (!providerInfo.exported && Binder.getCallingUid() != Process.myUid()) {
            throw SecurityException("Permission denied: Provider ${providerInfo.className} is not exported")
        }

        val targetProvider = getTargetProvider(className)
            ?: throw IllegalStateException("Cannot create or get Provider instance: $className")

        val rewrittenUri = rewriteUri(uri, providerInfo)

        return block(targetProvider, rewrittenUri)
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? = withForwardedRequest(uri) { provider, rewritten ->
        provider.query(rewritten, projection, selection, selectionArgs, sortOrder)
    }

    override fun getType(uri: Uri): String? = withForwardedRequest(uri) { provider, rewritten ->
        provider.getType(rewritten)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        var result: Uri? = null
        withForwardedRequest(uri) { provider, rewritten ->
            provider.insert(rewritten, values)?.also { originalResultUri ->
                context?.contentResolver?.notifyChange(uri, null)
                val pluginAuthority = originalResultUri.authority
                val hostAuthority = PluginManager.proxyManager.getHostProviderAuthority()

                result = originalResultUri.buildUpon()
                    .authority(hostAuthority)
                    .path("/$pluginAuthority${originalResultUri.path}")
                    .build()
            }
        }
        return result
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int =
        withForwardedRequest(uri) { provider, rewritten ->
            provider.delete(rewritten, selection, selectionArgs).also { deletedCount ->
                if (deletedCount > 0) {
                    context?.contentResolver?.notifyChange(uri, null)
                }
            }
        } ?: 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int =
        withForwardedRequest(uri) { provider, rewritten ->
            provider.update(rewritten, values, selection, selectionArgs).also { updatedCount ->
                if (updatedCount > 0) {
                    context?.contentResolver?.notifyChange(uri, null)
                }
            }
        } ?: 0

    @Suppress("DEPRECATION")
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val targetUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras?.getParcelable(KEY_TARGET_URI, Uri::class.java)
        } else {
            extras?.getParcelable(KEY_TARGET_URI)
        }
            ?: throw IllegalArgumentException("Cannot handle call request: missing target Uri in extras (KEY_TARGET_URI)")

        extras?.remove(KEY_TARGET_URI)

        return withForwardedRequest(targetUri) { provider, _ ->
            provider.call(method, arg, extras)
        }
    }
}