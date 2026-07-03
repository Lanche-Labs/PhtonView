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
    var sections by remember { mutableStateOf<List<LicenseSectionData>>(emptyList()) }

    LaunchedEffect(Unit) {
        sections = loadAllLicenseSections(context)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (sections.isEmpty()) {
            Text(
                text = "暂无许可证内容。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
        } else {
            sections.forEach { section ->
                LicenseSection(title = section.title, content = section.content)
            }
        }
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

private data class LicenseSectionData(
    val title: String,
    val content: String
)

private fun loadAllLicenseSections(context: android.content.Context): List<LicenseSectionData> {
    val result = mutableListOf<LicenseSectionData>()

    // 1. 根目录 LICENSE（如果存在）
    loadAssetText(context, "LICENSE")?.takeIf { it.isNotBlank() }?.let {
        result.add(LicenseSectionData("LICENSE", it))
    }

    // 2. 根目录 COPYING（如果存在）
    loadAssetText(context, "COPYING")?.takeIf { it.isNotBlank() }?.let {
        result.add(LicenseSectionData("COPYING", it))
    }

    // 3. LICENSES 目录下的所有文件
    // assets 源目录指向 generated/assets/licenses，因此运行时路径直接是 "LICENSES/"
    val licenseFiles = listAssetFiles(context, "LICENSES")
    AppLogger.report("UI", "LicensePage.kt:loadAllLicenseSections", "License files", mapOf("count" to licenseFiles.size.toString(), "files" to licenseFiles.joinToString()))
    licenseFiles.sorted().forEach { fileName ->
        val content = loadAssetText(context, "LICENSES/$fileName")
        if (!content.isNullOrBlank()) {
            val title = fileName.substringBeforeLast(".", fileName)
            result.add(LicenseSectionData(title, content))
        }
    }

    return result
}

private fun listAssetFiles(context: android.content.Context, path: String): List<String> {
    return runCatching {
        context.assets.list(path)?.toList() ?: emptyList()
    }.onFailure {
        AppLogger.report("UI", "LicensePage.kt:listAssetFiles", "List failed", mapOf("path" to path, "error" to (it.message ?: "unknown")))
    }.getOrDefault(emptyList())
}

private fun loadAssetText(context: android.content.Context, path: String): String? {
    // 直接尝试传入路径；若不带 licenses/ 前缀，也尝试从 licenses/ 下读取（兼容旧打包方式）
    val candidates = if (path.startsWith("licenses/")) {
        listOf(path)
    } else {
        listOf(path, "licenses/$path")
    }
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
    return null
}
