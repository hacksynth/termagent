package com.termagent.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Photos
import com.termagent.R
import com.termagent.appContainer
import com.termagent.data.Attachment
import com.termagent.data.AttachmentKind
import com.termagent.ui.theme.Spacing
import com.termagent.ui.theme.TermAgentTheme
import com.termagent.ui.theme.TerminalFontFamily
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Attachment rendering, shared by the composer (staged chips) and the transcript (sent messages).
 *
 * Images are decoded here rather than through an image-loading library: the files are already on
 * local disk, already downscaled by [com.termagent.data.ImagePrep], and the app deliberately has no
 * Coil/Glide dependency. A tiny decode-and-cache helper is all that is needed, and it keeps the
 * APK (and the build's dependency graph) unchanged.
 */

// ---------------------------------------------------------------------- image loading

/**
 * Decoded thumbnails, keyed by `path@mtime`.
 *
 * `SoftReference`-free on purpose: these are small (a few hundred KB at most) and bounded, and a
 * hard cap is easier to reason about than GC timing for a handful of images.
 */
private val thumbnailCache = ConcurrentHashMap<String, ImageBitmap>()
private const val THUMBNAIL_CACHE_LIMIT = 16

/** Decode [file] scaled so its longest edge fits [maxEdge]; `null` if it cannot be read. */
private fun loadScaled(file: File, maxEdge: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2

    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
}.getOrNull()

/**
 * Asynchronously decode [file] into a Compose [ImageBitmap].
 *
 * Keyed on the file's path + mtime so a replaced file is re-read; decoding happens off the main
 * thread because it is a real disk + codec operation.
 */
@Composable
fun rememberFileBitmap(file: File?, maxEdge: Int = 1024): ImageBitmap? {
    val key = file?.let { "${it.absolutePath}@${it.lastModified()}" }
    var bitmap by remember(key) { mutableStateOf(key?.let(thumbnailCache::get)) }

    LaunchedEffect(key) {
        // `takeIf` is what makes the null check below meaningful: `key != null` already implies
        // `file != null`, but it says nothing about whether the file still exists on disk.
        val target = file?.takeIf { it.isFile }
        if (key == null || target == null) {
            bitmap = null
            return@LaunchedEffect
        }
        if (bitmap == null) {
            val decoded = withContext(Dispatchers.IO) { loadScaled(target, maxEdge) }
            if (decoded != null) {
                if (thumbnailCache.size >= THUMBNAIL_CACHE_LIMIT) thumbnailCache.clear()
                thumbnailCache[key] = decoded
            }
            bitmap = decoded
        }
    }

    return bitmap
}

/** Absolute file for [attachment] — the store owns the `$HOME` mapping. */
@Composable
private fun fileOf(attachment: Attachment): File {
    val context = LocalContext.current
    return remember(attachment.path, context) {
        context.appContainer.attachmentStore.fileOf(attachment)
    }
}

// ---------------------------------------------------------------------- composer chips

/**
 * The staged-attachment strip shown above the text field.
 *
 * Images get a real thumbnail (so the user can confirm *which* screenshot they picked), documents
 * get a name + size chip. Nothing is sent until the message is, so removal here is free.
 */
@Composable
fun PendingAttachmentStrip(
    attachments: List<Attachment>,
    importing: Boolean,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty() && !importing) return
    val palette = TermAgentTheme.colors

    Column(modifier = modifier.fillMaxWidth()) {
        if (importing) {
            Row(
                modifier = Modifier.padding(start = Spacing.xs, bottom = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InfiniteProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    color = palette.accent,
                    size = 12.dp,
                    strokeWidth = 1.5.dp,
                    orbitingDotSize = 2.dp,
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.attach_importing),
                    color = palette.textFaint,
                    fontSize = 11.sp,
                )
            }
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = Spacing.xs,
                end = Spacing.xs,
            ),
        ) {
            items(attachments, key = { it.id }) { attachment ->
                PendingAttachmentChip(attachment = attachment, onRemove = { onRemove(attachment.id) })
            }
        }
    }
}

@Composable
private fun PendingAttachmentChip(attachment: Attachment, onRemove: () -> Unit) {
    val palette = TermAgentTheme.colors
    val bitmap = if (attachment.isImage) rememberFileBitmap(fileOf(attachment), maxEdge = 256) else null

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(palette.surface)
                .border(1.dp, palette.divider, RoundedCornerShape(12.dp))
                .padding(end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                    .background(palette.surfaceElevated),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = stringResource(R.string.message_attachment_image),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        imageVector = if (attachment.isImage) {
                            MiuixIcons.Regular.Photos
                        } else {
                            MiuixIcons.Regular.File
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = palette.textSecondary,
                    )
                }
            }

            Spacer(Modifier.width(Spacing.sm))

            Column(
                modifier = Modifier
                    .widthIn(max = 132.dp)
                    .padding(vertical = Spacing.sm),
            ) {
                Text(
                    text = attachment.name,
                    color = palette.textPrimary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(attachment.readableSize)
                        if (attachment.isImage && attachment.width > 0) {
                            append(" · ").append(attachment.width).append('×').append(attachment.height)
                        }
                    },
                    color = palette.textFaint,
                    fontSize = 10.sp,
                    fontFamily = TerminalFontFamily,
                    maxLines = 1,
                )
            }

            Spacer(Modifier.width(Spacing.xs))

            IconButton(
                onClick = onRemove,
                minWidth = 24.dp,
                minHeight = 24.dp,
                backgroundColor = Color.Transparent,
            ) {
                Icon(
                    imageVector = MiuixIcons.Regular.Close,
                    contentDescription = stringResource(R.string.attach_remove),
                    modifier = Modifier.size(14.dp),
                    tint = palette.textSecondary,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------- sent messages

/**
 * The attachments of an already-sent user message.
 *
 * Images are tappable: the thumbnail in the bubble is bounded, and reading a screenshot often
 * requires seeing it at full size.
 */
@Composable
fun SentAttachments(
    attachments: List<Attachment>,
    onOpenImage: (Attachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    val palette = TermAgentTheme.colors

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        attachments.forEach { attachment ->
            if (attachment.isImage) {
                ImageAttachment(attachment = attachment, onOpen = { onOpenImage(attachment) })
            } else {
                FileAttachmentChip(attachment = attachment, tint = palette.onUserBubble)
            }
        }
    }
}

@Composable
private fun ImageAttachment(attachment: Attachment, onOpen: () -> Unit) {
    val palette = TermAgentTheme.colors
    val file = fileOf(attachment)
    val bitmap = rememberFileBitmap(file, maxEdge = 1080)

    // Fall back to a neutral ratio when the dimensions are unknown, so the layout never collapses.
    val ratio = if (attachment.width > 0 && attachment.height > 0) {
        (attachment.width.toFloat() / attachment.height.toFloat()).coerceIn(0.4f, 2.5f)
    } else {
        4f / 3f
    }

    Box(
        modifier = Modifier
            .widthIn(max = 300.dp)
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.surfaceElevated)
            .clickable(enabled = bitmap != null, onClick = onOpen),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = attachment.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(ratio),
                contentScale = ContentScale.Fit,
            )
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.message_attachment_missing),
                    color = palette.textFaint,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun FileAttachmentChip(attachment: Attachment, tint: Color) {
    val palette = TermAgentTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(palette.surfaceElevated)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MiuixIcons.Regular.File,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = tint.copy(alpha = 0.8f),
        )
        Spacer(Modifier.width(Spacing.sm))
        Column {
            Text(
                text = attachment.name,
                color = tint,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(attachment.readableSize)
                    when (attachment.kind) {
                        // Telling the user (and only the user) why a file was not inlined is useful
                        // when the model then asks for the path.
                        AttachmentKind.Image -> Unit
                        AttachmentKind.Text -> append(" · ").append(attachment.mimeType)
                        AttachmentKind.Binary -> append(" · ").append(attachment.mimeType)
                    }
                },
                color = tint.copy(alpha = 0.6f),
                fontSize = 10.sp,
                fontFamily = TerminalFontFamily,
                maxLines = 1,
            )
        }
    }
}

// ---------------------------------------------------------------------- full-screen viewer

/**
 * Full-screen viewer for one attached image.
 *
 * Its own `Dialog` rather than a navigation destination: viewing an image must not disturb the
 * conversation, the composer contents or a running turn.
 */
@Composable
fun ImageViewerDialog(attachment: Attachment, onDismiss: () -> Unit) {
    val palette = TermAgentTheme.colors
    val bitmap = rememberFileBitmap(fileOf(attachment), maxEdge = 2048)

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = attachment.name,
                    modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    text = stringResource(R.string.message_attachment_missing),
                    color = palette.textFaint,
                    fontSize = 13.sp,
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = attachment.name,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (attachment.width > 0 && attachment.height > 0) {
                    Text(
                        text = stringResource(
                            R.string.attachment_dimensions,
                            attachment.width,
                            attachment.height,
                        ) + "  ·  " + attachment.readableSize,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        fontFamily = TerminalFontFamily,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- picker sheet

/**
 * The source picker behind the composer's "+" button.
 *
 * A plain dialog with two rows rather than a Material bottom sheet: the app's components come from
 * Miuix, and reaching for `ModalBottomSheet` would drag in an unrelated design language for one
 * screen. Two large tap targets communicate "gallery" and "files" just as well.
 */
@Composable
fun AttachmentSourceDialog(
    onPickPhotos: () -> Unit,
    onPickFiles: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = TermAgentTheme.colors

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 16.dp,
            colors = CardDefaults.defaultColors(
                color = palette.surface,
                contentColor = palette.textPrimary,
            ),
        ) {
            Column(modifier = Modifier.padding(Spacing.md)) {
                Text(
                    text = stringResource(R.string.attach_open),
                    modifier = Modifier.padding(start = Spacing.sm, top = Spacing.sm, bottom = Spacing.sm),
                    color = palette.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )

                SourceRow(
                    icon = MiuixIcons.Regular.Photos,
                    title = stringResource(R.string.attach_photos),
                    subtitle = stringResource(R.string.attach_photos_hint),
                    onClick = onPickPhotos,
                )
                SourceRow(
                    icon = MiuixIcons.Regular.File,
                    title = stringResource(R.string.attach_files),
                    subtitle = stringResource(R.string.attach_files_hint),
                    onClick = onPickFiles,
                )
            }
        }
    }
}

@Composable
private fun SourceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val palette = TermAgentTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(palette.surfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = palette.accent,
            )
        }
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = palette.textPrimary, fontSize = 14.sp)
            Text(text = subtitle, color = palette.textFaint, fontSize = 11.sp)
        }
    }
}
