package com.kingzcheung.xime.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/** Exposes filesDir as a read/write root to Android's Storage Access Framework. */
class PrivateFilesProvider : DocumentsProvider() {
    private lateinit var root: File

    override fun onCreate(): Boolean {
        root = requireNotNull(context).filesDir.canonicalFile
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns: List<String> = projection?.toList() ?: DEFAULT_ROOT_COLUMNS.toList()
        return MatrixCursor(columns.toTypedArray()).apply {
            val row = newRow()
            columns.forEach { column ->
                when (column) {
                    DocumentsContract.Root.COLUMN_ROOT_ID -> row.add(column, ROOT_ID)
                    DocumentsContract.Root.COLUMN_DOCUMENT_ID -> row.add(column, ROOT_ID)
                    DocumentsContract.Root.COLUMN_TITLE -> row.add(column, "Xime 私有数据")
                    DocumentsContract.Root.COLUMN_SUMMARY -> row.add(column, "Xime 应用私有目录")
                    DocumentsContract.Root.COLUMN_ICON -> row.add(
                        column,
                        requireNotNull(context).applicationInfo.icon
                    )
                    DocumentsContract.Root.COLUMN_AVAILABLE_BYTES -> row.add(column, root.freeSpace)
                    DocumentsContract.Root.COLUMN_FLAGS -> row.add(
                        column,
                        DocumentsContract.Root.FLAG_SUPPORTS_CREATE or
                            DocumentsContract.Root.FLAG_SUPPORTS_RECENTS or
                            DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD or
                            DocumentsContract.Root.FLAG_LOCAL_ONLY
                    )
                    DocumentsContract.Root.COLUMN_MIME_TYPES -> row.add(column, "*/*\n" + Document.MIME_TYPE_DIR)
                    else -> row.add(column, null)
                }
            }
        }
    }

    override fun queryDocument(
        documentId: String,
        projection: Array<out String>?
    ): Cursor = MatrixCursor(projection?.toList()?.toTypedArray() ?: DEFAULT_DOCUMENT_COLUMNS).apply {
        includeDocument(this, documentId)
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val directory = resolve(parentDocumentId)
        if (!directory.isDirectory) throw FileNotFoundException(parentDocumentId)
        val cursor = MatrixCursor(projection?.toList()?.toTypedArray() ?: DEFAULT_DOCUMENT_COLUMNS)
        directory.listFiles()?.sortedBy { it.name }?.forEach { includeDocument(cursor, idFor(it)) }
        return cursor
    }

    override fun queryRecentDocuments(
        rootId: String,
        projection: Array<out String>?
    ): Cursor {
        val cursor = MatrixCursor(projection?.toList()?.toTypedArray() ?: DEFAULT_DOCUMENT_COLUMNS)
        resolve(rootId).walkTopDown()
            .filter { it.isFile }
            .sortedByDescending { it.lastModified() }
            .take(20)
            .forEach { includeDocument(cursor, idFor(it)) }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = resolve(documentId)
        if (file.isDirectory) throw FileNotFoundException("Directories cannot be opened: $documentId")
        val flags = when (mode) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "w", "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            "wa" -> ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_APPEND
            "rw" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
            "rwt" -> ParcelFileDescriptor.MODE_READ_WRITE or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            else -> throw IllegalArgumentException("Unsupported mode: $mode")
        }
        file.parentFile?.mkdirs()
        return ParcelFileDescriptor.open(file, flags)
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = resolve(parentDocumentId)
        require(parent.isDirectory) { "Parent is not a directory" }
        val target = child(parent, displayName)
        val created = if (mimeType == Document.MIME_TYPE_DIR) target.mkdirs() else target.createNewFile()
        if (!created) throw IllegalStateException("Unable to create $displayName")
        notifyParent(parent)
        return idFor(target)
    }

    override fun deleteDocument(documentId: String) {
        val file = resolve(documentId)
        require(file != root) { "The root directory cannot be deleted" }
        val parent = file.parentFile ?: root
        if (!file.deleteRecursively()) throw IllegalStateException("Unable to delete $documentId")
        notifyParent(parent)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = resolve(documentId)
        require(file != root) { "The root directory cannot be renamed" }
        val target = child(file.parentFile ?: root, displayName)
        if (target.exists() || !file.renameTo(target)) throw IllegalStateException("Unable to rename $documentId")
        notifyParent(target.parentFile ?: root)
        return idFor(target)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = resolve(parentDocumentId)
        val child = resolve(documentId)
        return child != root && child.parentFile?.canonicalFile == parent
    }

    private fun includeDocument(cursor: MatrixCursor, documentId: String) {
        val file = resolve(documentId)
        val row = cursor.newRow()
        val flags = if (file.isDirectory) {
            Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        } else {
            Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or
                Document.FLAG_SUPPORTS_RENAME
        }
        cursor.columnNames.forEach { column ->
            when (column) {
                Document.COLUMN_DOCUMENT_ID -> row.add(column, idFor(file))
                Document.COLUMN_DISPLAY_NAME -> row.add(column, file.name.ifEmpty { "Xime 私有数据" })
                Document.COLUMN_MIME_TYPE -> row.add(column, if (file.isDirectory) Document.MIME_TYPE_DIR else mimeType(file))
                Document.COLUMN_FLAGS -> row.add(column, flags)
                Document.COLUMN_SIZE -> row.add(column, if (file.isFile) file.length() else null)
                Document.COLUMN_LAST_MODIFIED -> row.add(column, file.lastModified())
                else -> row.add(column, null)
            }
        }
    }

    private fun resolve(documentId: String): File {
        val file = if (documentId == ROOT_ID || documentId.isEmpty()) root else File(root, documentId)
        val canonical = file.canonicalFile
        require(canonical == root || canonical.toPath().startsWith(root.toPath())) { "Document escapes filesDir" }
        return canonical
    }

    private fun child(parent: File, name: String): File {
        require(name.isNotEmpty() && name != "." && name != ".." && !name.contains('/')) { "Invalid display name" }
        return File(parent, name).canonicalFile.also {
            require(it.parentFile == parent.canonicalFile) { "Invalid display name" }
        }
    }

    private fun idFor(file: File): String = if (file == root) ROOT_ID else file.relativeTo(root).path

    private fun notifyParent(parent: File) {
        requireNotNull(context).contentResolver.notifyChange(
                DocumentsContract.buildChildDocumentsUri(authority, idFor(parent)), null
        )
    }

    private fun mimeType(file: File): String = android.webkit.MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

    private val authority: String
        get() = requireNotNull(context).packageName + ".documents"

    companion object {
        private const val ROOT_ID = "root"
        private val DEFAULT_ROOT_COLUMNS = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_ICON, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
        )
        private val DEFAULT_DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED
        )
    }
}
