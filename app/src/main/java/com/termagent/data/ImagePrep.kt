package com.termagent.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Local image preparation for the vision path.
 *
 * Every image that is about to be sent to a multimodal model goes through here first, for three
 * reasons:
 *
 * 1. **Cost.** Vision models are billed per image token, and token count grows with pixel area.
 *    A 12 MP phone photo is ~40× more expensive than it needs to be. Downscaling the long edge to
 *    [MAX_EDGE] (1568 px, the size the OpenAI vision docs themselves recommend) keeps the image
 *    legible while cutting the cost by an order of magnitude.
 * 2. **Bandwidth.** Images travel as base64 inside the JSON request body — base64 is 4/3 the raw
 *    size, so a 5 MB PNG becomes an 6.7 MB request body without this step.
 * 3. **Request limits.** Most providers reject bodies over ~20 MB, which a couple of full-size
 *    photos would blow through.
 *
 * The output is always JPEG (except for images that keep transparency, which stay PNG), which is
 * what every OpenAI-compatible vision endpoint accepts.
 */
object ImagePrep {

    private const val TAG = "ImagePrep"

    /** Longest edge, in pixels, after downscaling. Matches the OpenAI vision guidance. */
    const val MAX_EDGE = 1568

    /** JPEG quality; 85 is the usual sweet spot between artefacts and size. */
    const val JPEG_QUALITY = 85

    /** Refuse to decode anything larger than this, so a corrupt header cannot OOM the app. */
    private const val MAX_PIXELS = 80_000_000

    /** A prepared image, ready to be encoded as a data URL. */
    data class Prepared(
        val bytes: ByteArray,
        val mimeType: String,
        val width: Int,
        val height: Int,
    ) {
        val sizeBytes: Int get() = bytes.size

        // `data class` with a ByteArray needs these spelled out, otherwise equality is identity
        // and any test (or `remember`) keyed on a Prepared would always see a change.
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Prepared &&
                    mimeType == other.mimeType &&
                    width == other.width &&
                    height == other.height &&
                    bytes.contentEquals(other.bytes))

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + mimeType.hashCode()
            result = 31 * result + width
            result = 31 * result + height
            return result
        }
    }

    /**
     * Decode [uri], downscale it and re-encode it as JPEG.
     *
     * Returns `null` when the content cannot be decoded as an image — the caller reports that as a
     * normal, user-visible failure rather than crashing.
     */
    fun prepare(resolver: ContentResolver, uri: Uri): Prepared? = runCatching {
        val bounds = readBounds(resolver, uri) ?: return null
        if (bounds.first <= 0 || bounds.second <= 0) return null
        if (bounds.first.toLong() * bounds.second.toLong() > MAX_PIXELS) {
            Log.w(TAG, "Refusing to decode ${bounds.first}x${bounds.second} image (too large)")
            return null
        }

        val bitmap = decodeScaled(resolver, uri, bounds.first, bounds.second) ?: return null
        try {
            encode(bitmap, alphaPossible = runCatching { resolver.getType(uri) }.getOrNull())
        } finally {
            bitmap.recycle()
        }
    }.onFailure { Log.w(TAG, "Failed to prepare image $uri", it) }.getOrNull()

    /** Same pipeline for an image already on local disk (used by the `view_image` tool). */
    fun prepare(file: File): Prepared? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val bitmap = decodeScaledFromFile(file) ?: return null
        try {
            encode(bitmap, alphaPossible = file.extension.lowercase())
        } finally {
            bitmap.recycle()
        }
    }.onFailure { Log.w(TAG, "Failed to prepare image ${file.absolutePath}", it) }.getOrNull()

    /** `data:image/jpeg;base64,….` — the form `image_url` expects for inline bytes. */
    fun dataUrl(prepared: Prepared): String = dataUrl(prepared.bytes, prepared.mimeType)

    /** `data:…;base64,….` for raw bytes of a known type. */
    fun dataUrl(bytes: ByteArray, mimeType: String): String =
        "data:$mimeType;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)

    // ------------------------------------------------------------------ decode

    private fun readBounds(resolver: ContentResolver, uri: Uri): Pair<Int, Int>? =
        resolver.openInputStream(uri)?.use { stream ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(stream, null, options)
            options.outWidth to options.outHeight
        }

    private fun decodeScaled(
        resolver: ContentResolver,
        uri: Uri,
        width: Int,
        height: Int,
    ): Bitmap? {
        val longest = maxOf(width, height)

        // API 28+ can do the whole thing in one pass (including EXIF rotation and colour space
        // handling), which is both faster and less memory-hungry than decode-then-scale.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return runCatching {
                val source = ImageDecoder.createSource(resolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val sample = sampleSize(longest, maxOf(info.size.width, info.size.height))
                    if (sample > 1) decoder.setTargetSampleSize(sample)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                }.let { downscale(it) }
            }.onFailure { Log.w(TAG, "ImageDecoder failed for $uri, falling back", it) }
                .getOrElse { decodeScaledLegacy(resolver, uri, longest) }
        }

        return decodeScaledLegacy(resolver, uri, longest)
    }

    private fun decodeScaledLegacy(resolver: ContentResolver, uri: Uri, longest: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(longest, longest)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        return downscale(applyExifRotation(resolver, uri, decoded))
    }

    private fun decodeScaledFromFile(file: File): Bitmap? {
        // `prepare(file)` has already decoded the bounds to validate the image, but those live in a
        // separate Options instance here so this stays safe to call on its own.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(longest, longest) }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        return downscale(decoded)
    }

    /** Largest power-of-two subsample that still leaves the longest edge at or above [target]. */
    private fun sampleSize(sourceLongest: Int, target: Int): Int {
        if (target <= 0) return 1
        var sample = 1
        while (sourceLongest / (sample * 2) >= target) sample *= 2
        return sample
    }

    /** Final exact resize. Bounding-box scaling, so the aspect ratio is always preserved. */
    private fun downscale(bitmap: Bitmap): Bitmap {
        val current = maxOf(bitmap.width, bitmap.height)
        if (current <= MAX_EDGE) return bitmap

        val ratio = MAX_EDGE.toFloat() / current
        val targetWidth = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)

        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /**
     * Bakes the EXIF orientation into the pixels.
     *
     * Only needed on the pre-28 path: `ImageDecoder` does this itself. Without it, a photo shot in
     * portrait would reach the model rotated 90°, which quietly ruins OCR-style questions.
     */
    @Suppress("DEPRECATION")
    private fun applyExifRotation(resolver: ContentResolver, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                android.media.ExifInterface(stream).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrNull() ?: return bitmap

        val matrix = Matrix()
        when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            android.media.ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f); matrix.postScale(-1f, 1f)
            }
            android.media.ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f); matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }

        val rotated = runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrNull() ?: return bitmap

        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    // ------------------------------------------------------------------ encode

    /**
     * Re-encodes [bitmap].
     *
     * PNG is kept only when the image actually has transparency, because a screenshot of a UI with
     * rounded corners would otherwise gain black corners. Everything else becomes JPEG, which is
     * dramatically smaller for photographs.
     */
    private fun encode(bitmap: Bitmap, alphaPossible: String?): Prepared {
        // `Bitmap.hasAlpha()` reports whether the *config* has an alpha channel, which ARGB_8888
        // always does — so it cannot distinguish a PNG from a JPEG on its own. Gating on the
        // source type first is what keeps photos from being re-encoded as (much larger) PNGs.
        val mayHaveAlpha = alphaPossible
            ?.lowercase()
            ?.let { it.contains("png") || it.contains("webp") || it.contains("gif") || it == "png" }
            ?: false
        val hasAlpha = mayHaveAlpha && bitmap.hasAlpha()

        val format = if (hasAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val mime = if (hasAlpha) "image/png" else "image/jpeg"

        val output = ByteArrayOutputStream()
        bitmap.compress(format, JPEG_QUALITY, output)
        var bytes = output.toByteArray()

        // A PNG re-encode of a large photo can be bigger than the JPEG would ever be; if the
        // result is huge, fall back to JPEG (opaque corner artefacts beat a 8 MB request).
        if (hasAlpha && bytes.size > JPEG_FALLBACK_BYTES) {
            val jpeg = ByteArrayOutputStream()
            val flattened = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(flattened)
            canvas.drawColor(android.graphics.Color.WHITE)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            flattened.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpeg)
            flattened.recycle()
            bytes = jpeg.toByteArray()
            return Prepared(bytes, "image/jpeg", bitmap.width, bitmap.height)
        }

        return Prepared(bytes, mime, bitmap.width, bitmap.height)
    }

    /** Above this, a PNG is re-encoded as JPEG regardless of transparency. */
    private const val JPEG_FALLBACK_BYTES = 4_000_000
}
