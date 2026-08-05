package com.phtontools.phtonview.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 轻量级 Markdown 渲染组件（issue #188 改进）。
 *
 * 支持 GitHub Release Notes 常用语法：
 * - 标题（# ## ###）
 * - 粗体（**text**）
 * - 斜体（*text*）
 * - 行内代码（`code`）
 * - 无序列表（- / *）
 * - 有序列表（1. 2. 3.）
 * - 链接（[text](url)）
 * - 分隔线（---）
 * - 代码块（```code```）
 *
 * 不依赖第三方库，纯 Compose AnnotatedString 实现。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    val lines = markdown.lines()
    val blocks = parseMarkdownBlocks(lines)

    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is MarkdownBlock.Heading -> {
                    val fontSize = when (block.level) {
                        1 -> 24.sp
                        2 -> 20.sp
                        else -> 17.sp
                    }
                    Text(
                        text = parseInlineMarkdown(block.text),
                        fontSize = fontSize,
                        fontWeight = FontWeight.Bold,
                        color = color,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }
                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = parseInlineMarkdown(block.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = color,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
                is MarkdownBlock.ListItem -> {
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = color.copy(alpha = 0.6f))) {
                                append(block.prefix)
                            }
                            append(" ")
                            append(parseInlineMarkdown(block.text))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = color,
                        modifier = Modifier.padding(start = 8.dp, top = 1.dp, bottom = 1.dp)
                    )
                }
                is MarkdownBlock.CodeBlock -> {
                    Text(
                        text = block.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = color.copy(alpha = 0.9f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }
                is MarkdownBlock.Separator -> {
                    Spacer(modifier = Modifier.height(1.dp))
                }
                is MarkdownBlock.Empty -> {
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

// ── 块级解析 ───────────────────────────────────────────────

private sealed class MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
    data class ListItem(val prefix: String, val text: String) : MarkdownBlock()
    data class CodeBlock(val text: String) : MarkdownBlock()
    object Separator : MarkdownBlock()
    object Empty : MarkdownBlock()
}

private fun parseMarkdownBlocks(lines: List<String>): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        // 空行
        if (trimmed.isEmpty()) {
            blocks.add(MarkdownBlock.Empty)
            i++
            continue
        }

        // 分隔线
        if (trimmed.matches(Regex("^[-*_]{3,}$"))) {
            blocks.add(MarkdownBlock.Separator)
            i++
            continue
        }

        // 代码块
        if (trimmed.startsWith("```")) {
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            blocks.add(MarkdownBlock.CodeBlock(codeLines.joinToString("\n")))
            i++ // skip closing ```
            continue
        }

        // 标题
        val headingMatch = Regex("^(#{1,6})\\s+(.+)$").find(trimmed)
        if (headingMatch != null) {
            blocks.add(MarkdownBlock.Heading(headingMatch.groupValues[1].length, headingMatch.groupValues[2]))
            i++
            continue
        }

        // 无序列表
        val ulMatch = Regex("^[-*+]\\s+(.+)$").find(trimmed)
        if (ulMatch != null) {
            blocks.add(MarkdownBlock.ListItem("•", ulMatch.groupValues[1]))
            i++
            continue
        }

        // 有序列表
        val olMatch = Regex("^\\d+\\.\\s+(.+)$").find(trimmed)
        if (olMatch != null) {
            val num = trimmed.substringBefore(".")
            blocks.add(MarkdownBlock.ListItem("$num.", olMatch.groupValues[1]))
            i++
            continue
        }

        // 普通段落
        blocks.add(MarkdownBlock.Paragraph(trimmed))
        i++
    }
    return blocks
}

// ── 行内解析（粗体、斜体、行内代码、链接）───────────────────

private fun parseInlineMarkdown(text: String): AnnotatedString {
    return buildAnnotatedString {
        var remaining = text
        while (remaining.isNotEmpty()) {
            when {
                // 行内代码 `code`
                remaining.startsWith("`") -> {
                    val end = remaining.indexOf('`', 1)
                    if (end > 0) {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFF2A2A2A))) {
                            append(remaining.substring(1, end))
                        }
                        remaining = remaining.substring(end + 1)
                    } else {
                        append(remaining[0])
                        remaining = remaining.substring(1)
                    }
                }
                // 粗体 **text**
                remaining.startsWith("**") -> {
                    val end = remaining.indexOf("**", 2)
                    if (end > 0) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(remaining.substring(2, end))
                        }
                        remaining = remaining.substring(end + 2)
                    } else {
                        append(remaining[0])
                        remaining = remaining.substring(1)
                    }
                }
                // 斜体 *text*
                remaining.startsWith("*") && remaining.length > 1 && remaining[1] != '*' -> {
                    val end = remaining.indexOf('*', 1)
                    if (end > 0) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(remaining.substring(1, end))
                        }
                        remaining = remaining.substring(end + 1)
                    } else {
                        append(remaining[0])
                        remaining = remaining.substring(1)
                    }
                }
                // 链接 [text](url)
                remaining.startsWith("[") -> {
                    val linkMatch = Regex("^\\[([^]]+)]\\(([^)]+)\\)").find(remaining)
                    if (linkMatch != null) {
                        val linkText = linkMatch.groupValues[1]
                        withStyle(SpanStyle(color = Color(0xFF6DA1E6), textDecoration = TextDecoration.Underline)) {
                            append(linkText)
                        }
                        remaining = remaining.substring(linkMatch.value.length)
                    } else {
                        append(remaining[0])
                        remaining = remaining.substring(1)
                    }
                }
                else -> {
                    // 普通文本，找到下一个特殊字符
                    val nextSpecial = remaining.indexOfFirst { it in setOf('`', '*', '[') }
                    if (nextSpecial < 0) {
                        append(remaining)
                        remaining = ""
                    } else if (nextSpecial == 0) {
                        //  shouldn't happen since we checked above, but safety
                        append(remaining[0])
                        remaining = remaining.substring(1)
                    } else {
                        append(remaining.substring(0, nextSpecial))
                        remaining = remaining.substring(nextSpecial)
                    }
                }
            }
        }
    }
}
