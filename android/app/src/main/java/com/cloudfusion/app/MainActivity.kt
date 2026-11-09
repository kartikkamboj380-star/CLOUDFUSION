package com.cloudfusion.app

import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.common.AccountPicker
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

private val Ink = Color(0xFF090C15)
private val Surface = Color(0xFF151A28)
private val Lavender = Color(0xFFB9A8FF)
private val Mint = Color(0xFF79E2CE)
private val Muted = Color(0xFFA4A9BE)
private val DarkColors = darkColorScheme(
    primary = Lavender, onPrimary = Color(0xFF211649), secondary = Mint,
    background = Ink, surface = Surface, surfaceVariant = Color(0xFF202637),
    onSurface = Color(0xFFF3F2FF), onSurfaceVariant = Muted, outline = Color(0xFF42495F)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = DarkColors) { CloudFusionApp() } }
    }
}

private data class Destination(val label: String, val icon: ImageVector)
private val destinations = listOf(
    Destination("Home", Icons.Default.Home), Destination("Files", Icons.Default.Folder),
    Destination("Transfers", Icons.Default.SwapHoriz), Destination("Accounts", Icons.Default.AccountCircle),
    Destination("Settings", Icons.Default.Settings)
)

@Composable
fun CloudFusionApp(vm: CloudViewModel = viewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val authorization = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.finishAuthorization(result.data) else vm.authorizationCancelled()
    }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val email = if (result.resultCode == Activity.RESULT_OK) result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME) else null
        if (!email.isNullOrBlank()) vm.connectAccount(email)
        else if (result.resultCode == Activity.RESULT_OK) vm.showError("Google did not return an account. Try again.")
    }
    val chooseAccount: () -> Unit = {
        try {
            val options = AccountPicker.AccountChooserOptions.Builder()
                .setAllowableAccountsTypes(listOf("com.google")).setAlwaysShowAccountPicker(true).build()
            chooser.launch(AccountPicker.newChooseAccountIntent(options))
        } catch (_: Exception) { vm.showError("Could not open the Google account picker. Check Google Play services.") }
    }
    LaunchedEffect(vm.authorizationResolution) {
        vm.consumeAuthorizationResolution()?.let { pending ->
            try { authorization.launch(IntentSenderRequest.Builder(pending.intentSender).build()) }
            catch (_: Exception) { vm.authorizationCancelled(); vm.showError("Could not open Google's consent screen. Try again.") }
        }
    }
    LaunchedEffect(Unit) { vm.refreshAll() }
    LaunchedEffect(vm.error, vm.notice) {
        val message = vm.error ?: vm.notice
        if (message != null) { snackbar.showSnackbar(message); vm.clearMessages() }
    }
    Scaffold(
        containerColor = Ink,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF111521), tonalElevation = 0.dp) {
                destinations.forEachIndexed { index, destination ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label, maxLines = 1, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0xFF302A4A), selectedIconColor = Lavender))
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> HomeScreen(vm, onAccounts = { tab = 3 }, onFiles = { tab = 1 }, onTransfers = { tab = 2 })
                1 -> FilesScreen(vm)
                2 -> TransfersScreen(vm)
                3 -> AccountsScreen(vm, chooseAccount)
                4 -> SettingsScreen()
            }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter), color = Mint)
        }
    }
}

@Composable
private fun BrandMark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Lavender), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Cloud, contentDescription = null, tint = Color(0xFF251B4E))
        }
        Spacer(Modifier.width(10.dp))
        Text("CLOUDFUSION", letterSpacing = 2.sp, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
    }
}

@Composable
private fun ScreenHeader(title: String, subtitle: String, action: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
        action?.invoke()
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(Surface).padding(18.dp), content = content)
}

@Composable
private fun HomeScreen(vm: CloudViewModel, onAccounts: () -> Unit, onFiles: () -> Unit, onTransfers: () -> Unit) {
    val dash = vm.dashboard
    val used = dash?.longOrNull("used_bytes")
    val capacity = dash?.longOrNull("capacity_bytes")
    val utilization = if (dash == null || dash.isNull("utilization_percent")) null else dash.optDouble("utilization_percent")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                BrandMark()
                IconButton(onClick = { vm.refreshAccounts(true) }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh storage quotas") }
            }
        }
        item { ScreenHeader("Your space, unified.", "One view. Your accounts remain separate.") }
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF373052), Color(0xFF1C2535)))) .padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudQueue, contentDescription = null, tint = Mint)
                    Spacer(Modifier.width(8.dp))
                    Text("TOTAL STORAGE", letterSpacing = 1.4.sp, color = Color(0xFFCCCAE0), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(20.dp))
                Text(formatBytes(used), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(if (capacity != null) "of ${formatBytes(capacity)} used" else "Total capacity unavailable or unlimited", color = Color(0xFFC9C9DA))
                Spacer(Modifier.height(18.dp))
                LinearProgressIndicator(progress = { ((utilization ?: 0.0) / 100).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape), color = Mint, trackColor = Color(0xFF4D5266))
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (utilization == null) "Usage unavailable" else "${"%.1f".format(utilization)}% used", color = Color(0xFFD8D8E5), fontSize = 12.sp)
                    Text("${formatBytes(dash?.longOrNull("remaining_bytes"))} free", color = Color(0xFFD8D8E5), fontSize = 12.sp)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("ACCOUNTS", dash?.optInt("connected_accounts")?.toString() ?: "—", Icons.Default.AccountCircle, Modifier.weight(1f), onAccounts)
                StatCard("ACTIVE", dash?.optInt("active_transfers")?.toString() ?: "—", Icons.Default.SwapHoriz, Modifier.weight(1f), onTransfers)
                StatCard("FAILED", dash?.optInt("failed_transfers")?.toString() ?: "—", Icons.Default.ErrorOutline, Modifier.weight(1f), onTransfers)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Connected accounts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = onAccounts) { Text("Manage") }
            }
        }
        if (vm.accounts.isEmpty()) item { EmptyPanel("No accounts connected", "Connect Google Drive to see your actual storage.", "Connect account", onAccounts) }
        else items(vm.accounts.take(4), key = { it.optString("id") }) { AccountSummary(it) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Recent files", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = onFiles) { Text("View all") }
            }
        }
        val recent = dash?.optJSONArray("recent_files")?.objects() ?: emptyList()
        if (recent.isEmpty()) item { Panel(Modifier.fillMaxWidth()) { Text("No files loaded yet", fontWeight = FontWeight.Medium); Text("Refresh files to load them from Google Drive.", color = Muted, fontSize = 13.sp) } }
        else items(recent, key = { it.optString("account_id") + it.optString("id") }) { FileSummary(it) }
        item {
            val history = dash?.optJSONArray("recent_transfers")?.objects() ?: emptyList()
            Text("Recent transfers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            if (history.isEmpty()) Text("No uploads or transfers yet.", color = Muted, fontSize = 13.sp)
            else history.take(3).forEach { t ->
                Text("${t.optString("name")} · ${t.optString("status").replace('_', ' ')}", color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Panel(modifier.clickable(onClick = onClick)) {
        Icon(icon, contentDescription = null, tint = Lavender, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(12.dp))
        Text(value, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text(label, fontSize = 9.sp, letterSpacing = 0.8.sp, color = Muted, maxLines = 1)
    }
}

@Composable
private fun AccountSummary(account: JSONObject) {
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(Color(0xFF302B48)), contentAlignment = Alignment.Center) {
                Text(account.optString("email").take(1).uppercase(), color = Lavender, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(account.optString("email"), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(if (account.optBoolean("unlimited")) "Unlimited quota" else "${formatBytes(account.longOrNull("free_bytes"))} free", color = Muted, fontSize = 12.sp)
            }
            if (account.optBoolean("reauthorization_required")) Text("Reconnect", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
        val used = account.longOrNull("used_bytes"); val limit = account.longOrNull("limit_bytes")
        if (used != null && limit != null && limit > 0) {
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(progress = { (used.toFloat() / limit).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape), color = Lavender, trackColor = Color(0xFF353B4F))
            Spacer(Modifier.height(6.dp))
            Text("${formatBytes(used)} of ${formatBytes(limit)} used", color = Muted, fontSize = 11.sp)
        }
        account.text("quota_error")?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
    }
}

@Composable
private fun FileSummary(file: JSONObject) {
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF292A43)), contentAlignment = Alignment.Center) {
                Icon(if (file.optString("mime_type").contains("folder")) Icons.Default.Folder else Icons.Default.InsertDriveFile, contentDescription = null, tint = Lavender)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(file.optString("name"), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${file.optString("account_email")} · ${formatBytes(file.longOrNull("size"))}", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun EmptyPanel(title: String, description: String, action: String? = null, onClick: () -> Unit = {}) {
    Panel(Modifier.fillMaxWidth()) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(description, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        if (action != null) TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp)) { Text(action) }
    }
}

@Composable
private fun AccountsScreen(vm: CloudViewModel, chooseAccount: () -> Unit) {
    var disconnect by remember { mutableStateOf<JSONObject?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenHeader("Accounts", "Every Drive stays independent.") { IconButton(onClick = { vm.refreshAccounts(true) }) { Icon(Icons.Default.Refresh, "Refresh accounts") } } }
        item {
            Button(onClick = chooseAccount, enabled = !vm.authInProgress,
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Default.Add, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Connect Google account", fontWeight = FontWeight.Bold)
            }
        }
        item { Text("Choose a Google account on this device. Google will ask for permission to manage its Drive files. No separate CloudFusion login or server is needed. Google Cloud OAuth setup is required; see Settings.", color = Muted, fontSize = 13.sp) }
        if (vm.accounts.isEmpty()) item { EmptyPanel("No accounts connected", "Add an account to see its quota and files.") }
        items(vm.accounts, key = { it.optString("id") }) { account ->
            Column {
                AccountSummary(account)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { vm.connectAccount(account.getString("id")) }, enabled = !vm.authInProgress) { Text("Reconnect") }
                    TextButton(onClick = { disconnect = account }) { Text("Disconnect") }
                }
            }
        }
        item { Text("Disconnecting removes the local account and stops its resumable transfers; it does not delete files or revoke the Google grant. Revoke it in Google Account permissions if desired.", color = Muted, fontSize = 12.sp) }
    }
    disconnect?.let { account ->
        AlertDialog(onDismissRequest = { disconnect = null }, title = { Text("Disconnect account?") },
            text = { Text("${account.optString("email")} will be removed from this device. Unfinished transfers using it cannot be resumed. Your files remain in Google Drive.") },
            confirmButton = { TextButton(onClick = { vm.disconnect(account.getString("id")); disconnect = null }) { Text("Disconnect") } },
            dismissButton = { TextButton(onClick = { disconnect = null }) { Text("Keep account") } })
    }
}

@Suppress("DEPRECATION")
private fun installedCertificateSha1(context: Context): String? = try {
    val info = if (Build.VERSION.SDK_INT >= 28) context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        else context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
    val signature = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.firstOrNull()
        else info.signatures?.firstOrNull()
    signature?.toByteArray()?.let { certificate ->
        MessageDigest.getInstance("SHA-1").digest(certificate).joinToString(":") { "%02X".format(Locale.US, it) }
    }
} catch (_: Exception) { null }

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val fingerprint = remember(context) { installedCertificateSha1(context) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenHeader("Settings", "Your CloudFusion workspace")
        Panel(Modifier.fillMaxWidth()) {
            Text("Direct to Google Drive", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("This Android app talks directly to Google. There is no backend URL or separate CloudFusion account.", color = Muted, lineHeight = 21.sp)
        }
        Panel(Modifier.fillMaxWidth()) {
            Text("Google developer setup", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("The Google Cloud project owner must enable Drive API, add test users and register this package and APK certificate as an Android OAuth client.", color = Muted, lineHeight = 21.sp)
            Spacer(Modifier.height(10.dp))
            Text("Package: ${context.packageName}", color = Muted, fontSize = 12.sp)
            Text("SHA-1: ${fingerprint ?: "Unavailable"}", color = Muted, fontSize = 12.sp)
            if (fingerprint != null) TextButton(onClick = { clipboard.setText(AnnotatedString(fingerprint)) }) { Text("Copy SHA-1") }
        }
        Panel(Modifier.fillMaxWidth()) {
            Text("About your storage", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("CloudFusion shows a virtual view; it does not merge Google accounts or pool their storage. Organization quotas may be shared, so summing them can count the same pool more than once.", color = Muted, lineHeight = 21.sp)
        }
        Panel(Modifier.fillMaxWidth()) {
            Text("Transfers & privacy", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Copies travel through this phone and use download and upload data, battery and time. Keep the app open and online. Incomplete downloads may leave a partial file.", color = Muted, lineHeight = 21.sp)
            Spacer(Modifier.height(10.dp))
            Text("Local account and transfer state is encrypted. Google handles authorization; this app never asks for your Google password.", color = Muted, lineHeight = 21.sp)
        }
    }
}

@Composable
private fun FilesScreen(vm: CloudViewModel) {
    var renameFile by remember { mutableStateOf<JSONObject?>(null) }
    var moveFile by remember { mutableStateOf<JSONObject?>(null) }
    var trashFile by remember { mutableStateOf<JSONObject?>(null) }
    var copyFile by remember { mutableStateOf<JSONObject?>(null) }
    var shareFile by remember { mutableStateOf<JSONObject?>(null) }
    var shareData by remember { mutableStateOf<JSONObject?>(null) }
    var confirmPublic by remember { mutableStateOf(false) }
    var createFolder by remember { mutableStateOf(false) }
    var uploadMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var downloadFile by remember { mutableStateOf<JSONObject?>(null) }
    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.prepareUpload(uri) }
    val downloadPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null) downloadFile?.let { vm.download(it, uri) }
        downloadFile = null
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenHeader("Your files", "Search across connected Drives.") { IconButton(onClick = { vm.loadFiles(sync = true) }) { Icon(Icons.Default.Refresh, "Refresh files from Google Drive") } } }
        item {
            OutlinedTextField(vm.search, { vm.search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Search file names") }, leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = { IconButton(onClick = { vm.loadFiles() }) { Icon(Icons.Default.ArrowForward, "Search") } }, shape = RoundedCornerShape(16.dp))
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { uploadPicker.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.Upload, contentDescription = null); Spacer(Modifier.width(5.dp)); Text("Upload")
                }
                OutlinedButton(onClick = { createFolder = true }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), enabled = vm.accounts.isNotEmpty()) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = null); Spacer(Modifier.width(5.dp)); Text("Folder")
                }
            }
            Box {
                TextButton(onClick = { uploadMenu = true }) {
                    val selected = vm.accounts.firstOrNull { it.optString("id") == vm.uploadAccountId }
                    Text(if (selected == null) "Upload destination: Auto allocate ▾" else "Upload to: ${selected.optString("email")} ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = uploadMenu, onDismissRequest = { uploadMenu = false }) {
                    DropdownMenuItem(text = { Text("Auto allocate") }, onClick = { vm.uploadAccountId = null; uploadMenu = false })
                    vm.accounts.forEach { account -> DropdownMenuItem(text = { Text(account.optString("email")) }, onClick = { vm.uploadAccountId = account.getString("id"); uploadMenu = false }) }
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("all" to "All", "images" to "Images", "documents" to "Documents", "videos" to "Video", "audio" to "Audio", "folders" to "Folders")) { (value, label) ->
                    FilterChip(selected = vm.fileType == value, onClick = { vm.fileType = value; vm.loadFiles() }, label = { Text(label) })
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${vm.files.size} loaded", color = Muted, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        TextButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("Sort: ${vm.sort}") }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            listOf("name", "size", "modified").forEach { value -> DropdownMenuItem(text = { Text(value.replaceFirstChar { it.uppercase() }) }, onClick = { vm.sort = value; sortMenu = false; vm.loadFiles() }) }
                        }
                    }
                    IconButton(onClick = { vm.direction = if (vm.direction == "asc") "desc" else "asc"; vm.loadFiles() }) {
                        Icon(if (vm.direction == "asc") Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, "Change sort direction")
                    }
                }
            }
        }
        if (vm.files.isEmpty()) item { EmptyPanel("Nothing here yet", "Connect an account, then refresh files. Search and filters only show matching files.", "Refresh files") { vm.loadFiles(sync = true) } }
        items(vm.files, key = { it.optString("account_id") + ":" + it.optString("id") }) { file ->
            ManagedFileCard(file,
                onRename = { renameFile = file }, onMove = { moveFile = file }, onTrash = { trashFile = file },
                onCopy = { copyFile = file }, onShare = { vm.getSharing(file) { shareFile = file; shareData = it } },
                onDownload = {
                    if (isWorkspaceNative(file.optString("mime_type"))) vm.showError("Folders and Google Workspace documents cannot be downloaded as binary files.")
                    else { downloadFile = file; downloadPicker.launch(file.optString("name", "download")) }
                })
        }
        if (vm.nextOffset != null) item { OutlinedButton(onClick = { vm.loadFiles(more = true) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Load more") } }
        item { Text("Search runs on Google Drive. Sort applies to loaded pages; load more to include further results.", color = Muted, fontSize = 11.sp) }
        vm.transferProgress?.let { progress -> item { Panel(Modifier.fillMaxWidth()) { Text(progress, fontSize = 13.sp); Text("Manage or cancel in Transfers", color = Muted, fontSize = 12.sp) } } }
    }
    vm.pendingUpload?.let { pending ->
        val email = vm.accounts.firstOrNull { it.optString("id") == pending.transfer.optString("destination_account_id") }?.optString("email") ?: "Selected account"
        AlertDialog(onDismissRequest = { vm.cancelPendingUpload() }, title = { Text("Ready to upload?") },
            text = { Text("${pending.document.name}\n${formatBytes(pending.document.size)}\n\nDestination: $email\n\nThe phone reads the file to verify it, then uploads directly to Google Drive. Keep the app open and online; internet data and battery are used. The file is not split.") },
            confirmButton = { TextButton(onClick = { vm.confirmUpload() }) { Text("Upload") } },
            dismissButton = { TextButton(onClick = { vm.cancelPendingUpload() }) { Text("Cancel") } })
    }
    renameFile?.let { file -> InputDialog("Rename file", "Name", file.optString("name"), { renameFile = null }) { value -> vm.rename(file, value); renameFile = null } }
    moveFile?.let { file -> InputDialog("Move within this Drive", "Destination folder ID (or root)", "", { moveFile = null }) { value -> vm.move(file, value); moveFile = null } }
    trashFile?.let { file ->
        AlertDialog(onDismissRequest = { trashFile = null }, title = { Text("Move to trash?") },
            text = { Text("${file.optString("name")} will be moved to this account's Google Drive trash.") },
            confirmButton = { TextButton(onClick = { vm.trash(file); trashFile = null }) { Text("Move to trash") } },
            dismissButton = { TextButton(onClick = { trashFile = null }) { Text("Cancel") } })
    }
    copyFile?.let { file ->
        val choices = vm.accounts.filter { it.optString("id") != file.optString("account_id") }
        AlertDialog(onDismissRequest = { copyFile = null }, title = { Text("Copy to another account") },
            text = {
                Column {
                    Text("The file is downloaded and uploaded through this phone, using internet data and battery. Keep the app open and online. Choose a destination to start:", color = Muted)
                    Spacer(Modifier.height(8.dp))
                    if (choices.isEmpty()) Text("Connect another account first.")
                    choices.forEach { account ->
                        Text(account.optString("email"), modifier = Modifier.fillMaxWidth().clickable { vm.copy(file, account.getString("id")); copyFile = null }.padding(vertical = 12.dp), color = Lavender)
                    }
                }
            }, confirmButton = { TextButton(onClick = { copyFile = null }) { Text("Close") } })
    }
    if (createFolder) FolderDialog(vm.accounts, onDismiss = { createFolder = false }) { account, name, parent -> vm.createFolder(account, name, parent); createFolder = false }
    shareFile?.let { file ->
        val data = shareData
        val clipboard = LocalClipboardManager.current
        AlertDialog(onDismissRequest = { shareFile = null; shareData = null }, title = { Text("Sharing & permissions") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(file.optString("name"), fontWeight = FontWeight.SemiBold)
                    Text("Account: ${file.optString("account_email")}", color = Muted, fontSize = 12.sp)
                    Text("Owner: ${file.text("owner_email") ?: "Not available"}", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(14.dp))
                    val permissions = data?.optJSONArray("permissions")?.objects() ?: emptyList()
                    if (permissions.isEmpty()) Text("No permissions returned by Google.", color = Muted)
                    permissions.forEach { Text("${it.optString("type")} · ${it.optString("role")} ${it.text("emailAddress") ?: ""}", fontSize = 13.sp, modifier = Modifier.padding(vertical = 3.dp)) }
                    Spacer(Modifier.height(10.dp))
                    val link = data?.optJSONObject("file")?.text("webViewLink") ?: file.text("web_view_link")
                    if (link != null) TextButton(onClick = { clipboard.setText(AnnotatedString(link)) }) { Text("Copy Google Drive link") }
                    Text("A link only works for people with permission unless public access is enabled.", color = Muted, fontSize = 12.sp)
                }
            },
            confirmButton = {
                Row {
                    val isPublic = data?.optJSONArray("permissions")?.objects()?.any { it.optString("type") == "anyone" } == true
                    if (isPublic) TextButton(onClick = { vm.changeSharing(file, false) { shareData = it } }) { Text("Revoke public") }
                    else TextButton(onClick = { confirmPublic = true }) { Text("Make public") }
                    TextButton(onClick = { shareFile = null; shareData = null }) { Text("Close") }
                }
            })
        if (confirmPublic) AlertDialog(onDismissRequest = { confirmPublic = false }, title = { Text("Make this file public?") },
            text = { Text("Anyone with the link may be able to view this file. Only continue if you intend to share it publicly.") },
            confirmButton = { TextButton(onClick = { vm.changeSharing(file, true) { shareData = it }; confirmPublic = false }) { Text("Make public") } },
            dismissButton = { TextButton(onClick = { confirmPublic = false }) { Text("Cancel") } })
    }
}

@Composable
private fun ManagedFileCard(file: JSONObject, onRename: () -> Unit, onMove: () -> Unit, onTrash: () -> Unit,
                            onCopy: () -> Unit, onShare: () -> Unit, onDownload: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF292A43)), contentAlignment = Alignment.Center) {
                Icon(if (file.optString("mime_type").contains("folder")) Icons.Default.Folder else Icons.Default.InsertDriveFile, contentDescription = null, tint = Lavender)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(file.optString("name"), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${file.optString("account_email")} · ${formatBytes(file.longOrNull("size"))}", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                file.text("owner_email")?.let { Text("Owner: $it", color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Box {
                IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, "File actions") }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    listOf("Download" to onDownload, "Rename" to onRename, "Move" to onMove, "Copy to account" to onCopy,
                        "Sharing & permissions" to onShare, "Move to trash" to onTrash).forEach { (label, action) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; action() })
                    }
                }
            }
        }
    }
}

@Composable
private fun InputDialog(title: String, label: String, initial: String, dismiss: () -> Unit, confirm: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { confirm(value.trim()) }, enabled = value.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun FolderDialog(accounts: List<JSONObject>, onDismiss: () -> Unit, onCreate: (String, String, String) -> Unit) {
    var selected by remember { mutableStateOf(accounts.firstOrNull()?.optString("id") ?: "") }
    var name by remember { mutableStateOf("") }
    var parent by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Create folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    TextButton(onClick = { expanded = true }) { Text(accounts.firstOrNull { it.optString("id") == selected }?.optString("email") ?: "Choose account") }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        accounts.forEach { account -> DropdownMenuItem(text = { Text(account.optString("email")) }, onClick = { selected = account.optString("id"); expanded = false }) }
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Folder name") }, singleLine = true)
                OutlinedTextField(parent, { parent = it }, label = { Text("Parent folder ID (optional)") }, singleLine = true)
            }
        }, confirmButton = { TextButton(onClick = { onCreate(selected, name.trim(), parent.trim()) }, enabled = selected.isNotBlank() && name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun TransfersScreen(vm: CloudViewModel) {
    var resume by remember { mutableStateOf<JSONObject?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) resume?.let { vm.resumeUpload(it, uri) }
        resume = null
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenHeader("Transfers", "On-device uploads, downloads & copies") }
        vm.transferProgress?.let { progress -> item { Panel(Modifier.fillMaxWidth()) { Text(progress, color = Mint, fontSize = 13.sp) } } }
        if (vm.transfers.isEmpty()) item { EmptyPanel("No transfers yet", "Your transfer activity will appear here.") }
        items(vm.transfers, key = { it.optString("id") }) { t ->
            Panel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(when (t.optString("kind")) { "upload" -> Icons.Default.Upload; "download" -> Icons.Default.Download; else -> Icons.Default.SwapHoriz }, contentDescription = null, tint = Lavender)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.optString("name"), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${t.optString("kind").replaceFirstChar { it.uppercase() }} · ${t.optString("status").replace('_', ' ')}", color = Muted, fontSize = 12.sp)
                    }
                }
                val size = t.longOrNull("size"); val transferred = t.optLong("bytes_transferred")
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress = { if (size != null && size > 0) (transferred.toFloat() / size).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape), color = Mint, trackColor = Color(0xFF353B4F))
                Text("${formatBytes(transferred)} / ${formatBytes(size)}", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                t.text("error")?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
                if (t.optBoolean("checksum_verified")) Text("Checksum verified", color = Mint, fontSize = 12.sp)
                val status = t.optString("status"); val kind = t.optString("kind")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (status in listOf("pending", "hashing", "in_progress", "failed", "interrupted")) TextButton(onClick = { vm.cancelTransfer(t.getString("id")) }) { Text("Cancel") }
                    if (kind == "upload" && status in listOf("failed", "interrupted") && !t.optBoolean("resume_blocked")) TextButton(onClick = { resume = t; picker.launch(arrayOf("*/*")) }) { Text("Resume with file") }
                    if (kind == "copy" && status in listOf("failed", "interrupted") && !t.optBoolean("resume_blocked")) TextButton(onClick = { vm.retryTransfer(t) }) { Text("Resume copy") }
                }
            }
        }
        item { Text("Transfers run only while this app stays open and online. Copies use both download and upload data on this phone. Sessions can expire; if an outcome is unclear, check the destination Drive before starting again. A partial local download may remain after cancellation.", color = Muted, fontSize = 12.sp) }
    }
}
