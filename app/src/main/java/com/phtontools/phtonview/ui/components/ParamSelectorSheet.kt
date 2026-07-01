package com.phtontools.phtonview.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phtontools.phtonview.R
import com.phtontools.phtonview.data.model.ExposureSettings
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 底部抽屉：点击 ISO / S / EV / F 后弹出参数选择器。
 *
 * 支持三种操作方式：
 * 1. 顶部当前数值点击后可用键盘输入；
 * 2. 滑条快速调整；
 * 3. 常用值网格一键选择。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParamSelectorSheet(
    kind: ParamKind,
    exposure: ExposureSettings,
    onDismiss: () -> Unit,
    onIsoChange: (Int) -> Unit,
    onShutterChange: (String) -> Unit,
    onEvChange: (Float) -> Unit,
    onApertureChange: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = stringResource(id = R.string.select_param, kind.label),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(16.dp))

            when (kind) {
                ParamKind.ISO -> IsoParamPicker(
                    current = exposure.iso,
                    onValueChange = onIsoChange,
                    onSelected = { onIsoChange(it); onDismiss() }
                )
                ParamKind.SHUTTER -> ShutterParamPicker(
                    current = exposure.shutter,
                    onValueChange = onShutterChange,
                    onSelected = { onShutterChange(it); onDismiss() }
                )
                ParamKind.EV -> EvParamPicker(
                    current = exposure.ev,
                    onValueChange = onEvChange,
                    onSelected = { onEvChange(it); onDismiss() }
                )
                ParamKind.APERTURE -> ApertureParamPicker(
                    current = exposure.aperture,
                    onValueChange = onApertureChange,
                    onSelected = { onApertureChange(it); onDismiss() }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun IsoParamPicker(
    current: Int,
    onValueChange: (Int) -> Unit,
    onSelected: (Int) -> Unit
) {
    var temp by remember { mutableFloatStateOf(log2(current.toFloat())) }
    var showInput by remember { mutableStateOf(false) }
    val values = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600, 51200, 102400)

    ClickableValue(
        value = current.toString(),
        onClick = { showInput = true }
    )

    Slider(
        value = temp,
        onValueChange = {
            temp = it
            onValueChange(2f.pow(temp).roundToInt().coerceIn(50, 102400))
        },
        valueRange = log2(50f)..log2(102400f),
        steps = 0,
        modifier = Modifier.padding(vertical = 4.dp)
    )

    PresetGrid(values.map { it.toString() }, current.toString()) { onSelected(it.toInt()) }

    if (showInput) {
        NumberInputDialog(
            title = "ISO",
            initial = current.toString(),
            onConfirm = { onSelected(it.toIntOrNull() ?: current) },
            onDismiss = { showInput = false }
        )
    }
}

@Composable
private fun ShutterParamPicker(
    current: String,
    onValueChange: (String) -> Unit,
    onSelected: (String) -> Unit
) {
    var tempIndex by remember { mutableFloatStateOf(shutterToIndex(current).toFloat()) }
    var showInput by remember { mutableStateOf(false) }
    val values = listOf(
        "1/8000", "1/4000", "1/2000", "1/1000", "1/500", "1/250", "1/125",
        "1/60", "1/30", "1/15", "1/8", "1/4", "1/2", "1s", "2s", "4s",
        "8s", "15s", "30s", "60s", "Bulb"
    )

    ClickableValue(
        value = current,
        onClick = { showInput = true }
    )

    Slider(
        value = tempIndex,
        onValueChange = {
            tempIndex = it
            onValueChange(values[it.roundToInt().coerceIn(0, values.lastIndex)])
        },
        valueRange = 0f..(values.size - 1).toFloat(),
        steps = values.size - 2,
        modifier = Modifier.padding(vertical = 4.dp)
    )

    PresetGrid(values, current) { onSelected(it) }

    if (showInput) {
        TextInputDialog(
            title = stringResource(id = R.string.shutter_speed),
            initial = current,
            onConfirm = { if (it.isNotBlank()) onSelected(it) },
            onDismiss = { showInput = false }
        )
    }
}

@Composable
private fun ApertureParamPicker(
    current: String,
    onValueChange: (String) -> Unit,
    onSelected: (String) -> Unit
) {
    var tempIndex by remember { mutableFloatStateOf(apertureToIndex(current).toFloat()) }
    var showInput by remember { mutableStateOf(false) }
    val values = listOf(
        "f/1.0", "f/1.2", "f/1.4", "f/1.8", "f/2.0", "f/2.8", "f/4",
        "f/5.6", "f/8", "f/11", "f/16", "f/22", "f/32"
    )

    ClickableValue(
        value = current,
        onClick = { showInput = true }
    )

    Slider(
        value = tempIndex,
        onValueChange = {
            tempIndex = it
            onValueChange(values[it.roundToInt().coerceIn(0, values.lastIndex)])
        },
        valueRange = 0f..(values.size - 1).toFloat(),
        steps = values.size - 2,
        modifier = Modifier.padding(vertical = 4.dp)
    )

    PresetGrid(values, current) { onSelected(it) }

    if (showInput) {
        TextInputDialog(
            title = stringResource(id = R.string.aperture),
            initial = current,
            onConfirm = { if (it.isNotBlank()) onSelected(it) },
            onDismiss = { showInput = false }
        )
    }
}

@Composable
private fun EvParamPicker(
    current: Float,
    onValueChange: (Float) -> Unit,
    onSelected: (Float) -> Unit
) {
    var temp by remember { mutableFloatStateOf(current) }
    var showInput by remember { mutableStateOf(false) }
    val values = listOf(-3f, -2f, -1f, -0.7f, -0.3f, 0f, 0.3f, 0.7f, 1f, 2f, 3f)

    ClickableValue(
        value = String.format("%+.1f", current),
        onClick = { showInput = true }
    )

    Slider(
        value = temp,
        onValueChange = {
            temp = it
            onValueChange(it.coerceIn(-3f, 3f))
        },
        valueRange = -3f..3f,
        steps = 11,
        modifier = Modifier.padding(vertical = 4.dp)
    )

    PresetGrid(values.map { String.format("%+.1f", it) }, String.format("%+.1f", current)) {
        onSelected(it.toFloat())
    }

    if (showInput) {
        NumberInputDialog(
            title = "EV",
            initial = current.toString(),
            onConfirm = { onSelected(it.toFloatOrNull() ?: current) },
            onDismiss = { showInput = false }
        )
    }
}

@Composable
private fun ClickableValue(value: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )
        Text(
            text = stringResource(id = R.string.tap_to_input),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetGrid(values: List<String>, current: String, onSelected: (String) -> Unit) {
    Spacer(modifier = Modifier.height(8.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        values.forEach { value ->
            ValueChip(
                label = value,
                selected = value == current,
                onClick = { onSelected(value) }
            )
        }
    }
}

@Composable
private fun ValueChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    if (selected) {
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(8.dp),
            contentPadding = ButtonDefaults.ContentPadding
        ) {
            Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            shape = RoundedCornerShape(8.dp),
            contentPadding = ButtonDefaults.ContentPadding
        ) {
            Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun NumberInputDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text); onDismiss() }) {
                Text(stringResource(id = android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = android.R.string.cancel))
            }
        }
    )
}

@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text); onDismiss() }) {
                Text(stringResource(id = android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = android.R.string.cancel))
            }
        }
    )
}

private fun shutterToIndex(shutter: String): Int {
    val values = listOf(
        "1/8000", "1/4000", "1/2000", "1/1000", "1/500", "1/250", "1/125",
        "1/60", "1/30", "1/15", "1/8", "1/4", "1/2", "1s", "2s", "4s",
        "8s", "15s", "30s", "60s", "Bulb"
    )
    val normalized = shutter.trim().replace(" ", "")
    val exact = values.indexOf(normalized)
    if (exact >= 0) return exact
    // Tolerate camera-returned values like "1" for "1s" or "0/10000" forms.
    return values.indexOfFirst {
        it.equals(normalized, ignoreCase = true) ||
                it.trimEnd('s', 'S').equals(normalized, ignoreCase = true) ||
                normalized.equals(it.trimEnd('s', 'S'), ignoreCase = true)
    }.coerceAtLeast(0)
}

private fun apertureToIndex(aperture: String): Int {
    val values = listOf(
        "f/1.0", "f/1.2", "f/1.4", "f/1.8", "f/2.0", "f/2.8", "f/4",
        "f/5.6", "f/8", "f/11", "f/16", "f/22", "f/32"
    )
    return values.indexOf(aperture).coerceAtLeast(0)
}
