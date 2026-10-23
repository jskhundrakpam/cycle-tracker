package com.example.cycletracker

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
    val defaultPeriodLength: Int = 5
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

            AppData(
                periods, logs,
                root.optInt("defaultCycleLength", 28), 
                root.optInt("defaultPeriodLength", 5)
            )
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
            .put("periods", pArr)
            .put("logs", lArr)
            .put("defaultCycleLength", d.defaultCycleLength)
            .put("defaultPeriodLength", d.defaultPeriodLength)

        runCatching { file.writeText(root.toString()) }
        _data.value = d
    }

    fun saveLog(log: DailyLog) {
        val currentLogs = _data.value.logs.filterNot { it.date == log.date }
        save(_data.value.copy(logs = currentLogs + log))
    }

    fun getLog(date: LocalDate): DailyLog {
        return _data.value.logs.find { it.date == date } ?: DailyLog(date)
    }

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

    fun deletePeriod(startDate: LocalDate) {
        save(_data.value.copy(periods = _data.value.periods.filterNot { it.start == startDate }))
    }

    fun setDefaults(cycle: Int, period: Int) = save(_data.value.copy(defaultCycleLength = cycle, defaultPeriodLength = period))
    fun clearAll() = save(AppData(defaultCycleLength = _data.value.defaultCycleLength, defaultPeriodLength = _data.value.defaultPeriodLength))
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
        val ov = next.minusDays(14)
        return Triple(day, phase, Pair(next, ov))
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
            val ov = predStart.minusDays(14)
            var f = ov.minusDays(5)
            repeat(7) { if (map[f] == null) map[f] = "fertile"; f = f.plusDays(1) }
            predStart = predStart.plusDays(cycle.toLong())
        }
        return map
    }
}

// --- UI THEME COLORS ---
val RosePrimary = Color(0xFFD81B60)
val RoseLight = Color(0xFFFCE4EC)
val Charcoal = Color(0xFF2C2C2C)
val SoftGrey = Color(0xFFF5F5F5)
val GreenLight = Color(0xFFE8F5E9)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(applicationContext)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = RosePrimary,
                    onPrimary = Color.White,
                    background = Color(0xFFFDFBFB),
                    surface = Color.White,
                    onSurface = Charcoal
                )
            ) {
                AppRoot(store)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(store: Store) {
    val data by store.data
    var tab by remember { mutableStateOf(0) }
    var onboarded by remember { mutableStateOf(data.periods.isNotEmpty()) }

    Scaffold(
        bottomBar = {
            if (onboarded) {
                NavigationBar(containerColor = Color.White, tonalElevation = 8.dp) {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("◉", fontSize = 20.sp) }, label = { Text("Home") })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("▤", fontSize = 20.sp) }, label = { Text("Calendar") })
                    NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Text("⚙", fontSize = 20.sp) }, label = { Text("Settings") })
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                !onboarded -> Onboarding { s, c, p -> store.setDefaults(c, p); store.startPeriod(s); onboarded = true }
                tab == 0 -> HomeScreen(data, store)
                tab == 1 -> CalendarScreen(data, store)
                else -> SettingsScreen(data, store)
            }
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
        Button(onClick = { onDone(start, cycle.toInt(), period.toInt()) }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) { 
            Text("Start Tracking", fontSize = 18.sp, fontWeight = FontWeight.Bold) 
        }
    }
}

@Composable
fun HomeScreen(data: AppData, store: Store) {
    val info = Predictor.todayInfo(data)
    val todayLog = store.getLog(LocalDate.now())
    val stats = Predictor.stats(data)
    
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("Overview", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        if (info != null) {
            val (day, phase, dates) = info
            val (next, ov) = dates
            
            Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { day.toFloat() / stats.first.toFloat() },
                    modifier = Modifier.size(200.dp),
                    color = RosePrimary,
                    trackColor = RoseLight,
                    strokeWidth = 16.dp
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Day", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
                    Text("$day", fontSize = 56.sp, fontWeight = FontWeight.ExtraBold, color = RosePrimary)
                }
            }
            
            Spacer(Modifier.height(16.dp))
            
            // Insights Card
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(2.dp), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Avg Cycle", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                        Text("${stats.first} days", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                    Column {
                        Text("Avg Period", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                        Text("${stats.second} days", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Next Period", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                        Text(next.format(DateTimeFormatter.ofPattern("d MMM")), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = RosePrimary)
                    }
                }
            }

            if (todayLog.flow.isNotEmpty() || todayLog.mood.isNotEmpty() || todayLog.symptoms.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Card(colors = CardDefaults.cardColors(containerColor = SoftGrey), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Today's Log", fontWeight = FontWeight.Bold, color = Charcoal)
                        Spacer(Modifier.height(4.dp))
                        if (todayLog.flow.isNotEmpty()) Text("Flow: ${todayLog.flow}", fontSize = 14.sp)
                        if (todayLog.mood.isNotEmpty()) Text("Mood: ${todayLog.mood}", fontSize = 14.sp)
                        if (todayLog.symptoms.isNotEmpty()) Text("Symptoms: ${todayLog.symptoms.joinToString(", ")}", fontSize = 14.sp)
                    }
                }
            }

        } else {
            Text("No data yet. Log your first period in the Calendar tab.", color = Color.Gray)
        }
        
        Spacer(Modifier.height(32.dp))
        
        Button(onClick = { store.startPeriod(LocalDate.now()) }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) { 
            Text("Log Period Started Today", fontSize = 16.sp, fontWeight = FontWeight.Bold) 
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { store.endPeriod(LocalDate.now()) }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(2.dp, RosePrimary)) { 
            Text("Log Period Ended Today", color = RosePrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold) 
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CalendarScreen(data: AppData, store: Store) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    val marks = remember(data) { Predictor.getCalendarMarks(data) }
    val today = LocalDate.now()
    
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { month = month.minusMonths(1) }) { Text("‹", fontSize = 28.sp, color = RosePrimary) }
            Text(month.month.name.lowercase().replaceFirstChar { it.uppercase() } + " ${month.year}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            IconButton(onClick = { month = month.plusMonths(1) }) { Text("›", fontSize = 28.sp, color = RosePrimary) }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach { 
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, color = Color.Gray) 
            }
        }
        Spacer(Modifier.height(8.dp))

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
                                "predicted" -> RoseLight
                                "fertile" -> GreenLight
                                else -> Color.Transparent
                            }
                            val textColor = if (mark == "logged") Color.White else Charcoal
                            
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
        Spacer(Modifier.height(32.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(2.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                LegendItem(RosePrimary, "Period")
                LegendItem(RoseLight, "Predicted")
                LegendItem(GreenLight, "Fertile")
            }
        }
    }
    
    if (selectedDate != null) {
        LogDayBottomSheet(
            date = selectedDate!!,
            initialLog = store.getLog(selectedDate!!),
            isPeriodStart = data.periods.any { it.start == selectedDate },
            onDismiss = { selectedDate = null },
            onSave = { log -> store.saveLog(log); selectedDate = null },
            onDeletePeriod = { store.deletePeriod(selectedDate!!); selectedDate = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LogDayBottomSheet(
    date: LocalDate,
    initialLog: DailyLog,
    isPeriodStart: Boolean,
    onDismiss: () -> Unit,
    onSave: (DailyLog) -> Unit,
    onDeletePeriod: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var flow by remember { mutableStateOf(initialLog.flow) }
    var mood by remember { mutableStateOf(initialLog.mood) }
    var note by remember { mutableStateOf(initialLog.note) }
    val symptoms = remember { mutableStateListOf<String>().apply { addAll(initialLog.symptoms) } }
    
    val flowOptions = listOf("Spotting", "Light", "Medium", "Heavy")
    val moodOptions = listOf("Happy", "Calm", "Anxious", "Irritable", "Sad")
    val symptomOptions = listOf("Cramps", "Headache", "Bloating", "Acne", "Backache", "Fatigue")

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().padding(24.dp).verticalScroll(rememberScrollState())) {
            Text(date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            
            Text("Flow", fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                flowOptions.forEach { option ->
                    FilterChip(selected = flow == option, onClick = { flow = if (flow == option) "" else option }, label = { Text(option) })
                }
            }
            
            Text("Mood", fontWeight = FontWeight.SemiBold)
            FlowRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                moodOptions.forEach { option ->
                    FilterChip(selected = mood == option, onClick = { mood = if (mood == option) "" else option }, label = { Text(option) })
                }
            }
            
            Text("Symptoms", fontWeight = FontWeight.SemiBold)
            FlowRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                symptomOptions.forEach { option ->
                    FilterChip(
                        selected = symptoms.contains(option),
                        onClick = { if (symptoms.contains(option)) symptoms.remove(option) else symptoms.add(option) },
                        label = { Text(option) }
                    )
                }
            }
            
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                shape = RoundedCornerShape(12.dp)
            )
            
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { onSave(DailyLog(date, flow, mood, symptoms.toList(), note)) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp)
            ) { Text("Save Log") }
            
            if (isPeriodStart) {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDeletePeriod, modifier = Modifier.fillMaxWidth()) {
                    Text("Remove Period Start for this Day", color = Color.Red)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SettingsScreen(data: AppData, store: Store) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        
        Card(colors = CardDefaults.cardColors(containerColor = SoftGrey), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("Your Data", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                Text("${data.periods.size} periods logged.", color = Color.Gray)
                Text("${data.logs.size} daily logs recorded.", color = Color.Gray)
            }
        }
        
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = { store.clearAll() }, 
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFEBEE), contentColor = Color.Red), 
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) { 
            Text("Delete All Data", fontWeight = FontWeight.Bold, fontSize = 16.sp) 
        }
    }
}
