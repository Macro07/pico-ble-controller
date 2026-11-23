package com.example.picoterminal

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Palette colori Terminale Dark
val TermBg = Color(0xFF0D1117)
val TermSurface = Color(0xFF161B22)
val TermBorder = Color(0xFF30363D)
val TermGreen = Color(0xFF3FB950)
val TermCyan = Color(0xFF58A6FF)
val TermYellow = Color(0xFFD29922)
val TermRed = Color(0xFFF85149)
val TermText = Color(0xFFC9D1D9)
val TermMuted = Color(0xFF8B949E)

data class PresetCommand(
    val name: String,
    val commandPrefix: String,
    val description: String,
    val placeholder: String
)

class MainActivity : ComponentActivity() {
    private lateinit var bleManager: BleManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleManager = BleManager(this)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = TermBg,
                    surface = TermSurface,
                    primary = TermGreen,
                    onBackground = TermText,
                    onSurface = TermText
                )
            ) {
                TerminalApp(bleManager)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.disconnect()
    }
}

@Composable
fun TerminalApp(bleManager: BleManager) {
    val connectionState by bleManager.connectionState.collectAsState()
    val logs by bleManager.terminalLogs.collectAsState()
    val discoveredDevices by bleManager.discoveredDevices.collectAsState()

    var isKeyboardMode by remember { mutableStateOf(false) }
    var currentLayout by remember { mutableStateOf("US") }
    var showDeviceDialog by remember { mutableStateOf(false) }

    // 1. Invio automatico del layout ogni volta che il dispositivo si connette/riconnette
    LaunchedEffect(connectionState) {
        if (connectionState == ConnectionState.CONNECTED) {
            bleManager.sendCommand("//LAYOUT $currentLayout -output phone")
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val allGranted = perms.values.all { it }
        if (allGranted) {
            showDeviceDialog = true
            bleManager.startScan()
        }
    }

    fun openScannerDialog() {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }
        permissionLauncher.launch(requiredPermissions)
    }

    if (showDeviceDialog) {
        DevicePickerDialog(
            devices = discoveredDevices,
            isScanning = connectionState == ConnectionState.SCANNING,
            onRescan = { bleManager.startScan() },
            onSelectDevice = { selected ->
                showDeviceDialog = false
                bleManager.connectToDevice(selected)
            },
            onDismiss = {
                bleManager.stopScan()
                showDeviceDialog = false
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TermBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(8.dp)
    ) {
        TerminalTopBar(
            connectionState = connectionState,
            currentLayout = currentLayout,
            onConnectClick = {
                if (connectionState == ConnectionState.CONNECTED) {
                    bleManager.disconnect()
                } else {
                    openScannerDialog()
                }
            },
            onToggleLayout = {
                currentLayout = if (currentLayout == "US") "IT" else "US"
                bleManager.sendCommand("//LAYOUT $currentLayout -output phone")
            },
            onStopClick = {
                bleManager.sendCommand("//STOP")
                isKeyboardMode = false
            }
        )

        Spacer(modifier = Modifier.height(6.dp))

        TerminalLogBox(
            logs = logs,
            onClear = { bleManager.clearLogs() },
            modifier = Modifier
                .fillMaxWidth()
                .weight(if (isKeyboardMode) 0.35f else 0.35f)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(if (isKeyboardMode) 0.65f else 0.65f)
        ) {
            if (isKeyboardMode) {
                KeyboardLiveView(
                    bleManager = bleManager,
                    onExitKeyboard = {
                        bleManager.sendCommand("//STOP")
                        isKeyboardMode = false
                    }
                )
            } else {
                CommandTreeView(
                    bleManager = bleManager,
                    onEnterKeyboard = {
                        bleManager.sendCommand("//KEYBOARD -output phone")
                        isKeyboardMode = true
                    }
                )
            }
        }
    }
}

@Composable
fun TerminalTopBar(
    connectionState: ConnectionState,
    currentLayout: String,
    onConnectClick: () -> Unit,
    onToggleLayout: () -> Unit,
    onStopClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TermSurface, RoundedCornerShape(6.dp))
            .border(1.dp, TermBorder, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        val (statusText, statusColor) = when (connectionState) {
            ConnectionState.CONNECTED -> "● ONLINE" to TermGreen
            ConnectionState.SCANNING -> "◌ SCAN..." to TermYellow
            ConnectionState.CONNECTING -> "◌ CONN..." to TermYellow
            ConnectionState.DISCONNECTED -> "○ OFFLINE" to TermMuted
        }

        Text(
            text = statusText,
            color = statusColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            modifier = Modifier.clickable { onConnectClick() }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TerminalChip(
                label = if (connectionState == ConnectionState.CONNECTED) "DISC" else "CONN",
                color = TermCyan,
                onClick = onConnectClick
            )

            TerminalChip(
                label = "KEY:$currentLayout",
                color = TermYellow,
                onClick = onToggleLayout
            )

            TerminalChip(
                label = "//STOP",
                color = TermRed,
                filled = true,
                onClick = onStopClick
            )
        }
    }
}

@Composable
fun TerminalLogBox(
    logs: List<TerminalLog>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .background(Color(0xFF090C10), RoundedCornerShape(6.dp))
            .border(1.dp, TermBorder, RoundedCornerShape(6.dp))
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "UART_STDOUT // LOG",
                color = TermMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            Text(
                text = "[CLEAR]",
                color = TermMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.clickable { onClear() }
            )
        }

        HorizontalDivider(color = TermBorder, modifier = Modifier.padding(vertical = 4.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            items(logs) { log ->
                val prefixColor = when (log.prefix) {
                    "TX" -> TermCyan
                    "RX" -> TermGreen
                    else -> TermYellow
                }
                Row {
                    Text(
                        text = "[${log.prefix}] ",
                        color = prefixColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = log.message,
                        color = TermText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
fun CommandTreeView(
    bleManager: BleManager,
    onEnterKeyboard: () -> Unit
) {
    val presets = remember {
        listOf(
            PresetCommand(
                name = "WRITE_BLOCK",
                commandPrefix = "//WRITE",
                description = "Scrive un blocco di testo continuo tramite tastiera HID.",
                placeholder = "Testo da digitare..."
            ),
            PresetCommand(
                name = "WRITE_FILE",
                commandPrefix = "//WRITE -file",
                description = "Legge ed esegue riga per riga un file salvato sulla flash del Pico.",
                placeholder = "nome_file.txt"
            ),
            PresetCommand(
                name = "SHORTCUT",
                commandPrefix = "//SHORTCUT",
                description = "Esegue una combinazione tasti (es. WIN+R, CTRL+SHIFT+ESC, ALT+F4).",
                placeholder = "WIN+R"
            ),
            PresetCommand(
                name = "OPEN_LINK",
                commandPrefix = "//LINK",
                description = "Apre Esegui (WIN+R) e lancia l'URL nel browser predefinito.",
                placeholder = "https://github.com"
            )
        )
    }

    var selectedPreset by remember { mutableStateOf<PresetCommand?>(null) }
    var argInput by remember { mutableStateOf("") }
    var selectedOutput by remember { mutableStateOf("phone") }
    var rawPromptText by remember { mutableStateOf("") }

    // Rileva se la tastiera di sistema è aperta
    val isImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    // Nasconde pico:~$ se la tastiera è aperta mentre stai scrivendo in un programma selezionato
    val hideBottomPrompt = isImeVisible && selectedPreset != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TermSurface, RoundedCornerShape(6.dp))
            .border(1.dp, TermBorder, RoundedCornerShape(6.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Voce 1: > KEYBOARD
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TermBg, RoundedCornerShape(4.dp))
                    .border(1.dp, TermGreen.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .clickable { onEnterKeyboard() }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "> KEYBOARD [LIVE_HID]",
                    color = TermGreen,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = "[ENTRA →]",
                    color = TermGreen,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }

            // 2. Lista diretta comandi (senza intestazione PROGRAMMI PREIMPOSTATI)
            presets.forEach { preset ->
                val isSelected = selectedPreset == preset
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isSelected) TermBg else Color.Transparent, RoundedCornerShape(4.dp))
                        .border(
                            1.dp,
                            if (isSelected) TermCyan else TermBorder,
                            RoundedCornerShape(4.dp)
                        )
                        .clickable {
                            selectedPreset = if (isSelected) null else preset
                            argInput = ""
                        }
                        .padding(8.dp)
                ) {
                    Text(
                        text = "${if (isSelected) "v" else ">"} ${preset.name}",
                        color = if (isSelected) TermCyan else TermText,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )

                    if (isSelected) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = preset.description,
                            color = TermMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        TerminalTextField(
                            value = argInput,
                            onValueChange = { argInput = it },
                            placeholder = preset.placeholder
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "-output:",
                                color = TermMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                            listOf("phone" to "TELEFONO", "pico" to "PICO", "none" to "NONE").forEach { (flagVal, label) ->
                                TerminalChip(
                                    label = label,
                                    color = if (selectedOutput == flagVal) TermGreen else TermMuted,
                                    filled = selectedOutput == flagVal,
                                    onClick = { selectedOutput = flagVal }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        val outputSuffix = if (selectedOutput != "none") " -output $selectedOutput" else ""
                        val builtCommand = "${preset.commandPrefix} ${argInput.trim()}$outputSuffix".trim()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = builtCommand,
                                color = TermYellow,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                modifier = Modifier.weight(1f)
                            )
                            TerminalChip(
                                label = "[EXEC]",
                                color = TermGreen,
                                filled = true,
                                onClick = {
                                    if (preset.commandPrefix == "//WRITE") {
                                        bleManager.sendWriteBlock(argInput.trim(), selectedOutput)
                                    } else {
                                        bleManager.sendCommand(builtCommand)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        // 3. Prompt pico:~$ visibile solo quando non si sta usando la tastiera su un programma selezionato
        if (!hideBottomPrompt) {
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TermBg, RoundedCornerShape(4.dp))
                    .border(1.dp, TermBorder, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "pico:~$ ",
                    color = TermGreen,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                BasicTextField(
                    value = rawPromptText,
                    onValueChange = { rawPromptText = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = TermText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    ),
                    cursorBrush = SolidColor(TermGreen),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (rawPromptText.isNotBlank()) {
                                bleManager.sendCommand(rawPromptText)
                                rawPromptText = ""
                            }
                        }
                    ),
                    modifier = Modifier.weight(1f),
                    decorationBox = { innerTextField ->
                        if (rawPromptText.isEmpty()) {
                            Text(
                                text = "Digita comando //...",
                                color = TermMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        }
                        innerTextField()
                    }
                )
                Text(
                    text = "[INVIO]",
                    color = TermCyan,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable {
                        if (rawPromptText.isNotBlank()) {
                            bleManager.sendCommand(rawPromptText)
                            rawPromptText = ""
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun KeyboardLiveView(
    bleManager: BleManager,
    onExitKeyboard: () -> Unit
) {
    // Usa TextFieldValue per tracciare e modificare cursore e selezione sul telefono
    var liveField by remember { mutableStateOf(TextFieldValue("")) }
    var instantSend by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TermSurface, RoundedCornerShape(6.dp))
            .border(1.dp, TermGreen, RoundedCornerShape(6.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Header sessione attiva
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "// KEYBOARD [ATTIVA]",
                color = TermGreen,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            TerminalChip(
                label = if (instantSend) "MODO: ISTANTANEO" else "MODO: BLOCCO",
                color = TermCyan,
                onClick = { instantSend = !instantSend }
            )
        }

        // Barra scorciatoie rapide sincronizzate con il campo sul telefono
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TerminalChip(
                label = "COPIA",
                color = TermText,
                onClick = { bleManager.sendCommand("S:CTRL+C", logToConsole = false) }
            )
            TerminalChip(
                label = "INCOLLA",
                color = TermText,
                onClick = { bleManager.sendCommand("S:CTRL+V", logToConsole = false) }
            )
            TerminalChip(
                label = "SELECT",
                color = TermText,
                onClick = {
                    bleManager.sendCommand("S:CTRL+A", logToConsole = false)
                    // Seleziona tutto anche nel campo sul telefono
                    liveField = liveField.copy(
                        selection = TextRange(0, liveField.text.length)
                    )
                }
            )
            TerminalChip(
                label = "ESC",
                color = TermText,
                onClick = { bleManager.sendCommand("S:ESC", logToConsole = false) }
            )
            TerminalChip(
                label = "TAB",
                color = TermText,
                onClick = { bleManager.sendCommand("S:TAB", logToConsole = false) }
            )
            TerminalChip(
                label = "BACKSPACE",
                color = TermText,
                onClick = {
                    bleManager.sendCommand("S:BACKSPACE", logToConsole = false)
                    // Cancella la selezione o il carattere prima del cursore anche sul telefono
                    val sel = liveField.selection
                    val text = liveField.text
                    if (!sel.collapsed) {
                        val min = minOf(sel.start, sel.end)
                        val max = maxOf(sel.start, sel.end)
                        val newText = text.removeRange(min, max)
                        liveField = TextFieldValue(newText, TextRange(min))
                    } else if (sel.start > 0) {
                        val newText = text.removeRange(sel.start - 1, sel.start)
                        liveField = TextFieldValue(newText, TextRange(sel.start - 1))
                    }
                }
            )
            TerminalChip(
                label = "ENTER",
                color = TermText,
                onClick = {
                    bleManager.sendCommand("S:ENTER", logToConsole = false)
                    if (instantSend) {
                        val pos = liveField.selection.start
                        val newText = StringBuilder(liveField.text).insert(pos, "\n").toString()
                        liveField = TextFieldValue(newText, TextRange(pos + 1))
                    }
                }
            )
        }

        // Box TEXT INPUT sincronizzato
        Column {
            Text(
                text = "TEXT INPUT:",
                color = TermMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    TerminalTextFieldValue(
                        value = liveField,
                        onValueChange = { newValue ->
                            if (instantSend) {
                                val oldText = liveField.text
                                val newText = newValue.text

                                if (newText.length > oldText.length) {
                                    // Calcola i caratteri inseriti alla posizione del cursore
                                    val insertedCount = newText.length - oldText.length
                                    val insertEnd = newValue.selection.start
                                    val insertStart = (insertEnd - insertedCount).coerceAtLeast(0)
                                    val added = newText.substring(insertStart, insertEnd)

                                    added.forEach { ch ->
                                        when (ch) {
                                            ' ' -> bleManager.sendCommand("S:SPACE", logToConsole = false)
                                            '\n' -> bleManager.sendCommand("S:ENTER", logToConsole = false)
                                            else -> bleManager.sendCommand("K:$ch", logToConsole = false)
                                        }
                                    }
                                } else if (newText.length < oldText.length) {
                                    val deletedCount = oldText.length - newText.length
                                    // Se era selezionato un blocco intero (es. dopo SELECT), basta un BACKSPACE
                                    if (!liveField.selection.collapsed) {
                                        bleManager.sendCommand("S:BACKSPACE", logToConsole = false)
                                    } else {
                                        repeat(deletedCount) {
                                            bleManager.sendCommand("S:BACKSPACE", logToConsole = false)
                                        }
                                    }
                                }
                            }
                            liveField = newValue
                        },
                        placeholder = "Scrivi qui..."
                    )
                }
                if (!instantSend) {
                    TerminalChip(
                        label = "INVIA",
                        color = TermGreen,
                        filled = true,
                        onClick = {
                            if (liveField.text.isNotEmpty()) {
                                bleManager.sendCommand("K:${liveField.text}")
                                liveField = TextFieldValue("")
                            }
                        }
                    )
                }
            }
        }

        // Pad Direzionale sincronizzato con il cursore del telefono + //stop
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ArrowButton("←") {
                    bleManager.sendCommand("S:LEFT", logToConsole = false)
                    val newPos = (liveField.selection.start - 1).coerceAtLeast(0)
                    liveField = liveField.copy(selection = TextRange(newPos))
                }
                ArrowButton("↑") {
                    bleManager.sendCommand("S:UP", logToConsole = false)
                    val text = liveField.text
                    val cur = liveField.selection.start
                    val prevNewline = text.lastIndexOf('\n', (cur - 1).coerceAtLeast(0))
                    val newPos = if (prevNewline >= 0) prevNewline else 0
                    liveField = liveField.copy(selection = TextRange(newPos))
                }
                ArrowButton("↓") {
                    bleManager.sendCommand("S:DOWN", logToConsole = false)
                    val text = liveField.text
                    val cur = liveField.selection.start
                    val nextNewline = text.indexOf('\n', cur)
                    val newPos = if (nextNewline >= 0) (nextNewline + 1).coerceAtMost(text.length) else text.length
                    liveField = liveField.copy(selection = TextRange(newPos))
                }
                ArrowButton("→") {
                    bleManager.sendCommand("S:RIGHT", logToConsole = false)
                    val newPos = (liveField.selection.end + 1).coerceAtMost(liveField.text.length)
                    liveField = liveField.copy(selection = TextRange(newPos))
                }
            }

            TerminalChip(
                label = "//stop",
                color = TermRed,
                filled = true,
                onClick = onExitKeyboard
            )
        }
    }
}

@Composable
fun TerminalTextFieldValue(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = false,
        maxLines = 3,
        textStyle = TextStyle(
            color = TermText,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp
        ),
        cursorBrush = SolidColor(TermGreen),
        modifier = Modifier
            .fillMaxWidth()
            .background(TermBg, RoundedCornerShape(4.dp))
            .border(1.dp, TermBorder, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        decorationBox = { innerTextField ->
            if (value.text.isEmpty()) {
                Text(
                    text = placeholder,
                    color = TermMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
            innerTextField()
        }
    )
}//LAYOUT US -output phone//LAYOUT IT -output phone
@Composable
fun TerminalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(
            color = TermText,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp
        ),
        cursorBrush = SolidColor(TermGreen),
        modifier = Modifier
            .fillMaxWidth()
            .background(TermBg, RoundedCornerShape(4.dp))
            .border(1.dp, TermBorder, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        decorationBox = { innerTextField ->
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    color = TermMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
            innerTextField()
        }
    )
}

@Composable
fun TerminalChip(
    label: String,
    color: Color,
    filled: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .background(
                if (filled) color.copy(alpha = 0.2f) else TermBg,
                RoundedCornerShape(4.dp)
            )
            .border(1.dp, color, RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
        )
    }
}

@Composable
fun ArrowButton(symbol: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(TermBg, RoundedCornerShape(4.dp))
            .border(1.dp, TermCyan, RoundedCornerShape(4.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = symbol,
            color = TermCyan,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
    }
}

@Composable
fun DevicePickerDialog(
    devices: List<ScannedBleDevice>,
    isScanning: Boolean,
    onRescan: () -> Unit,
    onSelectDevice: (ScannedBleDevice) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TermSurface,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isScanning) "> SCANSIONE BLE..." else "> DISPOSITIVI BLE",
                    color = TermGreen,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                TerminalChip(
                    label = if (isScanning) "SCAN..." else "AGG.",
                    color = TermCyan,
                    onClick = { if (!isScanning) onRescan() }
                )
            }
        },
        text = {
            if (devices.isEmpty()) {
                Text(
                    text = if (isScanning) "Ricerca dispositivi nelle vicinanze...\nAssicurati che il GPS sia attivo."
                    else "Nessun dispositivo trovato.\nPremi [AGG.] per riprovare.",
                    color = TermMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(devices) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(TermBg, RoundedCornerShape(4.dp))
                                .border(1.dp, TermBorder, RoundedCornerShape(4.dp))
                                .clickable { onSelectDevice(item) }
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.name,
                                    color = if (item.name != "Dispositivo Sconosciuto") TermCyan else TermText,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = item.address,
                                    color = TermMuted,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                            }
                            Text(
                                text = "${item.rssi} dBm",
                                color = TermYellow,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TerminalChip(
                label = "[CHIUDI]",
                color = TermRed,
                onClick = onDismiss
            )
        }
    )
}