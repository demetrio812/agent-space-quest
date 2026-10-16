package io.agentspace.quest

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.agentspace.quest.connection.ConnectionProfile
import io.agentspace.quest.connection.SshConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
fun ConnectionUi(controller: CatalogController, onConnect: () -> Unit, onDemo: () -> Unit, onClose: () -> Unit) {
    val saved = remember { controller.profiles.load() }
    var host by remember { mutableStateOf(saved?.host.orEmpty()) }
    var port by remember { mutableStateOf((saved?.port ?: 22).toString()) }
    var user by remember { mutableStateOf(saved?.user.orEmpty()) }
    var knownHost by remember { mutableStateOf(saved?.knownHost.orEmpty()) }
    var fingerprint by remember { mutableStateOf("") }
    var publicKey by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        try { publicKey = withContext(Dispatchers.IO) { controller.keys.publicKey() } }
        catch (_: Exception) { message = "Couldn't prepare this headset's pairing key." }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF172736)).padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("Connect to your Mac", color = Color.White, fontSize = 24.sp)
        Text("Herdr · live sessions over SSH", color = Color(0xFFACBFCD), fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ConnectionField("Mac address", host, { host = it; knownHost = ""; fingerprint = "" }, Modifier.weight(2f))
            ConnectionField("SSH port", port, { port = it; knownHost = ""; fingerprint = "" }, Modifier.weight(1f))
            ConnectionField("Mac username", user, { user = it }, Modifier.weight(2f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(if (busy) "Checking…" else "Check Mac identity") {
                if (!busy) scope.launch {
                    busy = true
                    val checkedHost = host; val checkedPort = port
                    try {
                        val identity = withContext(Dispatchers.IO) { SshConnection.probeHostKey(checkedHost, checkedPort.toInt()) }
                        if (host == checkedHost && port == checkedPort) {
                            knownHost = identity.knownHost; fingerprint = identity.fingerprint
                            message = "Compare this fingerprint with your Mac before connecting."
                        }
                    } catch (_: Exception) { message = "Couldn't check the Mac. Check its address, SSH port and Remote Login." }
                    finally { busy = false }
                }
            }
            ActionButton("Use demo", onDemo)
            ActionButton("Close", onClose)
        }
        if (fingerprint.isNotEmpty()) Text(fingerprint, color = Color(0xFF97CEEB), fontSize = 13.sp)
        Text("On the Mac: ssh-keygen -lf /etc/ssh/ssh_host_rsa_key.pub -E sha256", color = Color(0xFFACBFCD), fontSize = 12.sp)
        Text("Pair this headset using the supplied Mac pairing script. Viewing is enabled by default; add --allow-input on the Mac to enable Send.", color = Color.White, fontSize = 14.sp)
        if (publicKey.isNotEmpty()) BasicTextField(publicKey, {}, readOnly = true, textStyle = TextStyle(color = Color(0xFFACBFCD), fontSize = 11.sp),
            modifier = Modifier.fillMaxWidth().background(Color(0xFF0F202D)).padding(8.dp))
        if (message.isNotEmpty()) Text(message, color = Color(0xFFFFC58F), fontSize = 13.sp)
        ActionButton(if (knownHost.isEmpty()) "Check Mac identity first" else "Trust this Mac & connect") {
            if (knownHost.isNotEmpty() && !busy) {
                try {
                    val sameHost = saved?.host == host && saved.port == port.toInt() && saved.user == user && saved.knownHost == knownHost
                    controller.profiles.save(ConnectionProfile(if (sameHost) saved!!.id else UUID.randomUUID().toString(), host, port.toInt(), user, knownHost))
                    controller.profiles.enabled = true
                    onConnect()
                } catch (_: Exception) { message = "Check the address, port and username." }
            }
        }
    }
}

@Composable
private fun ConnectionField(label: String, value: String, change: (String) -> Unit, modifier: Modifier) {
    Column(modifier) {
        Text(label, color = Color(0xFFACBFCD), fontSize = 12.sp)
        BasicTextField(value, change, singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
            modifier = Modifier.fillMaxWidth().background(Color(0xFF23394B)).padding(12.dp))
    }
}
