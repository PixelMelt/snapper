package com.snapper.android.storage

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

class SnapFileProvider : ContentProvider() {
    private lateinit var repository: SnapRepository

    override fun onCreate(): Boolean {
        repository = SnapRepository(requireContext())
        return true
    }

    override fun getType(uri: Uri): String? {
        return if (resolve(uri) == null) null else "image/png"
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? {
        val file = resolve(uri) ?: return null
        val columns = projection
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns, 1)
        val row = cursor.newRow()
        for (column in columns) {
            when (column) {
                OpenableColumns.DISPLAY_NAME -> row.add(column, file.name)
                OpenableColumns.SIZE -> row.add(column, file.length())
                else -> row.add(column, null)
            }
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if ("r" != mode) {
            throw FileNotFoundException("Snap history is read-only")
        }
        val file = resolve(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        throw UnsupportedOperationException("read-only")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        throw UnsupportedOperationException("read-only")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int {
        throw UnsupportedOperationException("read-only")
    }

    private fun resolve(uri: Uri): File? {
        val context = requireContext()
        if ("content" != uri.scheme ||
            context.packageName + SnapRepository.AUTHORITY_SUFFIX != uri.authority ||
            uri.pathSegments.size != 1
        ) {
            return null
        }
        return repository.resolveProviderPath(uri.pathSegments.single())
    }
}
