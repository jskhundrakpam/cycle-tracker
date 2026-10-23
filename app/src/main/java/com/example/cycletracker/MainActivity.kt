package com.example.cycletracker

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.core.view.WindowCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kotlin.math.sqrt

// --- DATA & LOGIC ---
data class PeriodEntry(val start: LocalDate, val end: LocalDate? = null)
data class DailyLog(
    val date: LocalDate,
    val flow: String = "",
    val mood: String = "",
    val symptoms: List<String> = emptyList(),
    val note: String = ""
)
data class AppData(
    val periods: List<PeriodEntry> = emptyList(), 
    val logs: List<DailyLog> = emptyList(),
    val defaultCycleLength: Int = 28, 
    val defaultPeriodLength: Int = 5,
    val isDarkMode: Boolean = false
)

class Store(context: Context) {
    private val file = File(context.filesDir, "data.json")
    private val _data = mutableStateOf(load())
    val data: State<AppData> get() = _data

    private fun load(): AppData {
        if (!file.exists()) return AppData()
        return try {
            val root = JSONObject(file.readText())
            val pArr = root.optJSONArray("periods") ?: JSONArray()
            val periods = (0 until pArr.length()).mapNotNull { i ->
                val o = pArr.getJSONObject(i)
                val start = runCatching { LocalDate.parse(o.getString("start")) }.getOrNull()
                val end = o.optString("end", "").takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                start?.let { PeriodEntry(it, end) }
            }
            val lArr = root.optJSONArray("logs") ?: JSONArray()
            val logs = (0 until lArr.length()).mapNotNull { i ->
                val o = lArr.getJSONObject(i)
                val date = runCatching { LocalDate.parse(o.getString("date")) }.getOrNull()
                val symptomsArr = o.optJSONArray("symptoms") ?: JSONArray()
                val symptoms = (0 until symptomsArr.length()).map { symptomsArr.getString(it) }
                date?.let { DailyLog(it, o.optString("flow"), o.optString("mood"), symptoms, o.optString("note")) }
            }
            AppData(periods, logs, root.optInt("defaultCycleLength", 28), root.optInt("defaultPeriodLength", 5), root.optBoolean("isDarkMode", false))
        } catch (e: Exception) { AppData() }
    }

    private fun save(d: AppData) {
        val pArr = JSONArray()
        d.periods.forEach { pArr.put(JSONObject().put("start", it.start.toString()).put("end", it.end?.toString() ?: "")) }
        val lArr = JSONArray()
        d.logs.forEach { l ->
            val sArr = JSONArray()
            l.symptoms.forEach { sArr.put(it) }
            lArr.put(JSONObject().put("date", l.date.toString()).put("flow", l.flow).put("mood", l.mood).put("symptoms", sArr).put("note", l.note))
        }
        val root = JSONObject()
            .put("periods", pArr).put("logs", lArr)
            .put("defaultCycleLength", d.defaultCycleLength)
            .put("defaultPeriodLength", d.defaultPeriodLength)
            .put("isDarkMode", d.isDarkMode)
        runCatching { file.writeText(root.toString()) }
        _data.value = d
    }

    fun saveLog(log: DailyLog) {
        val currentLogs = _data.value.logs.filterNot { it.date == log.date }
        save(_data.value.copy(logs = currentLogs + log))
    }

    fun getLog(date: LocalDate): DailyLog = _data.value.logs.find { it.date == date } ?: DailyLog(date)

    fun startPeriod(date: LocalDate) {
        if (_data.value.periods.any { it.start == date }) return
        save(_data.value.copy(periods = (_data.value.periods + PeriodEntry(date)).sortedBy { it.start }))
    }

    fun endPeriod(date: LocalDate) {
        val periods = _data.value.periods
        val idx = periods.indexOfLast { it.start <= date }
        if (idx < 0) return
        val updated = periods.toMutableList()
        updated[idx] = updated[idx].copy(end = date)
        save(_data.value.copy(periods = updated))
    }

    fun deletePeriod(startDate: LocalDate) = save(_data.value.copy(periods = _data.value.periods.filterNot { it.start == startDate }))
    fun setDefaults(cycle: Int, period: Int) = save(_data.value.copy(defaultCycleLength = cycle, defaultPeriodLength = period))
    fun toggleTheme() = save(_data.value.copy(isDarkMode = !_data.value.isDarkMode))
    fun clearAll() = save(AppData(defaultCycleLength = _data.value.defaultCycleLength, defaultPeriodLength = _data.value.defaultPeriodLength, isDarkMode = _data.value.isDarkMode))
    
    fun exportData(): String {
        val d = _data.value
        val sb = StringBuilder().append("--- Cycle Tracker Export ---\n\nPeriods Logged:\n")
        d.periods.forEach { sb.append("Start: ${it.start}, End: ${it.end ?: "Ongoing"}\n") }
        sb.append("\nDaily Logs:\n")
        d.logs.forEach { sb.append("Date: ${it.date} | Flow: ${it.flow} | Mood: ${it.mood} | Symptoms: ${it.symptoms.joinToString(", ")} | Note: ${it.note}\n") }
        return sb.toString()
    }
}

enum class Phase { MENSTRUAL, FOLLICULAR, OVULATION, LUTEAL }

object Predictor {
    private const val LUTEAL = 14
    fun stats(data: AppData): Triple<Int, Int, Double> {
        val periods = data.periods.sortedBy { it.start }
        val durations = periods.mapNotNull { p -> p.end?.let { ChronoUnit.DAYS.between(p.start, it).toInt() + 1 } }.filter { it in 1..10 }
        val periodLen = durations.takeLast(6).average().takeIf { !it.isNaN() }?.roundToInt() ?: data.defaultPeriodLength
        val intervals = periods.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.start, b.start).toInt() }.filter { it in 15..60 }.takeLast(6)
        if (intervals.isEmpty()) return Triple(data.defaultCycleLength, periodLen, 0.0)
        val mean = intervals.average()
        return Triple(mean.roundToInt(), periodLen, sqrt(intervals.sumOf { (it - mean) * (it - mean) } / intervals.size))
    }

    fun todayInfo(data: AppData, now: LocalDate = LocalDate.now()): Triple<Int, Phase, Pair<LocalDate, LocalDate>>? {
        val last = data.periods.sortedBy { it.start }.lastOrNull { it.start <= now } ?: return null
        val (cycle, pLen, _) = stats(data)
        var start = last.start
        var next = start.plusDays(cycle.toLong())
        while (now >= next) { start = next; next = start.plusDays(cycle.toLong()) }
        val day = ChronoUnit.DAYS.between(start, now).toInt() + 1
        val phase = when {
            day <= pLen -> Phase.MENSTRUAL
            day < cycle - LUTEAL - 1 -> Phase.FOLLICULAR
            day <= cycle - LUTEAL + 2 -> Phase.OVULATION
            else -> Phase.LUTEAL
        }
        return Triple(day, phase, Pair(next, next.minusDays(14)))
    }

    fun getCalendarMarks(data: AppData): Map<LocalDate, String> {
        val map = mutableMapOf<LocalDate, String>()
        data.periods.forEach { p ->
            var d = p.start
            val end = p.end ?: p.start
            while (!d.isAfter(end)) { map[d] = "logged"; d = d.plusDays(1) }
        }
        val last = data.periods.maxOfOrNull { it.start } ?: return map
        val (cycle, pLen, _) = stats(data)
        var predStart = last.plusDays(cycle.toLong())
        repeat(6) {
            var d = predStart
            repeat(pLen) { if (map[d] == null) map[d] = "predicted"; d = d.plusDays(1) }
            var f = predStart.minusDays(19)
            repeat(7) { if (map[f] == null) map[f] = "fertile"; f = f.plusDays(1) }
            predStart = predStart.plusDays(cycle.toLong())
        }
        return map
    }
}

// --- THEME COLORS ---
val RosePrimary = Color(0xFFD81B60)
val RoseLight = Color(0xFFFCE4EC)
val Charcoal = Color(0xFF2C2C2C)
val SoftGrey = Color(0xFFF5F5F5)
val GreenLight = Color(0xFFE8F5E9)
val CleanWhite = Color(0xFFFAFAFA)
val SearchGrey = Color(0xFFF0F0F0)

val DarkBackground = Color(0xFF121212)
val DarkSurface = Color(0xFF1E1E1E)
val DarkSurfaceVariant = Color(0xFF2C2C2C)
val DarkTextPrimary = Color(0xFFE0E0E0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        val store = Store(applicationContext)
        setContent {
            val data by store.data
            val isDark = data.isDarkMode
            
            val colorScheme = if (isDark) {
                darkColorScheme(primary = RosePrimary, onPrimary = Color.White, background = DarkBackground, surface = DarkSurface, onSurface = DarkTextPrimary)
            } else {
                lightColorScheme(primary = RosePrimary, onPrimary = Color.White, background = CleanWhite, surface = Color.White, onSurface = Charcoal)
            }

            MaterialTheme(colorScheme = colorScheme) { AppRoot(store) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(store: Store) {
    val data by store.data
    var tab by remember { mutableStateOf(0) }
    var onboarded by remember { mutableStateOf(data.periods.isNotEmpty()) }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !data.isDarkMode
            insetsController.isAppearanceLightNavigationBars = !data.isDarkMode
        }
    }

    // Custom Floating Bottom Nav & FAB
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize().statusBarsPadding()) {
                when {
                    !onboarded -> Onboarding { s, c, p -> store.setDefaults(c, p); store.startPeriod(s); onboarded = true }
                    tab == 0 -> HomeScreen(data, store)
                    tab == 1 -> CalendarScreen(data, store)
                    else -> SettingsScreen(data, store)
                }
            }
        }

        if (onboarded) {
            // The Floating Pill Bottom Nav
            Box(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(50.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 12.dp,
                    modifier = Modifier.wrapContentSize()
                ) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FloatingNavItem("Chats", Icons.Default.Home, tab == 0) { tab = 0 }
                        FloatingNavItem("Calendar", Icons.Default.DateRange, tab == 1) { tab = 1 }
                        FloatingNavItem("Settings", Icons.Default.Settings, tab == 2) { tab = 2 }
                    }
                }
            }

            // The Floating Action Button (Log Period)
            if (tab == 0) {
                FloatingActionButton(
                    onClick = { store.startPeriod(LocalDate.now()) },
                    containerColor = RosePrimary,
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(bottom = 90.dp, end = 24.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Log Period", modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}

@Composable
fun FloatingNavItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, isSelected: Boolean, onClick: () -> Unit) {
    val bgColor = if (isSelected) RoseLight.copy(alpha = if (MaterialTheme.colorScheme.background == DarkBackground) 0.2f else 1f) else Color.Transparent
    val contentColor = if (isSelected) RosePrimary else Color.Gray
    
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50.dp))
            .background(bgColor)
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = contentColor, modifier = Modifier.size(24.dp))
        if (isSelected) {
            Spacer(Modifier.width(8.dp))
            Text(label, color = contentColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@Composable
fun Onboarding(onDone: (LocalDate, Int, Int) -> Unit) {
    var start by remember { mutableStateOf(LocalDate.now()) }
    var cycle by remember { mutableStateOf(28f) }
    var period by remember { mutableStateOf(5f) }
    
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
        Text("Welcome", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = RosePrimary)
        Text("Let's personalize your tracker.", style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
        Spacer(Modifier.height(40.dp))
        
        Text("Last period started", fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { start = start.minusDays(1) }, shape = CircleShape) { Text("−", fontSize = 20.sp) }
            Text(start.format(DateTimeFormatter.ofPattern("d MMM yyyy")), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { if (!start.plusDays(1).isAfter(LocalDate.now())) start = start.plusDays(1) }, shape = CircleShape) { Text("+", fontSize = 20.sp) }
        }
        Spacer(Modifier.height(24.dp))
        
        Text("Cycle length: ${cycle.toInt()} days", fontWeight = FontWeight.SemiBold)
        Slider(value = cycle, onValueChange = { cycle = it }, valueRange = 21f..40f, steps = 18, colors = SliderDefaults.colors(thumbColor = RosePrimary, activeTrackColor = RosePrimary))
        Spacer(Modifier.height(16.dp))
        Text("Period length: ${period.toInt()} days", fontWeight = FontWeight.SemiBold)
        Slider(value = period, onValueChange = { period = it }, valueRange = 2f..10f, steps = 7, colors = SliderDefaults.colors(thumbColor = RosePrimary, activeTrackColor = RosePrimary))
        
        Spacer(Modifier.height(40.dp))
        Button(onClick = { onDone(start, cycle.toInt(), period.toInt()) }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(50.dp)) { 
            Text("Start Tracking", fontSize = 18.sp, fontWeight = FontWeight.Bold) 
        }
    }
}

@Composable
fun HomeScreen(data: AppData, store: Store) {
    val info = Predictor.todayInfo(data)
    val stats = Predictor.stats(data)
    
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(16.dp))
        
        // Big Modern Header
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Cycle Tracker", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = RosePrimary)
            // Three dots menu icon
            Text("⋮", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
        }
        
        Spacer(Modifier.height(16.dp))
        
        // Fake Search Bar
        Surface(
            shape = RoundedCornerShape(50.dp),
            color = if (data.isDarkMode) DarkSurfaceVariant else SearchGrey,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🔍", fontSize = 16.sp)
                Spacer(Modifier.width(12.dp))
                Text("Search symptoms, notes...", color = Color.Gray, fontSize = 16.sp)
            }
        }
        
        Spacer(Modifier.height(24.dp))
        
        // Pill Tabs
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50.dp)).background(if (data.isDarkMode) DarkSurfaceVariant else SearchGrey).padding(4.dp)) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(50.dp)).background(if (data.isDarkMode) DarkSurface else Color.White).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text("Current Cycle", fontWeight = FontWeight.Bold, color = if (data.isDarkMode) RosePrimary else Color.Black)
            }
            Box(Modifier.weight(1f).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text("Insights", fontWeight = FontWeight.Medium, color = Color.Gray)
            }
        }
        
        Spacer(Modifier.height(24.dp))

        if (info != null) {
            val (day, phase, dates) = info
            val (next, ov) = dates
            
            // Main Card containing the Ring
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(2.dp), shape = RoundedCornerShape(32.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { day.toFloat() / stats.first.toFloat() },
                            modifier = Modifier.fillMaxSize(),
                            color = RosePrimary,
                            trackColor = if (data.isDarkMode) DarkSurfaceVariant else Color(0xFFF0F0F0),
                            strokeWidth = 12.dp,
                            strokeCap = StrokeCap.Round
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("DAY", style = MaterialTheme.typography.labelMedium, color = Color.Gray, letterSpacing = 2.sp)
                            Text("$day", fontSize = 56.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(phase.name.lowercase().replaceFirstChar { it.uppercase() } + " Phase", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = RosePrimary)
                    Spacer(Modifier.height(16.dp))
                    
                    Divider(color = Color.Gray.copy(alpha = 0.2f))
                    Spacer(Modifier.height(16.dp))
                    
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Next Period", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                            Text(next.format(DateTimeFormatter.ofPattern("d MMM")), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Ovulation", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                            Text(ov.format(DateTimeFormatter.ofPattern("d MMM")), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text("Recent Activity", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            // Telegram-style Chat List (Recent Logs)
            if (data.logs.isNotEmpty()) {
                val recentLogs = data.logs.sortedByDescending { it.date }.take(3)
                recentLogs.forEach { log ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Avatar
                        Box(Modifier.size(50.dp).clip(CircleShape).background(RoseLight), contentAlignment = Alignment.Center) {
                            Text("📝", fontSize = 24.sp)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(log.date.format(DateTimeFormatter.ofPattern("EEEE, d MMM")), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            val sub = mutableListOf<String>()
                            if (log.flow.isNotEmpty()) sub.add("Flow: ${log.flow}")
                            if (log.mood.isNotEmpty()) sub.add("Mood: ${log.mood}")
                            Text(sub.joinToString(" • "), color = Color.Gray, fontSize = 14.sp, maxLines = 1)
                        }
                    }
                }
            } else {
                 Text("No recent logs. Tap the + button to start.", color = Color.Gray)
            }

        }
        
        // Massive spacer to ensure scrolling past the floating nav
        Spacer(Modifier.height(120.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CalendarScreen(data: AppData, store: Store) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    val marks = remember(data) { Predictor.getCalendarMarks(data) }
    val today = LocalDate.now()
    
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("Calendar", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = RosePrimary)
        Spacer(Modifier.height(24.dp))
        
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(2.dp), shape = RoundedCornerShape(32.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { month = month.minusMonths(1) }) { Text("‹", fontSize = 28.sp, color = RosePrimary, fontWeight = FontWeight.Bold) }
                    Text(month.month.name.lowercase().replaceFirstChar { it.uppercase() } + " ${month.year}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { month = month.plusMonths(1) }) { Text("›", fontSize = 28.sp, color = RosePrimary, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth()) {
                    listOf("M", "T", "W", "T", "F", "S", "S").forEach { 
                        Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, color = Color.Gray, fontSize = 12.sp) 
                    }
                }
                Spacer(Modifier.height(12.dp))

                val offset = (month.atDay(1).dayOfWeek.value + 6) % 7
                val days = month.lengthOfMonth()
                
                for (r in 0 until ((offset + days + 6) / 7)) {
                    Row(Modifier.fillMaxWidth()) {
                        for (c in 0 until 7) {
                            val dayNum = r * 7 + c - offset + 1
                            Box(Modifier.weight(1f).aspectRatio(1f).padding(3.dp), contentAlignment = Alignment.Center) {
                                if (dayNum in 1..days) {
                                    val date = month.atDay(dayNum)
                                    val mark = marks[date]
                                    val isToday = date == today
                                    val hasLog = data.logs.any { it.date == date }
                                    
                                    val bgColor = when (mark) {
                                        "logged" -> RosePrimary
                                        "predicted" -> if (data.isDarkMode) Color(0xFF4A2530) else RoseLight
                                        "fertile" -> if (data.isDarkMode) Color(0xFF1B3824) else GreenLight
                                        else -> Color.Transparent
                                    }
                                    val textColor = if (mark == "logged") Color.White else MaterialTheme.colorScheme.onSurface
                                    
                                    Box(
                                        Modifier.fillMaxSize().clip(CircleShape).background(bgColor)
                                            .then(if (isToday) Modifier.border(2.dp, RosePrimary, CircleShape) else Modifier)
                                            .clickable { selectedDate = date }, 
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(dayNum.toString(), color = textColor, fontWeight = if (mark != null || isToday) FontWeight.Bold else FontWeight.Normal)
                                        if (hasLog) {
                                            Box(Modifier.size(4.dp).clip(CircleShape).background(if (mark == "logged") Color.White else RosePrimary).align(Alignment.BottomCenter).offset(y = (-4).dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        Spacer(Modifier.height(24.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(1.dp), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                LegendItem(RosePrimary, "Period")
                LegendItem(if (data.isDarkMode) Color(0xFF4A2530) else RoseLight, "Predicted")
                LegendItem(if (data.isDarkMode) Color(0xFF1B3824) else GreenLight, "Fertile")
            }
        }
        
        Spacer(Modifier.height(120.dp))
    }
    
    if (selectedDate != null) {
        LogDayBottomSheet(
            date = selectedDate!!, initialLog = store.getLog(selectedDate!!),
            isPeriodStart = data.periods.any { it.start == selectedDate },
            onDismiss = { selectedDate = null },
            onSave = { log -> store.saveLog(log); selectedDate = null },
            onDeletePeriod = { store.deletePeriod(selectedDate!!); selectedDate = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LogDayBottomSheet(date: LocalDate, initialLog: DailyLog, isPeriodStart: Boolean, onDismiss: () -> Unit, onSave: (DailyLog) -> Unit, onDeletePeriod: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var flow by remember { mutableStateOf(initialLog.flow) }
    var mood by remember { mutableStateOf(initialLog.mood) }
    var note by remember { mutableStateOf(initialLog.note) }
    val symptoms = remember { mutableStateListOf<String>().apply { addAll(initialLog.symptoms) } }
    
    val flowOptions = listOf("Spotting", "Light", "Medium", "Heavy")
    val moodOptions = listOf("Happy", "Calm", "Anxious", "Irritable", "Sad")
    val symptomOptions = listOf("Cramps", "Headache", "Bloating", "Acne", "Backache", "Fatigue")

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(24.dp).verticalScroll(rememberScrollState())) {
            Text(date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))
            
            Text("Flow", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            FlowRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                flowOptions.forEach { option -> FilterChip(selected = flow == option, onClick = { flow = if (flow == option) "" else option }, label = { Text(option) }) }
            }
            
            Spacer(Modifier.height(12.dp))
            Text("Mood", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            FlowRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                moodOptions.forEach { option -> FilterChip(selected = mood == option, onClick = { mood = if (mood == option) "" else option }, label = { Text(option) }) }
            }
            
            Spacer(Modifier.height(12.dp))
            Text("Symptoms", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            FlowRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                symptomOptions.forEach { option -> FilterChip(selected = symptoms.contains(option), onClick = { if (symptoms.contains(option)) symptoms.remove(option) else symptoms.add(option) }, label = { Text(option) }) }
            }
            
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            
            Spacer(Modifier.height(24.dp))
            Button(onClick = { onSave(DailyLog(date, flow, mood, symptoms.toList(), note)) }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(50.dp)) { Text("Save Log", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            
            if (isPeriodStart) {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDeletePeriod, modifier = Modifier.fillMaxWidth()) { Text("Remove Period Start for this Day", color = Color.Red, fontWeight = FontWeight.SemiBold) }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = Color.Gray)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(data: AppData, store: Store) {
    val clipboardManager = LocalClipboardManager.current
    var showCopiedText by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("Settings", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = RosePrimary)
        Spacer(Modifier.height(24.dp))
        
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(2.dp), shape = RoundedCornerShape(32.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Dark Mode", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("Easier on the eyes at night", color = Color.Gray, fontSize = 14.sp)
                }
                Switch(checked = data.isDarkMode, onCheckedChange = { store.toggleTheme() }, colors = SwitchDefaults.colors(checkedTrackColor = RosePrimary))
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(2.dp), shape = RoundedCornerShape(32.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp)) {
                Text("Your Data", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                Text("${data.periods.size} periods logged.", color = Color.Gray, fontSize = 14.sp)
                Text("${data.logs.size} daily logs recorded.", color = Color.Gray, fontSize = 14.sp)
            }
        }
        
        Spacer(Modifier.height(24.dp))
        
        Button(
            onClick = { clipboardManager.setText(AnnotatedString(store.exportData())); showCopiedText = true }, 
            modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
        ) { Text(if (showCopiedText) "Copied to Clipboard!" else "Copy Data to Clipboard", fontWeight = FontWeight.Bold, fontSize = 16.sp) }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = { store.clearAll() }, 
            colors = ButtonDefaults.buttonColors(containerColor = if (data.isDarkMode) Color(0xFF3E1E1E) else Color(0xFFFFEBEE), contentColor = Color.Red), 
            modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(50.dp)
        ) { Text("Delete All Data", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        
        Spacer(Modifier.height(120.dp))
    }
}
