package com.cloudfusion.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp

@Composable
fun DirectDownloadScreen(vm: CloudViewModel, onBack: () -> Unit, onAccounts: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var url by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(vm.accounts.firstOrNull()?.optString("id") ?: "") }
    var connections by remember { mutableIntStateOf(1) }
    var buffer by remember { mutableIntStateOf(1024 * 1024) }
    var historyLimit by remember { mutableIntStateOf(20) }
    LaunchedEffect(vm.accounts) {
        if (vm.accounts.none { it.optString("id") == selected }) selected = vm.accounts.firstOrNull()?.optString("id") ?: ""
    }
    val history = vm.transfers.filter { it.optString("kind") == "url" }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Direct Download", style = MaterialTheme.typography.headlineMedium)
            Text("URL → connected Google Drive. No CloudFusion server or workspace login. Data uses this phone's download/upload network, not a full local file.")
        }
        item {
            OutlinedTextField(url, { url = it }, label = { Text("Paste direct HTTPS file URL") }, modifier = Modifier.fillMaxWidth())
            Row {
                TextButton(onClick = { url = clipboard.getText()?.text ?: "" }) { Text("Paste from clipboard") }
                Button(onClick = { vm.inspectUrl(url) }, enabled = !vm.busy && url.isNotBlank()) { Text("Preview") }
            }
            Text("Public HTTPS direct files only. Web pages, DRM, login-required and unknown-size streams are not supported.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Text("Destination · existing Google accounts", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onAccounts) { Text("Manage / reconnect Google accounts") }
        }
        items(vm.accounts, key = { "account-" + it.optString("id") }) { account ->
            Row {
                RadioButton(selected = selected == account.optString("id"), onClick = { selected = account.optString("id") })
                Column {
                    TextButton(onClick = { selected = account.optString("id") }) { Text(account.optString("email")) }
                    Text("Free: ${if (account.optBoolean("unlimited")) "Unlimited" else formatBytes(account.longOrNull("free_bytes"))}")
                    if (account.optBoolean("reauthorization_required")) Text("Reconnect in Accounts", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        item {
            Text("Source connections: $connections", style = MaterialTheme.typography.titleMedium)
            Row {
                TextButton(onClick = { connections = 1 }) { Text("Normal · 1") }
                TextButton(onClick = { connections = 4 }) { Text("Fast · 4") }
            }
            Row {
                TextButton(onClick = { connections = 8 }) { Text("Advanced · 8") }
                TextButton(onClick = { connections = 50 }) { Text("Extreme · 50") }
            }
            Slider(value = connections.toFloat(), onValueChange = { connections = it.toInt().coerceIn(1, 50) }, valueRange = 1f..50f, steps = 48)
            Text("Parallel ranges require a stable strong ETag + correct Range responses. Otherwise one sequential connection is used. 50 is not 50× speed; it may trigger rate limits and uses more battery/memory.", style = MaterialTheme.typography.bodySmall)
            Text("Buffer: ${formatBytes(buffer.toLong())}")
            Row {
                listOf(256 * 1024, 1024 * 1024, 4 * 1024 * 1024).forEach { size ->
                    TextButton(onClick = { buffer = size }) { Text((if (buffer == size) "✓ " else "") + formatBytes(size.toLong())) }
                }
            }
            Text("Use 256 KiB and 1 connection on low-memory phones. Each block is uploaded before downloading the next.", style = MaterialTheme.typography.bodySmall)
        }
        vm.urlPreview?.takeIf { it.url == url.trim() }?.let { source ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(source.name, style = MaterialTheme.typography.titleMedium)
                        Text("${formatBytes(source.size)} · ${source.mime}")
                        Text(if (source.resumable) "Range + strong ETag detected. Resume will verify both ends." else "Single streaming connection. After acknowledged bytes, safe resume is unavailable; inspect Drive before restarting.")
                        Button(onClick = { vm.startUrlTransfer(url, selected, connections, buffer) }, enabled = !vm.busy && selected.isNotBlank()) { Text("Start Transfer") }
                    }
                }
            }
        }
        vm.transferProgress?.let { progress -> item { Text(progress, color = MaterialTheme.colorScheme.primary) } }
        vm.urlRates?.let { rates -> item { Text(rates, style = MaterialTheme.typography.bodySmall) } }
        item { Text("URL transfer history", style = MaterialTheme.typography.titleLarge) }
        if (history.isEmpty()) item { Text("No URL transfers yet.") }
        items(history.take(historyLimit), key = { it.optString("id") }) { t ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(t.optString("name"), style = MaterialTheme.typography.titleMedium)
                    Text("To: ${t.optString("destination_account_id")}")
                    val done = t.optLong("bytes_transferred")
                    val total = t.optLong("size")
                    Text("${t.optString("status")} · ${formatBytes(done)} / ${formatBytes(total)}")
                    LinearProgressIndicator(progress = { if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
                    if (t.optBoolean("checksum_verified")) Text("${t.optString("checksum_algorithm", "MD5")} verified")
                    t.text("error")?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row {
                        val status = t.optString("status")
                        if (status in listOf("pending", "in_progress")) TextButton(onClick = { vm.pauseUrlTransfer(t.getString("id")) }) { Text("Pause") }
                        if (status in listOf("paused", "failed", "interrupted") && !t.optBoolean("resume_blocked")) TextButton(onClick = { vm.retryTransfer(t) }) { Text("Resume / retry") }
                        if (status in listOf("pending", "in_progress", "paused", "failed", "interrupted")) TextButton(onClick = { vm.cancelTransfer(t.getString("id")) }) { Text("Cancel") }
                    }
                }
            }
        }
        if (history.size > historyLimit) item { TextButton(onClick = { historyLimit += 20 }) { Text("Load more history") } }
        item { Text("Keep CloudFusion open and online. Android may terminate it in the background; reopen and resume. Pause/cancel may wait for in-flight requests. Sessions expire; inspect Drive before replacing uncertain transfers.", style = MaterialTheme.typography.bodySmall) }
    }
}
