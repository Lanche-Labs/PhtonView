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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
    val placeholder = photo.thumbnailPath?.let { path ->
        loadThumbnailBitmap(path)
    }

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
        } ?: Text(
            text = photo.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(4.dp)
        )
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
