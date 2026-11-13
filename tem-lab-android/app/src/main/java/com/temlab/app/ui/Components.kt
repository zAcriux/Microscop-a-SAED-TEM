/*
 * TEM Lab
 * Copyright © 2026 F. Mares-Briones. All rights reserved.
 */
package com.temlab.app.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.OutputStream
import java.util.Locale

/** Image area + tool panel: side by side on tablets / landscape, stacked on phones. */
@Composable
fun AdaptivePane(image: @Composable (Modifier) -> Unit, panel: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val mw = maxWidth; val mh = maxHeight
        val wide = mw >= 720.dp || mw > mh
        if (wide) Row(Modifier.fillMaxSize()) {
            image(Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.width(if (mw >= 1000.dp) 400.dp else 330.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = panel)
        } else Column(Modifier.fillMaxSize()) {
            image(Modifier.weight(1f).fillMaxWidth())
            Column(Modifier.fillMaxWidth().heightIn(max = mh * 0.48f).verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = panel)
        }
    }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, steps: Int = 0, format: (Float) -> String = { String.format(Locale.US, "%.2f", it) }, onDone: (() -> Unit)? = null) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(format(value), style = MaterialTheme.typography.bodySmall)
        }
        Slider(value.coerceIn(range.start, range.endInclusive), onChange, valueRange = range, steps = steps, onValueChangeFinished = onDone)
    }
}

@Composable
fun NumberField(label: String, value: Double, modifier: Modifier = Modifier, onValue: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(if (value.isNaN()) "" else trimNum(value)) }
    OutlinedTextField(
        text, { t -> text = t; t.replace(',', '.').toDoubleOrNull()?.let(onValue) },
        label = { Text(label, fontSize = 11.sp) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = MaterialTheme.typography.bodyMedium,
    )
}

fun trimNum(v: Double): String = if (v == Math.rint(v) && kotlin.math.abs(v) < 1e9) v.toLong().toString() else String.format(Locale.US, "%.6g", v).trimEnd('0').trimEnd('.')

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipRow(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o), fontSize = 12.sp) }) }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
fun KeyValue(k: String, v: String) {
    Row { Text(k, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)); Text(v, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium) }
}

@Composable fun Divider() = HorizontalDivider(Modifier.padding(vertical = 2.dp))

/** Save-as helper based on the Storage Access Framework. */
class FileSaver(private val launch: (String, String, (Context, Uri) -> Unit) -> Unit) {
    fun save(name: String, mime: String, write: (OutputStream) -> Unit) = launch(name, mime) { ctx, uri ->
        ctx.contentResolver.openOutputStream(uri)!!.use(write)
    }
    fun saveUri(name: String, mime: String, action: (Context, Uri) -> Unit) = launch(name, mime, action)
}

@Composable
fun rememberFileSaver(onError: (String) -> Unit = {}): FileSaver {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<((Context, Uri) -> Unit)?>(null) }
    var mime by remember { mutableStateOf("application/octet-stream") }
    var name by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val p = pending; pending = null
        if (uri != null && p != null) try { p(ctx, uri) } catch (e: Throwable) { onError(e.message ?: e.toString()) }
    }
    LaunchedEffect(name) { name?.let { launcher.launch(it); name = null } }
    return remember { FileSaver { n, m, action -> pending = action; mime = m; name = n } }
}

@Composable
fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(24.dp)) }
}
