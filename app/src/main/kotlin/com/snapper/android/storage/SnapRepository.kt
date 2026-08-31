package com.snapper.android.storage

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.snapper.android.settings.SnapSettings
import com.snapper.android.types.SnapSourceMetadata
import java.io.File
import java.io.FileFilter
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class SnapRepository(context: Context) {
    enum class DeleteOutcome {
        DELETED,
        LIVE_PIN,
        FAILED,
    }

    class DeleteSummary(
        val deleted: Int,
        val livePins: Int,
        val failed: Int,
    )

    class Entry(val file: File, val source: SnapSourceMetadata)

    class LivePinState(
        val basename: String,
        val centerXFraction: Float,
        val centerYFraction: Float,
        val width: Int,
        val height: Int,
        val displayWidth: Int,
        val displayHeight: Int,
        val displayRotation: Int,
    ) {
        init {
            requireValidLivePinBasename(basename)
            require(centerXFraction.isFinite() && centerYFraction.isFinite())
            require(width > 0 && height > 0)
            require(displayWidth > 0 && displayHeight > 0)
            require(displayRotation in 0..3)
        }
    }

    class LivePinSession(revision: Long, val userPinsVisible: Boolean, pins: List<LivePinState>) {
        val revision: Long = revision.also { require(it >= 0L) }

        val pins: List<LivePinState> = pins.toList()

        companion object {
            internal fun empty(): LivePinSession = LivePinSession(0L, true, emptyList())
        }
    }

    private val context: Context = context.applicationContext
    private val directory: File = File(this.context.filesDir, DIRECTORY)
    private val metadataStore = SnapSourceMetadataStore(directory)
    private val livePinStore = LivePinStore(this.context)

    init {
        check(directory.isDirectory || directory.mkdirs()) {
            "Cannot create snap history directory"
        }
    }

    fun loadLivePins(): LivePinSession = livePinStore.load()

    fun publishLivePins(session: LivePinSession) {
        LivePinStore.publishInProcess(session)
    }

    fun writeLivePins(session: LivePinSession): Boolean = livePinStore.write(session)

    fun clearLivePins(revision: Long): Boolean = livePinStore.clear(revision)

    fun nextLivePinRevision(floor: Long): Long = LivePinStore.nextRevision(floor)

    fun protectLivePin(basename: String) {
        LivePinStore.protectInProcess(basename)
    }

    fun reserveLivePin(basename: String) {
        LivePinStore.reserveInProcess(basename)
    }

    fun releaseLivePin(basename: String) {
        LivePinStore.releaseInProcess(basename)
    }

    fun save(
        bitmap: Bitmap,
        reserveForLivePin: Boolean,
        sourceMetadata: SnapSourceMetadata,
    ): File {
        val now = System.currentTimeMillis()
        var committedMetadata = sourceMetadata
        if (SnapSettings.anonymousAppNameEnabled(context)) {
            committedMetadata = SnapSourceMetadata.fallback(
                committedMetadata.capturedAtMillis,
            )
        }
        val temporary = File.createTempFile(".snap-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw IOException("PNG encoder failed")
                }
            }
            return synchronized(FILE_LOCK) {
                val target = nextSnapFile(now)
                if (!temporary.renameTo(target)) {
                    throw IOException("Could not commit snap to history")
                }
                if (!metadataStore.write(target, committedMetadata)) {
                    metadataStore.delete(target)
                    if (!target.delete()) {
                        throw IOException("Could not attach source metadata or roll back snap")
                    }
                    throw IOException("Could not attach source metadata")
                }
                if (reserveForLivePin) {
                    LivePinStore.reserveInProcess(target.name)
                }
                try {
                    pruneLocked()
                } catch (failure: RuntimeException) {
                    if (reserveForLivePin) {
                        LivePinStore.releaseInProcess(target.name)
                    }
                    metadataStore.delete(target)
                    if (!target.delete()) {
                        failure.addSuppressed(IOException("Could not roll back snap"))
                    }
                    throw failure
                }
                target
            }
        } finally {
            temporary.delete()
        }
    }

    fun decode(file: File): Bitmap? {
        return if (isManaged(file)) BitmapFactory.decodeFile(file.absolutePath) else null
    }

    private fun list(): List<File> {
        val files = checkNotNull(directory.listFiles(
            FileFilter { file ->
                file.isFile && file.name.startsWith("snap-") && file.name.endsWith(".png")
            },
        )) { "Cannot list snap history directory" }
        return files.sortedByDescending { it.lastModified() }
    }

    fun listEntries(): List<Entry> {
        val files = list()
        val entries = ArrayList<Entry>(files.size)
        for (file in files) {
            val timestamp = captureTimestamp(file)
            entries.add(Entry(file, metadataStore.read(file, timestamp)))
        }
        return entries
    }

    fun latest(): File? {
        return list().firstOrNull()
    }

    fun delete(file: File): DeleteOutcome {
        synchronized(FILE_LOCK) {
            return delete(file, LivePinStore.protectedBasenames(context))
        }
    }

    fun deleteAll(): DeleteSummary {
        synchronized(FILE_LOCK) {
            var deleted = 0
            var livePins = 0
            var failed = 0
            val protectedNames = LivePinStore.protectedBasenames(context)
            for (file in list()) {
                when (delete(file, protectedNames)) {
                    DeleteOutcome.DELETED -> deleted++
                    DeleteOutcome.LIVE_PIN -> livePins++
                    DeleteOutcome.FAILED -> failed++
                }
            }
            return DeleteSummary(deleted, livePins, failed)
        }
    }

    fun uriFor(file: File): Uri {
        require(isManaged(file)) { "File is outside Snapper history" }
        return Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(context.packageName + AUTHORITY_SUFFIX)
            .appendPath(file.name)
            .build()
    }

    fun copyToGallery(file: File): Uri {
        if (!isManaged(file) || !file.isFile) {
            throw IOException("File is outside Snapper history")
        }
        val values = ContentValues()
        values.put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        values.put(
            MediaStore.Images.Media.RELATIVE_PATH,
            Environment.DIRECTORY_PICTURES + "/Snapper",
        )
        values.put(MediaStore.Images.Media.IS_PENDING, 1)
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore refused the image")
        try {
            FileInputStream(file).use { input ->
                val output = resolver.openOutputStream(uri)
                    ?: throw IOException("MediaStore returned no output stream")
                output.use { input.transferTo(it) }
            }
        } catch (error: IOException) {
            resolver.delete(uri, null, null)
            throw error
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    fun saveClipboard(bitmap: Bitmap): File {
        val temporary = File.createTempFile(".clipboard-", ".tmp", directory)
        return try {
            FileOutputStream(temporary).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw IOException("PNG encoder failed")
                }
            }
            replaceClipboard(temporary)
        } finally {
            temporary.delete()
        }
    }

    fun stageClipboard(source: File): File {
        if (!isManaged(source) || !source.isFile) {
            throw IOException("File is outside Snapper history")
        }
        val target = File(directory, "clipboard.png")
        if (source.canonicalFile == target.canonicalFile) {
            return target
        }
        val temporary = File.createTempFile(".clipboard-", ".tmp", directory)
        return try {
            Files.copy(source.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING)
            replaceClipboard(temporary)
        } finally {
            temporary.delete()
        }
    }

    private fun replaceClipboard(temporary: File): File {
        val target = File(directory, "clipboard.png")
        synchronized(FILE_LOCK) {
            try {
                Files.move(
                    temporary.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (unsupported: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }
        return target
    }

    internal fun resolveProviderPath(name: String): File? {
        if (name.contains("/") || name.contains("\\") ||
            !name.endsWith(".png")
        ) {
            return null
        }
        val file = File(directory, name)
        return if (isManaged(file) && file.isFile) file else null
    }

    private fun isManaged(file: File): Boolean {
        return try {
            file.canonicalFile.parentFile == directory.canonicalFile
        } catch (ignored: IOException) {
            false
        }
    }

    private fun delete(file: File, protectedNames: Set<String>): DeleteOutcome {
        if (!isManaged(file)) {
            return DeleteOutcome.FAILED
        }
        if (protectedNames.contains(file.name)) {
            return DeleteOutcome.LIVE_PIN
        }
        if (!file.delete()) {
            return DeleteOutcome.FAILED
        }
        metadataStore.delete(file)
        return DeleteOutcome.DELETED
    }

    fun prune() {
        synchronized(FILE_LOCK) {
            pruneLocked()
        }
    }

    private fun pruneLocked() {
        val limit = SnapSettings.historyLimit(context)
        if (limit == SnapSettings.HISTORY_UNLIMITED) {
            return
        }
        require(limit >= SnapSettings.HISTORY_KEEP_LATEST)
        val protectedNames = LivePinStore.protectedBasenames(context)
        val files = list()
        for (index in limit until files.size) {
            val file = files[index]
            if (!protectedNames.contains(file.name)) {
                if (file.delete()) {
                    metadataStore.delete(file)
                }
            }
        }
    }

    private fun nextSnapFile(now: Long): File {
        var timestamp = now
        while (true) {
            val target = File(directory, "snap-$timestamp.png")
            if (!target.exists()) {
                return target
            }
            timestamp = Math.addExact(timestamp, 1L)
        }
    }

    private fun captureTimestamp(file: File): Long {
        val timestamp = file.name.substring(5, file.name.length - 4).toLong()
        require(timestamp > 0L)
        return timestamp
    }

    companion object {
        internal const val AUTHORITY_SUFFIX = ".files"
        private const val DIRECTORY = "snaps"
        private val FILE_LOCK = Any()

        internal fun requireValidLivePinBasename(basename: String): String {
            require(
                basename.isNotEmpty() &&
                    basename.startsWith("snap-") &&
                    !basename.contains('/') &&
                    !basename.contains('\\') &&
                    basename.endsWith(".png"),
            )
            return basename
        }
    }
}
