package com.phtontools.phtonview.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.phtontools.phtonview.R
import com.phtontools.phtonview.data.model.PhotoItem
import com.phtontools.phtonview.ui.util.ThumbnailCache
import java.io.File
import java.net.URLConnection
import android.webkit.MimeTypeMap

/**
 * 相机照片预览底部面板。
 *
 * 列出相机存储中的照片，点击缩略图可下载到缓存目录并通过系统图库预览。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoGallerySheet(
    photos: List<PhotoItem>,
    loading: Boolean,
    onDismiss: () -> Unit,
    onDownload: suspend (PhotoItem, String) -> Boolean
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    var previewingPhoto by remember { mutableStateOf<PhotoItem?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.gallery),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                if (photos.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.5f),
                        contentAlignment = Alignment.Center
                    ) {
                        if (loading) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        } else {
                            Text(
                                text = stringResource(id = R.string.no_photos),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 96.dp),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(photos, key = { it.id }) { photo ->
                            PhotoThumbnail(
                                photo = photo,
                                onClick = {
                                    previewingPhoto = photo
                                }
                            )
                        }
                    }
                }
            }

            previewingPhoto?.let { photo ->
                PhotoPreviewOverlay(
                    photo = photo,
                    onClose = { previewingPhoto = null },
                    onDownload = { destination ->
                        onDownload(photo, destination)
                    }
                )
            }
        }
    }
}

@Composable
private fun PhotoThumbnail(
    photo: PhotoItem,
    onClick: () -> Unit
) {
    // **优化**（迭代 #2）：通过 LruCache 复用已解码的缩略图。
    // 首次 decode 后写入 ThumbnailCache；再次进入图库时命中缓存，零解码。
    val placeholder = photo.thumbnailPath?.let { path ->
        ThumbnailCache.get(photo.id) ?: run {
            val bmp = loadThumbnailBitmap(path)
            if (bmp != null) ThumbnailCache.put(photo.id, bmp)
            bmp
        }
    }
    // **迭代 #10**：RAW / 视频 / 不支持缩略图的文件用类型占位图标。
    // 之前所有文件都尝试 decodeBitmap，.NEF/.CR3/.ARW/.DNG 软解失败返回 null，
    // 用户看到"空白缩略图"分不清是失败还是不支持。改进后用类型图标直观区分。
    val assetKind = remember(photo.id, photo.name) { classifyPhotoAsset(photo.name) }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        placeholder?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = photo.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } ?: PhotoKindPlaceholder(assetKind = assetKind, fileName = photo.name)
    }
}

/**
 * 照片资产类型（迭代 #10）。
 *
 * 区分 JPEG / RAW / 视频 / 未知。每种类型用不同图标 + 标签展示，
 * 让用户快速识别"为什么这张是占位图"——是缩略图还没生成、还是相机端根本没缩略图。
 */
private enum class PhotoAssetKind { Jpeg, Raw, Video, Unknown }

private fun classifyPhotoAsset(name: String): PhotoAssetKind {
    val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return when (ext) {
        "jpg", "jpeg", "jpe", "jfif" -> PhotoAssetKind.Jpeg
        // 常见 RAW 格式：Nikon NEF, Canon CR2/CR3, Sony ARW/SR2/SRF, Fuji RAF,
        // Pentax PEF, Olympus ORF, Panasonic RW2, Adobe DNG, Hasselblad 3FR
        "nef", "cr2", "cr3", "arw", "sr2", "srf", "raf", "pef", "orf", "rw2", "dng", "3fr", "iiq" -> PhotoAssetKind.Raw
        // 视频格式
        "mov", "mp4", "m4v", "avi", "mts", "m2ts", "mpg", "mpeg", "avchd" -> PhotoAssetKind.Video
        else -> if (ext.isEmpty()) PhotoAssetKind.Unknown else PhotoAssetKind.Unknown
    }
}

/**
 * 无缩略图时的类型占位图（迭代 #10）。用类型色块 + 简短标签，
 * 让用户一眼看出"这是 RAW 还是视频"。
 *
 * 不用 Material Icons（项目未引入 material-icons-extended，base icons
 * 不含 Image / Camera / PlayArrow / QuestionMark），改用纯 Composable 几何形状。
 */
@Composable
private fun PhotoKindPlaceholder(
    assetKind: PhotoAssetKind,
    @Suppress("UNUSED_PARAMETER") fileName: String
) {
    val bg = when (assetKind) {
        PhotoAssetKind.Jpeg -> MaterialTheme.colorScheme.surfaceVariant
        PhotoAssetKind.Raw -> MaterialTheme.colorScheme.primaryContainer
        PhotoAssetKind.Video -> MaterialTheme.colorScheme.tertiaryContainer
        PhotoAssetKind.Unknown -> MaterialTheme.colorScheme.errorContainer
    }
    val label = when (assetKind) {
        PhotoAssetKind.Jpeg -> "JPG"
        PhotoAssetKind.Raw -> "RAW"
        PhotoAssetKind.Video -> "VIDEO"
        PhotoAssetKind.Unknown -> "?"
    }
    val shape = when (assetKind) {
        PhotoAssetKind.Jpeg -> androidx.compose.foundation.shape.CircleShape
        PhotoAssetKind.Raw -> androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
        PhotoAssetKind.Video -> androidx.compose.foundation.shape.CircleShape
        PhotoAssetKind.Unknown -> androidx.compose.foundation.shape.CircleShape
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg, shape),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(4.dp)
        ) {
            // 几何符号区分类型：Jpeg/Unknown = 圆点，Raw = 方块，Video = 三角
            Canvas(modifier = Modifier.size(20.dp)) {
                when (assetKind) {
                    PhotoAssetKind.Jpeg -> drawCircle(color = Color.Black.copy(alpha = 0.6f), radius = 8f)
                    PhotoAssetKind.Raw -> drawRect(color = Color.Black.copy(alpha = 0.6f), size = Size(16f, 16f))
                    PhotoAssetKind.Video -> {
                        val path = Path().apply {
                            moveTo(size.width * 0.2f, 0f)
                            lineTo(size.width, size.height / 2f)
                            lineTo(size.width * 0.2f, size.height)
                            close()
                        }
                        drawPath(path, color = Color.Black.copy(alpha = 0.6f))
                    }
                    PhotoAssetKind.Unknown -> drawCircle(color = Color.Black.copy(alpha = 0.4f), radius = 6f)
                }
            }
            Spacer(Modifier.size(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.Black.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun PhotoPreviewOverlay(
    photo: PhotoItem,
    onClose: () -> Unit,
    onDownload: suspend (String) -> Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var downloading by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.95f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center
    ) {
        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(id = R.string.onboarding_skip),
                tint = MaterialTheme.colorScheme.onBackground
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = photo.name,
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit
                )
            } ?: Box(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = photo.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }

            androidx.compose.material3.Button(
                onClick = {
                    if (downloading) return@Button
                    downloading = true
                    scope.launch {
                        val safeName = photo.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                        val destFile = File(File(context.cacheDir, "updates"), "preview_$safeName")
                        destFile.parentFile?.mkdirs()
                        val destination = destFile.absolutePath
                        val success = runCatching { onDownload(destination) }.getOrDefault(false)
                        if (success && destFile.exists() && destFile.length() > 0) {
                            android.widget.Toast.makeText(context, "已下载，准备打开预览", android.widget.Toast.LENGTH_SHORT).show()
                            bitmap = loadThumbnailBitmap(destination)
                            openImageWithSystem(context, destination)
                        } else {
                            android.widget.Toast.makeText(context, "下载失败或文件为空", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        downloading = false
                    }
                },
                modifier = Modifier.padding(top = 16.dp),
                enabled = !downloading
            ) {
                Text(text = stringResource(id = R.string.download_preview))
            }
        }
    }
}

private fun loadThumbnailBitmap(path: String): android.graphics.Bitmap? {
    return runCatching {
        android.graphics.BitmapFactory.decodeFile(path)
    }.getOrNull()
}

private fun openImageWithSystem(context: android.content.Context, path: String) {
    val file = File(path)
    if (!file.exists()) return
    runCatching {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val mimeType = URLConnection.guessContentTypeFromName(file.name)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "image/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }.onFailure {
        android.widget.Toast.makeText(context, "无法打开预览：${it.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}
