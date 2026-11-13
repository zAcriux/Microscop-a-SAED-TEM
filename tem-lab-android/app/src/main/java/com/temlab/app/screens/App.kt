package com.temlab.app.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.temlab.app.R
import com.temlab.app.data.AppViewModel
import com.temlab.app.ui.ChipRow
import com.temlab.app.ui.Lang
import com.temlab.app.ui.LocalTr
import com.temlab.app.ui.ThemeMode
import com.temlab.app.ui.rememberFileSaver
import java.io.File
import java.text.DateFormat
import java.util.Date

enum class Mode(val en: String, val es: String, val icon: ImageVector) {
    VIEW("Measure", "Medir", Icons.Filled.Straighten),
    PROCESS("Process", "Procesar", Icons.Filled.AutoFixHigh),
    FFT("FFT", "FFT", Icons.Filled.GridOn),
    IFFT("IFFT", "IFFT", Icons.Filled.BlurOn),
    PARTICLES("Particles", "Partículas", Icons.Filled.BubbleChart),
    STATS("Statistics", "Estadística", Icons.AutoMirrored.Filled.ShowChart),
    ANNOTATE("Annotate", "Anotar", Icons.Filled.Create),
    COMPARE("Compare", "Comparar", Icons.Filled.Compare),
    REPORT("Report", "Reporte", Icons.Filled.PictureAsPdf),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemLabApp(vm: AppViewModel) {
    val tr = LocalTr.current
    var home by rememberSaveable { mutableStateOf(true) }
    var mode by rememberSaveable { mutableStateOf(Mode.VIEW) }
    var settings by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) vm.openUris(uris) { if (vm.docs.isNotEmpty()) home = false }
    }
    LaunchedEffect(vm.message) { vm.message?.let { snack.showSnackbar(it); vm.message = null } }
    LaunchedEffect(vm.docs.size) { if (vm.docs.isNotEmpty() && vm.current != null && home && vm.loading) home = false }
    BackHandler(enabled = !home) { home = true }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = {
                    if (home) Text(stringResource(R.string.app_name))
                    else Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        vm.docs.forEach { d ->
                            val sel = d == vm.current
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                                TextButton(onClick = { vm.current = d }) {
                                    Text((if (d.derived != null) "⚗ " else "") + d.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
                                        color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(120.dp))
                                }
                                if (sel) IconButton(onClick = { vm.close(d); if (vm.docs.isEmpty()) home = true }, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.Close, null, Modifier.size(16.dp)) }
                            }
                        }
                    }
                },
                navigationIcon = { if (!home) IconButton(onClick = { home = true }) { Icon(Icons.Filled.Home, tr("Home", "Inicio")) } },
                actions = {
                    IconButton(onClick = { open.launch(arrayOf("*/*")) }) { Icon(Icons.Filled.FileOpen, tr("Open", "Abrir")) }
                    if (vm.docs.isNotEmpty()) IconButton(onClick = { vm.saveProject() }) { Icon(Icons.Filled.Save, tr("Save project", "Guardar proyecto")) }
                    IconButton(onClick = { settings = true }) { Icon(Icons.Filled.Settings, tr("Settings", "Ajustes")) }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (home || vm.current == null) HomeScreen(vm, onOpenFiles = { open.launch(arrayOf("*/*")) }, onEnter = { home = false })
            else BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 600.dp
                if (wide) Row(Modifier.fillMaxSize()) {
                    NavigationRail(Modifier.verticalScrollCompat()) {
                        Mode.entries.forEach { m -> NavigationRailItem(selected = m == mode, onClick = { mode = m }, icon = { Icon(m.icon, null) }, label = { Text(tr(m.en, m.es), fontSize = 10.sp) }) }
                    }
                    Box(Modifier.weight(1f)) { ModeContent(vm, mode) }
                } else Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) { ModeContent(vm, mode) }
                    NavigationBar(Modifier.horizontalScroll(rememberScrollState())) {
                        Mode.entries.forEach { m -> NavigationBarItem(selected = m == mode, onClick = { mode = m }, icon = { Icon(m.icon, null) }, label = { Text(tr(m.en, m.es), fontSize = 9.sp, maxLines = 1) }, modifier = Modifier.width(72.dp)) }
                    }
                }
            }
            if (vm.loading || vm.current?.busy == true) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
    if (settings) SettingsDialog(vm) { settings = false }
}

private fun Modifier.verticalScrollCompat(): Modifier = this

@Composable
fun ModeContent(vm: AppViewModel, mode: Mode) {
    val d = vm.current ?: return
    when (mode) {
        Mode.VIEW -> MeasureScreen(vm, d)
        Mode.PROCESS -> ProcessScreen(vm, d)
        Mode.FFT -> FftScreen(vm, d)
        Mode.IFFT -> IfftScreen(vm, d)
        Mode.PARTICLES -> ParticlesScreen(vm, d)
        Mode.STATS -> StatsScreen(vm, d)
        Mode.ANNOTATE -> AnnotateScreen(vm, d)
        Mode.COMPARE -> CompareScreen(vm)
        Mode.REPORT -> ReportScreen(vm)
    }
}

@Composable
fun SettingsDialog(vm: AppViewModel, onClose: () -> Unit) {
    val tr = LocalTr.current
    AlertDialog(onDismissRequest = onClose, confirmButton = { TextButton(onClick = onClose) { Text("OK") } }, title = { Text(tr("Settings", "Ajustes")) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Theme", "Tema"))
            ChipRow(ThemeMode.entries, vm.themeMode, { when (it) { ThemeMode.SYSTEM -> tr("System", "Sistema"); ThemeMode.LIGHT -> tr("Light", "Claro"); ThemeMode.DARK -> tr("Dark", "Oscuro") } }) { vm.setTheme(it) }
            Text(tr("Language", "Idioma"))
            ChipRow(Lang.entries, vm.lang, { when (it) { Lang.SYSTEM -> tr("System", "Sistema"); Lang.ES -> "Español"; Lang.EN -> "English" } }) { vm.setLanguage(it) }
            Text(tr("All processing is done locally on this device; no data leaves it.", "Todo el procesamiento se realiza localmente en el dispositivo; ningún dato sale de él."), style = MaterialTheme.typography.bodySmall)
        }
    })
}

@Composable
fun HomeScreen(vm: AppViewModel, onOpenFiles: () -> Unit, onEnter: () -> Unit) {
    val tr = LocalTr.current
    var projects by remember { mutableStateOf(vm.listProjects()) }
    var newName by remember { mutableStateOf(vm.projectName) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { vm.importProject(uri); onEnter() } }
    val saver = rememberFileSaver { vm.notify(it) }
    var exportOriginals by remember { mutableStateOf(true) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.titleMedium)
            Text(tr("DM3 · DM4 · TIFF (8/16/32-bit, multipage) · MRC · PNG · JPG · BMP — processed offline on the device.",
                "DM3 · DM4 · TIFF (8/16/32 bits, multipágina) · MRC · PNG · JPG · BMP — procesado sin conexión en el dispositivo."), style = MaterialTheme.typography.bodySmall)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenFiles) { Icon(Icons.Filled.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text(tr("Open micrographs", "Abrir micrografías")) }
                if (vm.docs.isNotEmpty()) OutlinedButton(onClick = onEnter) { Icon(Icons.Filled.Image, null); Spacer(Modifier.width(6.dp)); Text(tr("Workspace (${vm.docs.size})", "Área de trabajo (${vm.docs.size})")) }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("Current project", "Proyecto actual"), style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(newName, { newName = it; vm.projectName = it }, label = { Text(tr("Name", "Nombre")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(vm.projectSample, { vm.projectSample = it }, label = { Text(tr("Sample", "Muestra")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(vm.projectOperator, { vm.projectOperator = it }, label = { Text(tr("Operator", "Operador")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(vm.projectDescription, { vm.projectDescription = it }, label = { Text(tr("Description / acquisition notes", "Descripción / notas de adquisición")) }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        Button(onClick = { vm.saveProject(newName); projects = vm.listProjects() }, enabled = vm.docs.isNotEmpty()) { Icon(Icons.Filled.Save, null); Text(tr(" Save", " Guardar")) }
                        OutlinedButton(onClick = { vm.newProject(); newName = vm.projectName }) { Text(tr("New", "Nuevo")) }
                        OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }) { Icon(Icons.Filled.Upload, null); Text(tr(" Import", " Importar")) }
                        OutlinedButton(onClick = { saver.saveUri("${newName}.temlab.zip", "application/zip") { _, uri -> vm.exportProjectZip(uri, exportOriginals) } }, enabled = vm.docs.isNotEmpty()) { Text(tr("Backup / export", "Copia / exportar")) }
                    }
                    com.temlab.app.ui.SwitchRow(tr("Include copies of original files in the backup", "Incluir copia de los archivos originales en la copia de seguridad"), exportOriginals) { exportOriginals = it }
                }
            }
        }
        item { Text(tr("Saved projects", "Proyectos guardados"), style = MaterialTheme.typography.titleSmall) }
        if (projects.isEmpty()) item { Text(tr("No saved projects yet.", "Aún no hay proyectos guardados."), style = MaterialTheme.typography.bodySmall) }
        items(projects, key = { it.path }) { f -> ProjectRow(f, onOpen = { vm.openProjectFile(f); onEnter() }, onDelete = { f.delete(); projects = vm.listProjects() }) }
        item { Spacer(Modifier.height(8.dp)); Text(tr("Recent files", "Archivos recientes"), style = MaterialTheme.typography.titleSmall) }
        items(vm.recents, key = { it.uri }) { r ->
            Card(Modifier.fillMaxWidth().clickable { vm.openUris(listOf(Uri.parse(r.uri))) { onEnter() } }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Image, null); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) { Text(r.name); Text(DateFormat.getDateTimeInstance().format(Date(r.time)), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (vm.loading) item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
    }
}

@Composable
private fun ProjectRow(f: File, onOpen: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.FolderOpen, null); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) { Text(f.name.removeSuffix(".temlab.json")); Text(DateFormat.getDateTimeInstance().format(Date(f.lastModified())), style = MaterialTheme.typography.bodySmall) }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, null) }
        }
    }
}
