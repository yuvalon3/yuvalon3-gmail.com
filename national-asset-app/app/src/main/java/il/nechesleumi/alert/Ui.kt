@file:OptIn(ExperimentalMaterial3Api::class)

package il.nechesleumi.alert

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val DrillColor = Color(0xFFD97706)
val RealColor = Color(0xFFB91C1C)
val FoundColor = Color(0xFF15803D)

private enum class Screen { MAIN, CONTACTS, SETTINGS }

private fun modeColor(m: Mode) = if (m == Mode.REAL) RealColor else DrillColor
private fun hhmmss(t: Long?) = t?.let { SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(it)) } ?: ""
private fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

@Composable
fun AppRoot(trigger: MutableState<TriggerPrompt?>) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(primary = RealColor)
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
                RequestPermissions()
                when (screen) {
                    Screen.MAIN -> MainScreen({ screen = Screen.CONTACTS }, { screen = Screen.SETTINGS })
                    Screen.CONTACTS -> ContactsScreen { screen = Screen.MAIN }
                    Screen.SETTINGS -> SettingsScreen { screen = Screen.MAIN }
                }
                trigger.value?.let { t ->
                    StartDialog(preset = t.mode, fromTrigger = t.fromTrigger, onDismiss = { trigger.value = null })
                }
            }
        }
    }
}

private val neededPermissions = buildList {
    add(Manifest.permission.SEND_SMS)
    add(Manifest.permission.RECEIVE_SMS)
    add(Manifest.permission.CALL_PHONE)
    add(Manifest.permission.READ_PHONE_STATE)
    add(Manifest.permission.READ_CALL_LOG)
    if (Build.VERSION.SDK_INT >= 28) add(Manifest.permission.ANSWER_PHONE_CALLS)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}

private fun missingPermissions(ctx: Context) =
    neededPermissions.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }

@Composable
private fun RequestPermissions() {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) {
        val missing = missingPermissions(ctx)
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
    }
}

// =====================================================================  MAIN

@Composable
private fun MainScreen(onContacts: () -> Unit, onSettings: () -> Unit) {
    val ev by Store.event.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("נכס לאומי", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onContacts) { Icon(Icons.Filled.Person, "רשימת תפוצה") }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "הגדרות") }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            val e = ev
            if (e == null) IdleContent(onContacts, onSettings) else EventContent(e)
        }
    }
}

@Composable
private fun IdleContent(onContacts: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val contacts by Store.contacts.collectAsState()
    val s by Store.settings.collectAsState()
    var ask by remember { mutableStateOf<Mode?>(null) }
    var missing by remember { mutableStateOf(missingPermissions(ctx)) }
    LaunchedEffect(Unit) { while (true) { missing = missingPermissions(ctx); delay(2000) } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (missing.isNotEmpty()) Warning(
            "חסרות הרשאות (SMS / טלפון / יומן שיחות / התראות).",
            "לאישור",
        ) {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            )
        }
        if (contacts.isEmpty()) Warning("רשימת התפוצה ריקה – הוסיפו אנשים.", "לרשימה", onContacts)

        Text("רשימת תפוצה: ${contacts.size} אנשים", style = MaterialTheme.typography.titleMedium)
        val what = listOfNotNull(
            if (s.sendSms) "SMS" else null,
            if (s.makeCalls) "שיחה מהטלפון שלך" else null,
        ).joinToString(" + ").ifEmpty { "כלום (הכל כבוי בהגדרות)" }
        Text("בהפעלה יישלח לכל אחד: $what", style = MaterialTheme.typography.bodyMedium)

        BigButton("הפעלת נכס לאומי – תרגיל", DrillColor, enabled = contacts.isNotEmpty()) { ask = Mode.DRILL }
        BigButton("הפעלת נכס לאומי – אמת", RealColor, enabled = contacts.isNotEmpty()) { ask = Mode.REAL }
    }
    ask?.let { StartDialog(preset = it, fromTrigger = false, onDismiss = { ask = null }) }
}

@Composable
private fun BigButton(text: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(88.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
    ) { Text(text, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun Warning(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(8.dp))
            Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

/** Confirmation before starting (manual or from an incoming trigger SMS). */
@Composable
private fun StartDialog(preset: Mode?, fromTrigger: Boolean, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val contacts by Store.contacts.collectAsState()
    var mode by remember { mutableStateOf(preset ?: Mode.DRILL) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (fromTrigger) "התקבלה הודעת הפעלה" else "להפעיל נכס לאומי?",
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Mode.entries.forEach { m ->
                        FilterChip(
                            selected = mode == m,
                            onClick = { mode = m },
                            label = { Text(m.label, fontWeight = FontWeight.Bold) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = modeColor(m), selectedLabelColor = Color.White,
                            ),
                        )
                    }
                }
                Text("תישלח הודעה ותתבצע שיחה ל-${contacts.size} אנשים ברשימה.")
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (contacts.isEmpty()) toast(ctx, "רשימת התפוצה ריקה")
                    else EventService.start(ctx, mode)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = modeColor(mode)),
            ) { Text("הפעל – ${mode.label}") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } },
    )
}

private enum class Filter(val label: String) { ALL("הכל"), MISSING("לא נמצאו"), FOUND("נמצאו") }

@Composable
private fun EventContent(ev: EventData) {
    val ctx = LocalContext.current
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var confirmEnd by remember { mutableStateOf(false) }
    val s by Store.settings.collectAsState()
    val dialing by Store.dialerRunning.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Banner(ev)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Filter.entries.forEach { f ->
                val n = when (f) {
                    Filter.ALL -> ev.entries.size
                    Filter.MISSING -> ev.entries.size - ev.foundCount
                    Filter.FOUND -> ev.foundCount
                }
                FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text("${f.label} ($n)") })
            }
        }
        CallingPanel(ev)
        val shown = ev.entries
            .filter { filter == Filter.ALL || (filter == Filter.FOUND) == it.found }
            .sortedWith(compareBy<Entry> { it.found }.thenBy { it.foundAt ?: 0L })
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(shown, key = { it.contactId }) { EntryCard(it, ev.active, s.makeCalls) }
        }
        Surface(tonalElevation = 3.dp) {
            Row(
                Modifier.fillMaxWidth().padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (ev.active) {
                    if (s.makeCalls) {
                        if (dialing) {
                            OutlinedButton(
                                onClick = { EventService.send(ctx, EventService.ACTION_PAUSE_CALLS) },
                                modifier = Modifier.weight(1f),
                            ) { Text("עצור חיוגים", maxLines = 1) }
                        } else {
                            OutlinedButton(
                                onClick = { EventService.send(ctx, EventService.ACTION_RETRY); toast(ctx, "מחייג שוב למי שלא נמצא") },
                                modifier = Modifier.weight(1f),
                            ) { Text("חייג שוב", maxLines = 1) }
                        }
                    }
                    OutlinedButton(onClick = { share(ctx, ev) }, modifier = Modifier.weight(1f)) { Text("שתף דוח", maxLines = 1) }
                    Button(
                        onClick = { confirmEnd = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text("סיום", maxLines = 1) }
                } else {
                    OutlinedButton(onClick = { share(ctx, ev) }, modifier = Modifier.weight(1f)) { Text("שתף דוח") }
                    Button(onClick = { Store.clearEvent() }, modifier = Modifier.weight(1f)) { Text("סגור אירוע") }
                }
            }
        }
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("לסיים את האירוע?") },
            text = { Text("שיחות שעדיין פעילות ינותקו. הדוח יישמר עד שתסגרו את האירוע.") },
            confirmButton = {
                Button(onClick = { EventService.send(ctx, EventService.ACTION_STOP); confirmEnd = false }) { Text("סיום") }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("ביטול") } },
        )
    }
}

/** The person being called right now, with "ענה / לא ענה" buttons. */
@Composable
private fun CallingPanel(ev: EventData) {
    val ctx = LocalContext.current
    val callingId by Store.calling.collectAsState()
    val dialing by Store.dialerRunning.collectAsState()
    if (!ev.active || !dialing) return
    val e = callingId?.let { id -> ev.entries.firstOrNull { it.contactId == id } }
    val waiting = ev.entries.count { it.call == CallState.WAITING }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            if (e == null) {
                Text("ממתין לסבב החיוג הבא… ($waiting בתור)", fontWeight = FontWeight.Bold)
            } else {
                Text("📞 מתקשר ל${e.name} · ${e.call.label}", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("עוד $waiting בתור", fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { EventService.send(ctx, EventService.ACTION_ANSWERED) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = FoundColor),
                    ) { Text("ענה ✓") }
                    OutlinedButton(
                        onClick = { EventService.send(ctx, EventService.ACTION_NO_ANSWER) },
                        modifier = Modifier.weight(1f),
                    ) { Text("לא ענה ✗") }
                }
            }
        }
    }
}

private fun share(ctx: Context, ev: EventData) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Report.text(ev))
    ctx.startActivity(Intent.createChooser(send, "שיתוף דוח"))
}

@Composable
private fun Banner(ev: EventData) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ev.endedAt) {
        while (ev.endedAt == null) { now = System.currentTimeMillis(); delay(1000) }
    }
    val total = ev.entries.size
    val found = ev.foundCount
    Surface(color = if (ev.active) modeColor(ev.mode) else Color(0xFF475569), contentColor = Color.White) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("נכס לאומי – ${ev.mode.label}", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            val dur = Report.duration((ev.endedAt ?: now) - ev.startedAt)
            Text(if (ev.active) "פעיל מ-${hhmmss(ev.startedAt)} · $dur" else "הסתיים · משך $dur")
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$found", fontSize = 44.sp, fontWeight = FontWeight.Bold)
                Text(" / $total נמצאו", fontSize = 20.sp, modifier = Modifier.padding(bottom = 8.dp))
            }
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else found.toFloat() / total },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.3f),
            )
        }
    }
}

@Composable
private fun Pill(label: String, value: String, color: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$label: ", fontSize = 12.sp, color = color)
        Text(value, fontSize = 12.sp, color = color, fontWeight = FontWeight.Bold)
    }
}

private val neutral = Color(0xFF64748B)
private val pending = Color(0xFF2563EB)
private val bad = Color(0xFFDC2626)

private fun smsColor(s: SmsState) = when (s) {
    SmsState.DELIVERED, SmsState.SENT -> FoundColor
    SmsState.SENDING -> pending
    SmsState.FAILED -> bad
    SmsState.NONE -> neutral
}

private fun callColor(c: CallState) = when (c) {
    CallState.ANSWERED -> FoundColor
    CallState.WAITING, CallState.DIALING, CallState.IN_CALL -> pending
    CallState.MAYBE -> DrillColor
    CallState.IDLE, CallState.UNKNOWN -> neutral
    CallState.NO_ANSWER, CallState.FAILED -> bad
}

@Composable
private fun EntryCard(e: Entry, active: Boolean, canCall: Boolean) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Card(
            onClick = { menu = true },
            colors = CardDefaults.cardColors(
                containerColor = if (e.found) FoundColor.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(if (e.found) FoundColor else neutral.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (e.found) Icon(Icons.Filled.Check, "נמצא", tint = Color.White)
                    else Icon(Icons.Filled.Person, null, tint = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.name, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(e.phone, fontSize = 13.sp, color = neutral)
                    if (e.found) Text("${e.foundHow} · ${hhmmss(e.foundAt)}", fontSize = 13.sp, color = FoundColor)
                    e.replyText?.let {
                        Text("“$it”", fontSize = 13.sp, fontStyle = FontStyle.Italic, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    e.error?.let { Text(it, fontSize = 12.sp, color = bad, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Pill("SMS", e.sms.label, smsColor(e.sms))
                    val calls = if (e.attempts > 1) "${e.call.label} (${e.attempts})" else e.call.label
                    Pill("שיחה", calls, callColor(e.call))
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (e.manualAt == null) {
                DropdownMenuItem(
                    text = { Text("סמן ידנית כנמצא") },
                    leadingIcon = { Icon(Icons.Filled.CheckCircle, null) },
                    onClick = { Store.updateEntry(e.contactId) { it.copy(manualAt = System.currentTimeMillis()) }; menu = false },
                )
            } else {
                DropdownMenuItem(
                    text = { Text("בטל סימון ידני") },
                    leadingIcon = { Icon(Icons.Filled.Close, null) },
                    onClick = { Store.updateEntry(e.contactId) { it.copy(manualAt = null) }; menu = false },
                )
            }
            if (active && canCall) {
                DropdownMenuItem(
                    text = { Text("חייג שוב") },
                    leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                    onClick = { EventService.send(ctx, EventService.ACTION_CALL_ONE, e.contactId); menu = false },
                )
            }
            if (active) {
                DropdownMenuItem(
                    text = { Text("שלח SMS שוב") },
                    leadingIcon = { Icon(Icons.Filled.Email, null) },
                    onClick = { EventService.send(ctx, EventService.ACTION_SMS_ONE, e.contactId); menu = false },
                )
            }
            DropdownMenuItem(
                text = { Text("התקשר בעצמי") },
                leadingIcon = { Icon(Icons.Filled.Call, null) },
                onClick = { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${e.phone}"))); menu = false },
            )
        }
    }
}

// =====================================================================  CONTACTS

@Composable
private fun ContactsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val contacts by Store.contacts.collectAsState()
    var editing by remember { mutableStateOf<Contact?>(null) }
    var importing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Contact?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data ?: return@rememberLauncherForActivityResult
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        runCatching {
            ctx.contentResolver.query(uri, proj, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0).orEmpty()
                    val phone = c.getString(1).orEmpty()
                    if (!Store.addContact(name, phone)) toast(ctx, "המספר כבר ברשימה")
                }
            }
        }.onFailure { toast(ctx, "לא ניתן לקרוא את איש הקשר") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("רשימת תפוצה (${contacts.size})") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { editing = Contact("", "", "") }, modifier = Modifier.weight(1f)) { Text("הוספה") }
                OutlinedButton(
                    onClick = {
                        runCatching {
                            picker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                        }.onFailure { toast(ctx, "אין אפליקציית אנשי קשר") }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("מאנשי קשר", maxLines = 1) }
                OutlinedButton(onClick = { importing = true }, modifier = Modifier.weight(1f)) { Text("ייבוא", maxLines = 1) }
            }
            if (contacts.isEmpty()) {
                Text(
                    "אין עדיין אנשים ברשימה.",
                    Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            LazyColumn(Modifier.weight(1f)) {
                items(contacts, key = { it.id }) { c ->
                    ListItem(
                        headlineContent = { Text(c.name, fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text(c.phone) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { editing = c }) { Icon(Icons.Filled.Edit, "עריכה") }
                                IconButton(onClick = { deleting = c }) { Icon(Icons.Filled.Delete, "מחיקה") }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    editing?.let { c ->
        var name by remember(c) { mutableStateOf(c.name) }
        var phone by remember(c) { mutableStateOf(c.phone) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (c.id.isEmpty()) "הוספת איש קשר" else "עריכה") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("שם") }, singleLine = true)
                    OutlinedTextField(
                        phone, { phone = it }, label = { Text("טלפון") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (phone.filter(Char::isDigit).length < 9) { toast(ctx, "מספר טלפון לא תקין"); return@Button }
                    if (c.id.isEmpty()) {
                        if (!Store.addContact(name, phone)) toast(ctx, "המספר כבר ברשימה")
                    } else {
                        Store.updateContact(c.copy(name = name.trim().ifBlank { phone.trim() }, phone = phone.trim()))
                    }
                    editing = null
                }) { Text("שמירה") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("ביטול") } },
        )
    }

    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("למחוק את ${c.name}?") },
            confirmButton = { Button(onClick = { Store.removeContact(c.id); deleting = null }) { Text("מחיקה") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("ביטול") } },
        )
    }

    if (importing) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { importing = false },
            title = { Text("ייבוא רשימה") },
            text = {
                Column {
                    Text("אדם אחד בכל שורה: שם ומספר.\nלמשל: דנה כהן, 052-1234567", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 160.dp))
                }
            },
            confirmButton = {
                Button(onClick = {
                    val rows = Importer.parse(text)
                    val added = rows.count { (n, p) -> Store.addContact(n, p) }
                    toast(ctx, "נוספו $added מתוך ${rows.size}")
                    importing = false
                }) { Text("ייבוא") }
            },
            dismissButton = { TextButton(onClick = { importing = false }) { Text("ביטול") } },
        )
    }
}

// =====================================================================  SETTINGS

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf(Store.settings.value) }
    var tts by remember { mutableStateOf<android.speech.tts.TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        var engine: android.speech.tts.TextToSpeech? = null
        engine = android.speech.tts.TextToSpeech(ctx) { st ->
            if (st == android.speech.tts.TextToSpeech.SUCCESS) {
                engine?.language = Locale("he", "IL")
                tts = engine
            }
        }
        onDispose { engine?.shutdown() }
    }

    fun save() { Store.saveSettings(s); toast(ctx, "נשמר") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("הגדרות") },
                navigationIcon = { IconButton(onClick = { Store.saveSettings(s); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") } },
                actions = { TextButton(onClick = { save() }) { Text("שמירה") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Section("הודעות SMS (נשלחות מהסים של הטלפון)")
            Toggle("לשלוח SMS", s.sendSms) { s = s.copy(sendSms = it) }
            OutlinedTextField(s.smsDrill, { s = s.copy(smsDrill = it) }, Modifier.fillMaxWidth(), label = { Text("נוסח הודעה – תרגיל") })
            OutlinedTextField(s.smsReal, { s = s.copy(smsReal = it) }, Modifier.fillMaxWidth(), label = { Text("נוסח הודעה – אמת") })

            Section("שיחות (מהטלפון שלך, אחת אחרי השנייה)")
            Toggle("לבצע שיחות", s.makeCalls) { s = s.copy(makeCalls = it) }
            Toggle("להשמיע הודעה ברמקול בזמן השיחה", s.announce) { s = s.copy(announce = it) }
            OutlinedTextField(
                s.callText, { s = s.copy(callText = it) }, Modifier.fillMaxWidth(),
                label = { Text("מה נאמר בשיחה") },
                supportingText = { Text("{mode} יוחלף ב\"תרגיל\" או \"אמת\"") },
            )
            NumberField("זמן מקסימלי לשיחה, כולל צלצול (שניות)", s.callWindowSec) { s = s.copy(callWindowSec = it) }
            NumberField("מספר סבבי חיוג", s.maxAttempts) { s = s.copy(maxAttempts = it) }
            NumberField("המתנה בין סבבים (שניות)", s.retryDelaySec) { s = s.copy(retryDelaySec = it) }
            Text(
                "אחרי כל שיחה האפליקציה בודקת ביומן השיחות אם השיחה נענתה. " +
                    "אם הצד השני ניתק בעצמו – \"ענה\". אם השיחה נמשכה עד הזמן המקסימלי – \"ענה? אולי תא קולי\" (לא נספר כנמצא). " +
                    "בזמן שיחה אפשר ללחוץ ענה / לא ענה במסך או בהתראה.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = {
                tts?.speak(s.callTextFor(Mode.DRILL), android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "test")
                    ?: toast(ctx, "מנוע הקראה לא זמין")
            }) { Text("🔊 השמע לדוגמה") }

            Section("הפעלה אוטומטית מהודעה נכנסת")
            Text(
                "כשמגיעה הודעת SMS עם \"הופעל נכס לאומי תרגיל/אמת\" – תופיע התראה עם צלצול, ובלחיצה אחת מפעילים.",
                style = MaterialTheme.typography.bodySmall,
            )
            Toggle("להפעיל מיד בלי אישור (לא מומלץ)", s.autoStart) { s = s.copy(autoStart = it) }
            OutlinedTextField(
                s.triggerSenders, { s = s.copy(triggerSenders = it) }, Modifier.fillMaxWidth(),
                label = { Text("שולחים מורשים (ריק = כל שולח)") },
                supportingText = { Text("מספרים או שמות שולח, מופרדים בפסיק") },
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text("שמירה") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
    HorizontalDivider()
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        text,
        { t -> text = t.filter(Char::isDigit).take(4); text.toIntOrNull()?.let(onChange) },
        Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
