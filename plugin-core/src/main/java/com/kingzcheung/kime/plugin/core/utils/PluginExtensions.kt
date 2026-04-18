package com.kingzcheung.kime.plugin.core.utils

import android.content.ContentResolver
import android.content.ContentValues
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.kingzcheung.kime.plugin.core.component.BaseHostProvider
import com.kingzcheung.kime.plugin.core.runtime.PluginManager

fun buildProxyUri(pluginUri: Uri): Uri {
    return PluginManager.proxyManager.buildProxyUri(pluginUri)
}

fun ContentResolver.queryPlugin(
    uri: Uri,
    projection: Array<String>?,
    selection: String?,
    selectionArgs: Array<String>?,
    sortOrder: String?
): Cursor? {
    val proxyUri = buildProxyUri(uri)
    return this.query(proxyUri, projection, selection, selectionArgs, sortOrder)
}

fun ContentResolver.insertPlugin(uri: Uri, values: ContentValues?): Uri? {
    val proxyUri = buildProxyUri(uri)
    return this.insert(proxyUri, values)
}

fun ContentResolver.deletePlugin(
    uri: Uri,
    selection: String?,
    selectionArgs: Array<String>?
): Int {
    val proxyUri = buildProxyUri(uri)
    return this.delete(proxyUri, selection, selectionArgs)
}

fun ContentResolver.updatePlugin(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<String>?
): Int {
    val proxyUri = buildProxyUri(uri)
    return this.update(proxyUri, values, selection, selectionArgs)
}

fun ContentResolver.callPlugin(
    uri: Uri,
    method: String,
    arg: String? = null,
    extras: Bundle? = null
): Bundle? {
    val proxyUri = buildProxyUri(uri)

    val finalExtras = (extras ?: Bundle()).apply {
        putParcelable(BaseHostProvider.KEY_TARGET_URI, uri)
    }

    return this.call(proxyUri, method, arg, finalExtras)
}

fun ContentResolver.registerPluginObserver(
    uri: Uri,
    notifyForDescendants: Boolean,
    observer: ContentObserver
) {
    val proxyUri = buildProxyUri(uri)
    this.registerContentObserver(proxyUri, notifyForDescendants, observer)
}

fun ContentResolver.unregisterPluginObserver(observer: ContentObserver) {
    this.unregisterContentObserver(observer)
}