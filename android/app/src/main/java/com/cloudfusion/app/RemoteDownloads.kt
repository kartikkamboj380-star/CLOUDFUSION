package com.cloudfusion.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Separate optional server workspace. Never sends a direct-Drive token to the backend. */
class RemoteDownloadsViewModel(app: Application) : AndroidViewModel(app) {
    private val store = LocalStore(app, "remote_backend_v1")
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(60, TimeUnit.SECONDS).build()
    private var token = ""
    var origin by mutableStateOf(""); private set
    var signedIn by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var accounts by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var tasks by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var preview by mutableStateOf<JSONObject?>(null); private set
    var selected by mutableStateOf(""); private set
    private var previewUrl = ""
    var nextOffset by mutableStateOf<Int?>(null); private set
    var pageOffset by mutableStateOf(0); private set
    var speeds by mutableStateOf<Map<String, Long>>(emptyMap()); private set
    private var samples = emptyMap<String, Pair<Long, Long>>()
    init {
        try {
            val saved = store.read()
            origin = saved.optString("origin")
            selected = saved.optString("default_account")
            token = saved.optString("token")
            signedIn = token.isNotBlank()
        } catch (_: Exception) { message = "Could not read server session. Sign in again." }
    }
    private fun save() = store.write(JSONObject().put("origin", origin).put("token", token).put("default_account", selected))
    private fun run(block: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true; message = null
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "Server request failed" }
            finally { busy = false }
        }
    }
    private suspend fun request(path: String, body: JSONObject? = null, method: String? = null): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(origin + path)
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        if (method == "DELETE") builder.delete()
        else if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        client.newCall(builder.build()).execute().use { response ->
            // API responses are small; cap a misconfigured backend response.
            val output = java.io.ByteArrayOutputStream()
            response.body?.byteStream()?.use { stream ->
                val buffer = ByteArray(8192)
                while (output.size() <= 1024 * 1024) {
                    val count = stream.read(buffer, 0, minOf(buffer.size, 1024 * 1024 + 1 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
            val bytes = output.toByteArray()
            check(bytes.size <= 1024 * 1024) { "Server response too large" }
            val text = bytes.toString(Charsets.UTF_8)
            if (!response.isSuccessful) {
                if (response.code == 401) { token = ""; signedIn = false; save() }
                val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()?.take(500)
                throw IllegalStateException(detail?.takeIf { it.isNotBlank() } ?: "Server error ${response.code}")
            }
            text
        }
    }
    private fun array(text: String): List<JSONObject> = JSONArray(text).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private suspend fun load() {
        accounts = array(request("/api/accounts"))
        if (accounts.none { it.optString("id") == selected }) selected = accounts.firstOrNull()?.optString("id") ?: ""
        val page = JSONObject(request("/api/remote-downloads?limit=50&offset=$pageOffset"))
        val now = android.os.SystemClock.elapsedRealtime()
        val updated = page.getJSONArray("items").objects()
        val newlyCompleted = updated.firstOrNull { t -> t.optString("status") == "completed" && tasks.any { it.optString("id") == t.optString("id") && it.optString("status") in listOf("pending", "in_progress") } }
        if (newlyCompleted != null) message = "Download completed: ${newlyCompleted.optString("name")}"
        speeds = updated.mapNotNull { t ->
            val id = t.getString("id")
            val previous = samples[id]
            if (previous == null || now <= previous.second || t.optString("status") != "in_progress") null
            else id to (((t.optLong("bytes_transferred") - previous.first).coerceAtLeast(0) * 1000) / (now - previous.second))
        }.toMap()
        samples = updated.associate { it.getString("id") to (it.optLong("bytes_transferred") to now) }
        tasks = updated
        nextOffset = if (page.isNull("next_offset")) null else page.getInt("next_offset")
    }
    fun selectAccount(id: String) = run { selected = id; save() }
    fun disconnect(id: String) = run { request("/api/accounts/$id", method = "DELETE"); load(); save() }
    fun refreshQuotas() = run { request("/api/accounts/refresh", JSONObject()); load() }
    fun refresh() = run { load() }
    fun page(offset: Int) = run { pageOffset = offset; load() }
    fun remove(id: String) = run { request("/api/remote-downloads/$id", method = "DELETE"); load() }
    fun login(server: String, email: String, password: String, register: Boolean) = run {
        val uri = Uri.parse(server.trim())
        check(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null && (uri.path.isNullOrEmpty() || uri.path == "/")) { "Use a trusted HTTPS server origin without a path" }
        token = ""; signedIn = false; origin = server.trim().trimEnd('/')
        val result = JSONObject(request("/api/auth/${if (register) "register" else "login"}", JSONObject().put("email", email).put("password", password)))
        token = result.getString("token"); save(); signedIn = true
        load()
    }
    fun logout() = run {
        request("/api/auth/logout", JSONObject())
        token = ""; signedIn = false; accounts = emptyList(); tasks = emptyList(); preview = null; save()
    }
    fun connect(open: (String) -> Unit) = run {
        val url = JSONObject(request("/api/oauth/start", JSONObject())).getString("authorization_url")
        val uri = Uri.parse(url)
        check(uri.scheme == "https" && uri.host == "accounts.google.com") { "Unexpected OAuth destination" }
        open(url)
    }
    fun inspect(url: String) = run {
        preview = null
        val uri = Uri.parse(url)
        check(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()) { "Enter a direct HTTP/HTTPS file URL" }
        preview = JSONObject(request("/api/remote-downloads/preview", JSONObject().put("url", url)))
        previewUrl = url
    }
    fun create(url: String) = run {
        check(preview != null && previewUrl == url) { "Preview this link first" }
        check(selected.isNotBlank()) { "Connect and select a server Drive account first" }
        request("/api/remote-downloads", JSONObject().put("url", url).put("account_id", selected).put("idempotency_key", UUID.randomUUID().toString()))
        preview = null; pageOffset = 0; load()
    }
    fun control(id: String, action: String) = run { request("/api/remote-downloads/$id/$action", JSONObject()); load() }
}

@Composable
fun RemoteDownloadsScreen(onBack: () -> Unit, vm: RemoteDownloadsViewModel = viewModel()) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val lifecycle = LocalLifecycleOwner.current
    var server by remember { mutableStateOf(vm.origin) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var disconnect by remember { mutableStateOf<JSONObject?>(null) }
    disconnect?.let { account ->
        AlertDialog(onDismissRequest = { disconnect = null }, title = { Text("Disconnect server account?") },
            text = { Text("Remove ${account.optString("email")} from the server workspace? Drive files are not deleted and Google's grant is not revoked. Cancel its active/paused downloads first.") },
            confirmButton = { TextButton(onClick = { vm.disconnect(account.getString("id")); disconnect = null }) { Text("Disconnect") } },
            dismissButton = { TextButton(onClick = { disconnect = null }) { Text("Keep account") } })
    }
    LaunchedEffect(vm.signedIn, lifecycle) {
        if (vm.signedIn) lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.refresh()
            while (isActive) {
                delay(15000)
                if (vm.tasks.any { it.optString("status") in listOf("pending", "in_progress") }) vm.refresh()
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick = onBack) { Text("Back to Settings") }
            Text("URL to cloud", style = MaterialTheme.typography.headlineMedium)
            Text("Optional server workspace. Bytes travel from the source through your backend to Drive, never through phone storage. Direct-Drive connections are separate.")
        }
        vm.message?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
        if (vm.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (!vm.signedIn) {
            item {
                Text("A deployed, trusted CloudFusion backend with Google Web OAuth configuration is required. This does not replace direct Android account setup.")
                OutlinedTextField(server, { server = it }, label = { Text("Trusted backend HTTPS origin") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(email, { email = it }, label = { Text("CloudFusion email (not Google password)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(password, { password = it }, label = { Text("CloudFusion password") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row {
                    Button(onClick = { vm.login(server, email, password, false); password = "" }, enabled = !vm.busy) { Text("Sign in") }
                    TextButton(onClick = { vm.login(server, email, password, true); password = "" }, enabled = !vm.busy) { Text("Register") }
                }
            }
        } else {
            item {
                Text(vm.origin)
                Row {
                    TextButton(onClick = { vm.refresh() }, enabled = !vm.busy) { Text("Refresh") }
                    TextButton(onClick = { vm.refreshQuotas() }, enabled = !vm.busy) { Text("Quotas") }
                    TextButton(onClick = { vm.logout() }, enabled = !vm.busy) { Text("Sign out") }
                }
                Button(onClick = { vm.connect { link -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) } }, enabled = !vm.busy) { Text("Connect server Google account") }
                Text("Finish consent in your browser, return here and tap Refresh.")
                Text("Destination account")
            }
            items(vm.accounts, key = { "account-" + it.getString("id") }) { account ->
                TextButton(onClick = { vm.selectAccount(account.getString("id")) }, enabled = !vm.busy) {
                    Text((if (vm.selected == account.getString("id")) "✓ Default: " else "") + account.optString("email"))
                }
                Text("Used: ${formatBytes(account.longOrNull("used_bytes"))} · Free: ${if (account.optBoolean("unlimited")) "Unlimited" else formatBytes(account.longOrNull("free_bytes"))}")
                if (account.optBoolean("reauthorization_required")) Text("Reconnect using Connect server Google account above.", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { disconnect = account }, enabled = !vm.busy) { Text("Disconnect server account") }
            }
            item {
                OutlinedTextField(url, { url = it }, label = { Text("Paste direct download link") }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { url = clipboard.getText()?.text ?: "" }) { Text("Paste from clipboard") }
                Button(onClick = { vm.inspect(url) }, enabled = !vm.busy && url.isNotBlank()) { Text("Preview file") }
                vm.preview?.let { info ->
                    Text("${info.optString("name")} · ${info.optLong("size")} bytes · ${info.optString("mime_type")}")
                    Text(if (info.optBoolean("resume_possible")) "Source advertises resume; verified when resuming." else "Source may not support safe resume.")
                    Button(onClick = { vm.create(url) }, enabled = !vm.busy && vm.selected.isNotBlank()) { Text("Download to Cloud") }
                }
                Text("Download Manager · 50 transfers per page. Refresh for updates; active tasks poll every 15 seconds only while this screen is visible. Transfers continue on the server.")
            }
            items(vm.tasks, key = { it.getString("id") }) { task ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(task.optString("name"), style = MaterialTheme.typography.titleMedium)
                        val done = task.optLong("bytes_transferred")
                        val total = task.optLong("size")
                        Text("${task.optString("status")} · $done / $total bytes")
                        vm.speeds[task.getString("id")]?.takeIf { it > 0 }?.let { speed ->
                            Text("$speed bytes/s · about ${(total - done).coerceAtLeast(0) / speed}s remaining (sampled Drive acknowledgements)")
                        }
                        if (total > 0) LinearProgressIndicator(progress = { (done.toDouble() / total).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text("Destination: " + (vm.accounts.firstOrNull { it.optString("id") == task.optString("destination_account_id") }?.optString("email") ?: "Disconnected"))
                        if (!task.isNull("error")) Text(task.optString("error"), color = MaterialTheme.colorScheme.error)
                        Row {
                            val state = task.optString("status")
                            if (state in listOf("completed", "cancelled")) TextButton(onClick = { vm.remove(task.getString("id")) }, enabled = !vm.busy) { Text("Remove history") }
                            if (state in listOf("pending", "in_progress")) TextButton(onClick = { vm.control(task.getString("id"), "pause") }, enabled = !vm.busy) { Text("Pause") }
                            if (state in listOf("paused", "failed")) TextButton(onClick = { vm.control(task.getString("id"), "resume") }, enabled = !vm.busy) { Text("Resume") }
                            if (state in listOf("pending", "in_progress", "paused", "failed")) TextButton(onClick = { vm.control(task.getString("id"), "cancel") }, enabled = !vm.busy) { Text("Cancel") }
                        }
                    }
                }
            }
            item {
                Row {
                    if (vm.pageOffset > 0) TextButton(onClick = { vm.page(0) }, enabled = !vm.busy) { Text("First page") }
                    vm.nextOffset?.let { offset -> TextButton(onClick = { vm.page(offset) }, enabled = !vm.busy) { Text("Next page") } }
                }
            }
        }
    }
}
