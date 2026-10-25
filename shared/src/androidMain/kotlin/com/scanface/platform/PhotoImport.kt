package com.scanface.platform

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import com.scanface.core.CapturedPhoto
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal class ImageImportTooLarge : Exception()

internal fun importPhoto(
    context: Context,
    uri: Uri,
    id: String = UUID.randomUUID().toString(),
    lens: String = "Photo library",
): CapturedPhoto {
    require(uri.scheme == "content" || uri.scheme == "file")
    require(id.matches(Regex("[a-zA-Z0-9-]{1,96}")))
    if (uri.scheme == "file") {
        val source = File(requireNotNull(uri.path)).canonicalFile
        val privateRoot = File(context.applicationInfo.dataDir).canonicalFile
        require(!source.toPath().startsWith(privateRoot.toPath()))
    }
    val directory = File(context.filesDir, "captures")
    check(directory.exists() || directory.mkdirs())
    val destination = File(directory, "$id.jpg")
    if (destination.exists()) return importedPhoto(destination, lens)
    val raw = File(context.cacheDir, "$id-import")
    val partial = File(directory, "$id-partial")
    try {
        requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
            raw.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    if (copied > 50L * 1024 * 1024) throw ImageImportTooLarge()
                    output.write(buffer, 0, count)
                }
                require(copied > 0)
            }
        }
        normalizePhoto(raw, partial)
        Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
        return importedPhoto(destination, lens)
    } finally {
        raw.delete()
        partial.delete()
    }
}

private fun importedPhoto(file: File, lens: String): CapturedPhoto {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0)
    return CapturedPhoto(file.absolutePath, bounds.outWidth, bounds.outHeight, lens, false, file.lastModified())
}
