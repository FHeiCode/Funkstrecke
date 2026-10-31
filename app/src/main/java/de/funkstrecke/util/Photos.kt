package de.funkstrecke.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Fotos liegen im privaten App-Speicher unter files/photos. */
object Photos {

    private fun authority(context: Context) = "${context.packageName}.files"

    fun dir(context: Context): File = File(context.filesDir, "photos").apply { mkdirs() }

    fun file(context: Context, name: String): File = File(dir(context), name)

    fun newFile(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir(context), "IMG_${stamp}_${(1000..9999).random()}.jpg")
    }

    fun uri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, authority(context), file)

    fun delete(context: Context, name: String) {
        file(context, name).delete()
    }

    suspend fun importFrom(context: Context, source: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val target = newFile(context)
            context.contentResolver.openInputStream(source)!!.use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            target.name
        }.getOrNull()
    }

    fun view(context: Context, name: String) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri(context, file(context, name)), "image/jpeg")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(intent) }
    }

    /** Verkleinertes, richtig gedrehtes Vorschaubild. */
    fun decodeThumbnail(file: File, targetSize: Int): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetSize) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val rotation = runCatching {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (rotation == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
