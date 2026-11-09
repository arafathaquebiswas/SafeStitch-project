package com.safestitch.app.data

import android.content.Context
import java.io.File
import java.util.UUID

/** Inspection photos live in the app's private storage and are never shared directly. */
class PhotoStorage(context: Context) {
    private val directory = File(context.filesDir, "photos")

    fun newPhotoFile(): File {
        directory.mkdirs()
        return File(directory, "IMG_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.jpg")
    }

    fun file(fileName: String): File {
        require(fileName.isNotBlank() && '/' !in fileName && fileName != "..") { "Invalid photo name" }
        return File(directory, fileName)
    }

    fun delete(fileName: String) {
        runCatching { file(fileName).delete() }
    }
}
