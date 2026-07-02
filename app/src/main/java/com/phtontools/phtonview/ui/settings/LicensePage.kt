package com.phtontools.phtonview.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phtontools.phtonview.util.AppLogger

@Composable
fun LicensePage(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var licenseText by remember { mutableStateOf("") }
    var copyingText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        licenseText = loadAssetText(context, "LICENSE")
        copyingText = loadAssetText(context, "COPYING")
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        LicenseSection(title = "LICENSE", content = licenseText)
        LicenseSection(title = "COPYING", content = copyingText)
    }
}

@Composable
private fun LicenseSection(
    title: String,
    content: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = content.ifBlank { "No content available." },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
        )
    }
}

private fun loadAssetText(context: android.content.Context, path: String): String {
    // build.gradle 会把根目录 LICENSE / COPYING 复制到 assets/licenses/
    // 某些情况下资源可能被放到根目录，所以优先 licenses/，再回退根目录
    val candidates = listOf("licenses/$path", path)
    for (candidate in candidates) {
        val text = runCatching {
            context.assets.open(candidate).bufferedReader().use { it.readText() }
        }.onFailure {
            AppLogger.report("UI", "LicensePage.kt:loadAssetText", "Load failed", mapOf("path" to candidate, "error" to (it.message ?: "unknown")))
        }.getOrNull()
        if (!text.isNullOrBlank()) {
            AppLogger.report("UI", "LicensePage.kt:loadAssetText", "Load OK", mapOf("path" to candidate, "chars" to text.length.toString()))
            return text
        }
    }
    return ""
}
