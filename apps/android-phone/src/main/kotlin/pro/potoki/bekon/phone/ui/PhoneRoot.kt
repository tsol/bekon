package pro.potoki.bekon.phone.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CallMade
import androidx.compose.material.icons.outlined.CallMissed
import androidx.compose.material.icons.outlined.CallReceived
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import pro.potoki.bekon.call.CallProtocol
import pro.potoki.bekon.phone.CallPrefs
import pro.potoki.bekon.phone.CallService
import pro.potoki.bekon.phone.ChannelLogLine
import pro.potoki.bekon.phone.ContactHit
import pro.potoki.bekon.phone.PhoneApp
import pro.potoki.bekon.phone.RecentCall
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneRoot() {
    val context = LocalContext.current
    val call by CallService.ui.collectAsStateWithLifecycle()
    val recents by PhoneApp.instance.recents.observe().collectAsStateWithLifecycle(emptyList())
    var tab by remember { mutableIntStateOf(0) }
    var settings by remember { mutableStateOf(false) }
    var digits by remember { mutableStateOf("") }
    var muted by remember { mutableStateOf(false) }
    var speaker by remember { mutableStateOf(false) }
    var walkie by remember { mutableStateOf(call.phone?.mode == CallProtocol.MODE_WALKIE) }
    val phoneCall = call.phone?.call.orEmpty()
    val busy = call.pending.isNotBlank()
    val lineNumber = call.number.ifBlank { call.phone?.number.orEmpty() }

    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        perm.launch(need.toTypedArray())
    }
    LaunchedEffect(call.phone?.mode) {
        walkie = call.phone?.mode == CallProtocol.MODE_WALKIE
    }

    val joined = call.socket == "joined"
    val connecting = call.socket == "connecting" || call.pending == "connect"
    val statusLine = statusLabel(call)

    BekonPhoneTheme {
        if (phoneCall == "offhook") {
            InCallPane(
                number = lineNumber,
                muted = muted,
                speaker = speaker,
                statusLine = statusLine,
                onMute = {
                    muted = !muted
                    CallService.muteMic(context, muted)
                },
                onSpeaker = {
                    speaker = !speaker
                    CallService.speaker(context, speaker)
                },
                onHangup = { CallService.cancel(context) },
            )
            return@BekonPhoneTheme
        }
        if (call.pending == "dial" || (call.outbound && phoneCall == "ringing")) {
            ProgressCallPane(
                title = "Calling",
                number = lineNumber.ifBlank { digits },
                incoming = false,
                busy = busy,
                statusLine = statusLine,
                onAnswer = {},
                onEnd = { CallService.cancel(context) },
            )
            return@BekonPhoneTheme
        }
        if (phoneCall == "ringing") {
            ProgressCallPane(
                title = "Incoming",
                number = lineNumber,
                incoming = true,
                busy = busy,
                statusLine = statusLine,
                onAnswer = { CallService.pickup(context) },
                onEnd = { CallService.cancel(context) },
            )
            return@BekonPhoneTheme
        }
        if (settings) {
            BackHandler { settings = false }
        }
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                PhoneAppBar(
                    title = if (settings) "Settings" else "Bekon Phone",
                    status = statusLine,
                    onSettings = if (settings) null else ({ settings = true }),
                )
            },
            bottomBar = {
                if (!settings) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            icon = { Icon(Icons.Outlined.History, null) },
                            label = { Text("Recents") },
                        )
                        NavigationBarItem(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            icon = { Icon(Icons.Outlined.Contacts, null) },
                            label = { Text("Contacts") },
                        )
                        NavigationBarItem(
                            selected = tab == 2,
                            onClick = { tab = 2 },
                            icon = { Icon(Icons.Outlined.Dialpad, null) },
                            label = { Text("Keypad") },
                        )
                    }
                }
            },
        ) { pad ->
            if (settings) {
                SettingsPane(
                    modifier = Modifier.padding(pad),
                    joined = joined,
                    connecting = connecting,
                    walkie = walkie,
                    lastError = call.lastError,
                    onWalkie = { next ->
                        walkie = next
                        CallService.setMode(
                            context,
                            if (next) CallProtocol.MODE_WALKIE else CallProtocol.MODE_PHONE,
                        )
                    },
                )
            } else {
                Column(Modifier.padding(pad).fillMaxSize()) {
                    if (!joined) {
                        TextButton(
                            onClick = { CallService.connect(context) },
                            enabled = !connecting,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (connecting) "Connecting…" else "Connect") }
                    }
                    if (call.lastError.isNotBlank()) {
                        Text(
                            call.lastError,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            color = BekonHangup,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    when (tab) {
                        0 -> RecentsPane(recents, joined && !busy) { n -> CallService.dial(context, n) }
                        1 -> ContactsPane(joined && !busy) { n -> CallService.dial(context, n) }
                        else -> KeypadPane(digits, { digits = it }, joined && !busy, busy) {
                            CallService.dial(context, digits)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneAppBar(
    title: String,
    status: String,
    onSettings: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onSettings != null) {
            IconButton(onClick = onSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = "Settings")
            }
        } else {
            Spacer(Modifier.size(48.dp))
        }
    }
}

@Composable
private fun RecentsPane(rows: List<RecentCall>, canDial: Boolean, onDial: (String) -> Unit) {
    if (rows.isEmpty()) {
        EmptyHint("No calls yet", "Dial from the keypad or a contact. History stays on this phone.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(rows, key = { it.id }) { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(enabled = canDial) { onDial(row.number) }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    when (row.direction) {
                        RecentCall.OUT -> Icons.Outlined.CallMade
                        RecentCall.MISSED -> Icons.Outlined.CallMissed
                        else -> Icons.Outlined.CallReceived
                    },
                    null,
                    tint = if (row.direction == RecentCall.MISSED) BekonHangup else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(row.name.ifBlank { row.number }, fontWeight = FontWeight.Medium)
                    Text(
                        recentLine(row),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.direction == RecentCall.MISSED || row.result == "Failed" || row.result == "No answer" || row.result == "No ack") {
                            BekonHangup
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ContactsPane(canDial: Boolean, onDial: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<ContactHit>>(emptyList()) }
    LaunchedEffect(q) {
        hits = withContext(Dispatchers.IO) { PhoneApp.instance.contacts.search(q) }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = q,
            onValueChange = { q = it },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            placeholder = { Text("Search contacts") },
            singleLine = true,
            colors = bekonFieldColors(),
        )
        if (hits.isEmpty()) {
            EmptyHint("No contacts", "Allow contacts in the permission dialog, then search.")
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp)) {
                items(hits, key = { it.number + it.name }) { c ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(enabled = canDial) { onDial(c.number) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(c.name.take(1).uppercase(), fontWeight = FontWeight.Medium)
                        }
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(c.name.ifBlank { c.number }, fontWeight = FontWeight.Medium)
                            if (c.name.isNotBlank()) {
                                Text(c.number, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeypadPane(
    digits: String,
    onDigits: (String) -> Unit,
    canDial: Boolean,
    busy: Boolean,
    onCall: () -> Unit,
) {
    val keys = listOf(
        "1" to "", "2" to "ABC", "3" to "DEF",
        "4" to "GHI", "5" to "JKL", "6" to "MNO",
        "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
        "*" to "", "0" to "+", "#" to "",
    )
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            digits.ifBlank { " " },
            modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
            textAlign = TextAlign.Center,
            fontSize = 32.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        keys.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { (d, sub) ->
                    Column(
                        Modifier
                            .size(84.dp)
                            .clip(CircleShape)
                            .clickable {
                                onDigits(digits + d)
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(d, fontSize = 28.sp, fontWeight = FontWeight.Medium, lineHeight = 28.sp)
                        Text(
                            sub.ifEmpty { " " },
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            color = if (sub.isEmpty()) {
                                Color.Transparent
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.size(72.dp))
            FilledIconButton(
                onClick = onCall,
                enabled = canDial && digits.isNotBlank() && !busy,
                modifier = Modifier.size(72.dp),
                colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(containerColor = BekonAnswer),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = Color(0xFF042016),
                        strokeWidth = 3.dp,
                    )
                } else {
                    Icon(Icons.Outlined.Call, "Call", tint = Color(0xFF042016), modifier = Modifier.size(32.dp))
                }
            }
            TextButton(onClick = { if (digits.isNotEmpty()) onDigits(digits.dropLast(1)) }) {
                Text("⌫", fontSize = 22.sp)
            }
        }
    }
}

@Composable
private fun InCallPane(
    number: String,
    muted: Boolean,
    speaker: Boolean,
    statusLine: String,
    onMute: () -> Unit,
    onSpeaker: () -> Unit,
    onHangup: () -> Unit,
) {
    var startedAt by remember { mutableStateOf(System.currentTimeMillis()) }
    var now by remember { mutableStateOf(startedAt) }
    LaunchedEffect(number) {
        startedAt = System.currentTimeMillis()
        now = startedAt
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(72.dp))
        Text(statusLine, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(number.ifBlank { "Call" }, fontSize = 28.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
        Text(formatElapsed(now - startedAt), modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            CircleAction(Icons.Outlined.MicOff, "Mute", muted, onMute)
            CircleAction(Icons.Outlined.VolumeUp, "Speaker", speaker, onSpeaker)
        }
        Spacer(Modifier.height(40.dp))
        FilledIconButton(
            onClick = onHangup,
            modifier = Modifier.size(80.dp),
            colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(containerColor = BekonHangup),
        ) {
            Icon(Icons.Outlined.Call, "Hang up", modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun ProgressCallPane(
    title: String,
    number: String,
    incoming: Boolean,
    busy: Boolean,
    statusLine: String,
    onAnswer: () -> Unit,
    onEnd: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(72.dp))
        Text(statusLine, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, modifier = Modifier.padding(top = 12.dp), fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(number.ifBlank { "…" }, fontSize = 28.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.weight(1f))
        if (busy || !incoming) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
            Spacer(Modifier.height(40.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            if (incoming) {
                FilledIconButton(
                    onClick = onAnswer,
                    enabled = !busy,
                    modifier = Modifier.size(80.dp),
                    colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(containerColor = BekonAnswer),
                ) {
                    Icon(Icons.Outlined.Call, "Answer", tint = Color(0xFF042016), modifier = Modifier.size(32.dp))
                }
            }
            FilledIconButton(
                onClick = onEnd,
                modifier = Modifier.size(80.dp),
                colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(containerColor = BekonHangup),
            ) {
                Icon(Icons.Outlined.Call, if (incoming) "Decline" else "Hang up", modifier = Modifier.size(32.dp))
            }
        }
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun CircleAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, click: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                .clickable(onClick = click),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
        }
        Text(label, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingsPane(
    modifier: Modifier = Modifier,
    joined: Boolean,
    connecting: Boolean,
    walkie: Boolean,
    lastError: String,
    onWalkie: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { CallPrefs(context) }
    var url by remember { mutableStateOf(prefs.url) }
    var room by remember { mutableStateOf(prefs.room) }
    var seed by remember { mutableStateOf(prefs.seed) }
    var auto by remember { mutableStateOf(prefs.autoConnect) }
    val logs by CallService.trace.collectAsStateWithLifecycle()
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        OutlinedTextField(
            url,
            { url = it; prefs.url = it },
            label = { Text("WebSocket URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = bekonFieldColors(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            room,
            { room = it; prefs.room = it },
            label = { Text("Room") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = bekonFieldColors(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            seed,
            { seed = it; prefs.seed = it },
            label = { Text("Secret") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = bekonFieldColors(),
        )
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Auto connect", Modifier.weight(1f))
            Switch(auto, { auto = it; prefs.autoConnect = it })
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Walkie talkie", Modifier.weight(1f))
            Switch(walkie, onWalkie)
        }
        if (lastError.isNotBlank()) {
            Text(lastError, color = BekonHangup, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
        }
        Button(
            onClick = {
                prefs.url = url.trim()
                prefs.room = room.trim()
                prefs.seed = seed.trim()
                if (joined) CallService.disconnect(context) else CallService.connect(context)
            },
            enabled = !connecting,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (joined) BekonHangup else MaterialTheme.colorScheme.primary,
            ),
        ) {
            Text(
                when {
                    connecting -> "Connecting…"
                    joined -> "Disconnect"
                    else -> "Connect"
                },
            )
        }
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Debug", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            TextButton(onClick = { CallService.clearTrace() }) { Text("Clear") }
        }
        Text(
            "Commands on the call channel. PCM frames are not listed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (logs.isEmpty()) {
            Text("No messages yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        } else {
            logs.asReversed().forEach { line ->
                DebugLogRow(line)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DebugLogRow(line: ChannelLogLine) {
    val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(line.at))
    Text(
        "$clock  ${line.dir}  ${line.text}",
        modifier = Modifier.padding(vertical = 3.dp),
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        color = when (line.dir) {
            "→" -> MaterialTheme.colorScheme.primary
            "←" -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun EmptyHint(title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(body, modifier = Modifier.padding(top = 8.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun bekonFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
)

private fun statusLabel(call: pro.potoki.bekon.phone.CallUiState): String {
    val line = call.phone?.call.orEmpty()
    return when {
        call.pending == "connect" || call.socket == "connecting" -> "Connecting…"
        call.pending == "dial" -> "Calling…"
        call.pending == "pickup" -> "Answering…"
        call.pending == "cancel" -> "Hanging up…"
        call.pending == "mode" -> "Switching…"
        line == "ringing" && call.outbound -> "Calling…"
        line == "ringing" -> "Incoming"
        line == "offhook" -> "On call"
        call.socket == "joined" && call.phone?.mode == CallProtocol.MODE_WALKIE -> "Walkie"
        call.socket == "joined" -> "Connected"
        call.lastError.isNotBlank() && call.socket != "joined" -> call.lastError
        else -> "Not connected"
    }
}

private fun formatElapsed(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}

private fun recentLine(row: RecentCall): String {
    val whenAt = formatWhen(row.startedAt)
    val outcome = when {
        row.result.isNotBlank() && row.durationMs >= 4_000L -> "${row.result} · ${formatElapsed(row.durationMs)}"
        row.result.isNotBlank() -> row.result
        row.durationMs > 0L -> formatElapsed(row.durationMs)
        row.direction == RecentCall.MISSED -> "Missed"
        else -> "No result"
    }
    return "${row.number} · $whenAt · $outcome"
}

private fun formatWhen(ms: Long): String {
    val fmt = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
    return fmt.format(Date(ms))
}
