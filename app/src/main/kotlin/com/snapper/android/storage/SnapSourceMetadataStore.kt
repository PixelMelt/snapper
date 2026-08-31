package com.snapper.android.storage

import com.snapper.android.types.SnapSourceMetadata
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class SnapSourceMetadataStore(private val directory: File) {
    companion object {
        private const val MAGIC = 0x534E4150
        private const val VERSION = 1
        private const val SUFFIX = ".source"
    }

    fun read(image: File, fallbackTimestamp: Long): SnapSourceMetadata {
        val sidecar = sidecarFor(image)
        if (!sidecar.isFile) {
            return SnapSourceMetadata.fallback(fallbackTimestamp)
        }
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(sidecar))).use { input ->
                check(input.readInt() == MAGIC) { "Invalid source metadata header" }
                check(input.readUnsignedByte() == VERSION) {
                    "Unsupported source metadata version"
                }
                val timestamp = input.readLong()
                val packageName = input.readUTF()
                val appLabel = input.readUTF()
                SnapSourceMetadata.fromStored(packageName, appLabel, timestamp)
            }
        } catch (unreadable: IOException) {
            SnapSourceMetadata.fallback(fallbackTimestamp)
        }
    }

    fun write(image: File, metadata: SnapSourceMetadata): Boolean {
        val target = sidecarFor(image)
        val temporary = try {
            File.createTempFile(".${image.name}.source-", ".tmp", directory)
        } catch (failure: IOException) {
            return false
        }
        return try {
            FileOutputStream(temporary).use { fileOutput ->
                DataOutputStream(BufferedOutputStream(fileOutput)).use { output ->
                    output.writeInt(MAGIC)
                    output.writeByte(VERSION)
                    output.writeLong(metadata.capturedAtMillis)
                    output.writeUTF(metadata.packageName)
                    output.writeUTF(metadata.appLabel)
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
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
            true
        } catch (failure: IOException) {
            false
        } finally {
            temporary.delete()
        }
    }

    fun delete(image: File) {
        sidecarFor(image).delete()
    }

    private fun sidecarFor(image: File): File = File(directory, image.name + SUFFIX)
}
