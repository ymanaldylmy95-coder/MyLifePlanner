package com.mylife.planner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.*
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    var tasks by mutableStateOf(listOf<Task>())
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Notifier.channel(this)
        Repo.listener = { tasks = Repo.all(this) }
        tasks = Repo.all(this)
        setContent { App(this) }
    }
    override fun onResume() { super.onResume(); tasks = Repo.all(this) }
}

@Composable
fun App(a: MainActivity) {
    val sys = isSystemInDarkTheme()
    var mode by remember { mutableIntStateOf(Repo.theme(a)) }
    val dark = if (mode == -1) sys else mode == 1
    val cs = if (dark) darkColorScheme(primary = Color(0xFF8B8DFF)) else lightColorScheme(primary = Color(0xFF6366F1))
    MaterialTheme(colorScheme = cs) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(Modifier.fillMaxSize()) {
                Main(a, dark) { mode = if (dark) 0 else 1; Repo.setTheme(a, mode) }
            }
        }
    }
}

val PRI = mapOf(1 to "🔴 عالية", 2 to "🟠 متوسطة", 3 to "🟢 منخفضة")
val ZONE: ZoneId get() = ZoneId.systemDefault()
fun dtOf(t: Task): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(t.due), ZONE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Main(a: MainActivity, dark: Boolean, toggle: () -> Unit) {
    val tasks = a.tasks
    var tab by remember { mutableIntStateOf(0) }
    var showForm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Task?>(null) }
    var snoozeT by remember { mutableStateOf<Task?>(null) }
    var delT by remember { mutableStateOf<Task?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val today = LocalDate.now()
    val by = compareBy<Task>({ it.due }, { it.priority })
    val lists = listOf(
        tasks.filter { !it.done && dtOf(it).toLocalDate() <= today }.sortedWith(by),
        tasks.filter { !it.done && dtOf(it).toLocalDate() == today.plusDays(1) }.sortedWith(by),
        tasks.filter { !it.done && dtOf(it).toLocalDate() > today.plusDays(1) }.sortedWith(by),
        tasks.filter { it.done }.sortedByDescending { it.doneAt }
    )
    val names = listOf("اليوم", "الغد", "القادمة", "المكتملة")
    val td = tasks.filter { dtOf(it).toLocalDate() == today }
    val dn = td.count { it.done }
    val pc = if (td.isEmpty()) 0 else dn * 100 / td.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("📅 منظم حياتي", fontWeight = FontWeight.Bold) },
                actions = { TextButton(onClick = toggle) { Text(if (dark) "☀️" else "🌙", fontSize = 22.sp) } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; showForm = true }) { Text("＋", fontSize = 28.sp) }
        }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            Card(
                Modifier.fillMaxWidth().padding(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (td.isEmpty()) "لا توجد مهام اليوم" else if (pc == 100) "🎉 أنجزت كل مهام اليوم!" else "إنجاز اليوم: $pc%",
                        fontWeight = FontWeight.Bold, fontSize = 17.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { pc / 100f }, modifier = Modifier.fillMaxWidth().height(10.dp))
                    Spacer(Modifier.height(6.dp))
                    Text("$dn من ${td.size} مهام مكتملة", fontSize = 13.sp)
                }
            }
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                names.forEachIndexed { i, n -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text("$n (${lists[i].size})") }) }
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 90.dp)) {
                if (lists[tab].isEmpty()) item {
                    Text("📭\nلا توجد مهام هنا", Modifier.fillMaxWidth().padding(48.dp), textAlign = TextAlign.Center, fontSize = 18.sp)
                }
                items(lists[tab], key = { it.id }) { t ->
                    TaskCard(t,
                        onCheck = { if (t.done) Repo.uncheck(a, t.id) else Repo.complete(a, t.id) },
                        onEdit = { editing = t; showForm = true },
                        onSnooze = { snoozeT = t }, onDel = { delT = t })
                }
            }
        }
    }
    if (showForm) TaskForm(editing, { showForm = false }) { Repo.upsert(a, it); showForm = false }
    snoozeT?.let { t ->
        AlertDialog(
            onDismissRequest = { snoozeT = null },
            title = { Text("⏰ تأجيل المهمة") },
            text = {
                Column {
                    TextButton({ Repo.snooze(a, t.id, 10); snoozeT = null }) { Text("بعد 10 دقائق") }
                    TextButton({ Repo.snooze(a, t.id, 60); snoozeT = null }) { Text("بعد ساعة") }
                    TextButton({ Repo.toTomorrow(a, t.id); snoozeT = null }) { Text("إلى الغد") }
                }
            },
            confirmButton = { TextButton({ snoozeT = null }) { Text("إلغاء") } }
        )
    }
    delT?.let { t ->
        AlertDialog(
            onDismissRequest = { delT = null },
            title = { Text("حذف المهمة؟") }, text = { Text(t.title) },
            confirmButton = { TextButton({ Repo.delete(a, t.id); delT = null }) { Text("حذف", color = Color(0xFFEF4444)) } },
            dismissButton = { TextButton({ delT = null }) { Text("إلغاء") } }
        )
    }
}

@Composable
fun TaskCard(t: Task, onCheck: () -> Unit, onEdit: () -> Unit, onSnooze: () -> Unit, onDel: () -> Unit) {
    val pcol = when (t.priority) { 1 -> Color(0xFFEF4444); 2 -> Color(0xFFF59E0B); else -> Color(0xFF10B981) }
    val dt = dtOf(t)
    val late = !t.done && dt.isBefore(LocalDateTime.now())
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(pcol))
            Checkbox(checked = t.done, onCheckedChange = { onCheck() })
            Column(Modifier.weight(1f).padding(vertical = 10.dp, horizontal = 4.dp)) {
                Text(
                    t.title, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    textDecoration = if (t.done) TextDecoration.LineThrough else null
                )
                if (t.desc.isNotBlank()) Text(t.desc, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val meta = "🕒 " + dt.format(DateTimeFormatter.ofPattern("HH:mm")) + "   📆 " + dt.format(DateTimeFormatter.ofPattern("yyyy/MM/dd")) +
                    "\n" + PRI[t.priority] + (if (t.remind > 0) "   🔔 قبل ${t.remind} د" else "") +
                    (if (t.repeat == "daily") "   🔁 يومياً" else if (t.repeat == "weekly") "   🔁 أسبوعياً" else "") +
                    (if (late) "   ⚠️ متأخرة" else "")
                Text(meta, fontSize = 12.sp, color = if (late) Color(0xFFEF4444) else MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    if (!t.done) { TextButton(onEdit) { Text("✏️ تعديل") }; TextButton(onSnooze) { Text("⏰ تأجيل") } }
                    TextButton(onDel) { Text("🗑️ حذف", color = Color(0xFFEF4444)) }
                }
            }
        }
    }
}

@Composable
fun <T> Chips(opts: List<Pair<T, String>>, sel: T, on: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        opts.forEach { (v, l) -> FilterChip(selected = sel == v, onClick = { on(v) }, label = { Text(l) }) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskForm(init: Task?, onDismiss: () -> Unit, onSave: (Task) -> Unit) {
    val base = init?.let { dtOf(it) } ?: LocalDateTime.now().plusHours(1).withSecond(0).withNano(0)
    var title by remember { mutableStateOf(init?.title ?: "") }
    var desc by remember { mutableStateOf(init?.desc ?: "") }
    var date by remember { mutableStateOf(base.toLocalDate()) }
    var time by remember { mutableStateOf(base.toLocalTime().withSecond(0).withNano(0)) }
    var pri by remember { mutableIntStateOf(init?.priority ?: 2) }
    var rem by remember { mutableIntStateOf(init?.remind ?: 10) }
    var rep by remember { mutableStateOf(init?.repeat ?: "none") }
    var showD by remember { mutableStateOf(false) }
    var showT by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (init == null) "مهمة جديدة" else "تعديل المهمة") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("اسم المهمة *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(desc, { desc = it }, label = { Text("الوصف (اختياري)") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ showD = true }, Modifier.weight(1f)) { Text("📆 $date") }
                    OutlinedButton({ showT = true }, Modifier.weight(1f)) { Text("🕒 " + time.format(DateTimeFormatter.ofPattern("HH:mm"))) }
                }
                Text("الأولوية", fontSize = 13.sp)
                Chips(listOf(1 to "🔴 عالية", 2 to "🟠 متوسطة", 3 to "🟢 منخفضة"), pri) { pri = it }
                Text("التذكير قبل الموعد", fontSize = 13.sp)
                Chips(listOf(0 to "عند الموعد", 5 to "5 دقائق", 10 to "10 دقائق", 30 to "30 دقيقة"), rem) { rem = it }
                Text("التكرار", fontSize = 13.sp)
                Chips(listOf("none" to "بدون", "daily" to "يومياً", "weekly" to "أسبوعياً"), rep) { rep = it }
            }
        },
        confirmButton = {
            TextButton({
                if (title.isNotBlank()) {
                    val due = LocalDateTime.of(date, time).atZone(ZONE).toInstant().toEpochMilli()
                    onSave(Task(init?.id ?: Repo.newId(), title.trim(), desc.trim(), due, pri, rem, rep, false, 0, init?.series ?: 0))
                }
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onDismiss) { Text("إلغاء") } }
    )
    if (showD) {
        val st = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showD = false },
            confirmButton = {
                TextButton({
                    st.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    showD = false
                }) { Text("موافق") }
            }
        ) { DatePicker(st) }
    }
    if (showT) {
        val st = rememberTimePickerState(time.hour, time.minute, true)
        AlertDialog(
            onDismissRequest = { showT = false },
            confirmButton = { TextButton({ time = LocalTime.of(st.hour, st.minute); showT = false }) { Text("موافق") } },
            text = { TimePicker(st) }
        )
    }
}
