package com.example.cycletracker

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
data class AppData(val periods: List<PeriodEntry> = emptyList(), val defaultCycleLength: Int = 28, val defaultPeriodLength: Int = 5)

class Store(context: Context) {
    private val file = File(context.filesDir, "data.json")
    private val _data = mutableStateOf(load())
    val data: State<AppData> get() = _data

    private fun load(): AppData {
        if (!file.exists()) return AppData()
        return try {
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("periods") ?: JSONArray()
            val periods = (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val start = runCatching { LocalDate.parse(o.getString("start")) }.getOrNull()
                val end = o.optString("end", "").takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                start?.let { PeriodEntry(it, end) }
            }
            AppData(periods, root.optInt("defaultCycleLength", 28), root.optInt("defaultPeriodLength", 5))
        } catch (e: Exception) { AppData() }
    }

    private fun save(d: AppData) {
        val arr = JSONArray()
        d.periods.forEach { arr.put(JSONObject().put("start", it.start.toString()).put("end", it.end?.toString() ?: "")) }
        runCatching { file.writeText(JSONObject().put("periods", arr).put("defaultCycleLength", d.defaultCycleLength).put("defaultPeriodLength", d.defaultPeriodLength).toString()) }
        _data.value = d
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

    // Generates a map of dates to "logged", "predicted", or "fertile" for the calendar
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

        // Predict next 6 cycles
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

// --- UI ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(applicationContext)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFFB4476A), background = Color(0xFFFFF8F6))) {
                AppRoot(store)
            }
        }
    }
}

@Composable
fun AppRoot(store: Store) {
    val data by store.data
    var tab by remember { mutableStateOf(0) }
    var onboarded by remember { mutableStateOf(data.periods.isNotEmpty()) }

    Scaffold(bottomBar = {
        if (onboarded) NavigationBar(containerColor = Color.White) {
            NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("◉") }, label = { Text("Home") })
            NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("▤") }, label = { Text("Calendar") })
            NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Text("⚙") }, label = { Text("Settings") })
        }
    }) { pad ->
        Box(Modifier.padding(pad)) {
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
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Welcome", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        Text("Last period started: ${start.format(DateTimeFormatter.ofPattern("d MMM yyyy"))}", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(onClick = { start = start.minusDays(1) }) { Text("−") }
            OutlinedButton(onClick = { if (!start.plusDays(1).isAfter(LocalDate.now())) start = start.plusDays(1) }) { Text("+") }
        }
        Spacer(Modifier.height(16.dp))
        
        Text("Cycle length: ${cycle.toInt()} days")
        Slider(value = cycle, onValueChange = { cycle = it }, valueRange = 21f..40f, steps = 18)
        
        Text("Period length: ${period.toInt()} days")
        Slider(value = period, onValueChange = { period = it }, valueRange = 2f..10f, steps = 7)
        
        Spacer(Modifier.height(24.dp))
        Button(onClick = { onDone(start, cycle.toInt(), period.toInt()) }, modifier = Modifier.fillMaxWidth().height(50.dp)) { 
            Text("Start Tracking", fontSize = MaterialTheme.typography.titleMedium.fontSize) 
        }
    }
}

@Composable
fun HomeScreen(data: AppData, store: Store) {
    val info = Predictor.todayInfo(data)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        if (info != null) {
            val (day, phase, dates) = info
            val (next, ov) = dates
            
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(20.dp)) {
                    Text("Cycle Day $day", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Color(0xFFB4476A))
                    Text("Phase: ${phase.name.lowercase().replaceFirstChar { it.uppercase() }}", style = MaterialTheme.typography.titleMedium)
                    
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { day.toFloat() / Predictor.stats(data).first.toFloat() },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                        color = Color(0xFFB4476A),
                        trackColor = Color(0xFFF3C6D3)
                    )
                    Spacer(Modifier.height(16.dp))
                    
                    Text("Next period: ${next.format(DateTimeFormatter.ofPattern("d MMM"))}", fontWeight = FontWeight.Medium)
                    Text("Ovulation: ~${ov.format(DateTimeFormatter.ofPattern("d MMM"))}", color = Color.Gray)
                }
            }
        } else {
            Text("Log your first period in the Calendar tab.", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = { store.startPeriod(LocalDate.now()) }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Period Started Today") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { store.endPeriod(LocalDate.now()) }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Period Ended Today") }
    }
}

@Composable
fun CalendarScreen(data: AppData, store: Store) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selected by remember { mutableStateOf<LocalDate?>(null) }
    val marks = remember(data) { Predictor.getCalendarMarks(data) }
    
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹", fontSize = MaterialTheme.typography.headlineMedium.fontSize) }
            Text(month.month.name.lowercase().replaceFirstChar { it.uppercase() } + " ${month.year}", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { month = month.plusMonths(1) }) { Text("›", fontSize = MaterialTheme.typography.headlineMedium.fontSize) }
        }
        
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach { 
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, color = Color.Gray) 
            }
        }

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
                            val bgColor = when (mark) {
                                "logged" -> Color(0xFFB4476A)
                                "predicted" -> Color(0xFFF3C6D3)
                                "fertile" -> Color(0xFFD9EFE3)
                                else -> Color.Transparent
                            }
                            Box(Modifier.fillMaxSize().clip(CircleShape).background(bgColor).clickable { selected = date }, contentAlignment = Alignment.Center) {
                                Text(dayNum.toString(), color = if (mark == "logged") Color.White else Color.Black, fontWeight = if (mark != null) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }
        }
        
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            LegendItem(Color(0xFFB4476A), "Period")
            LegendItem(Color(0xFFF3C6D3), "Predicted")
            LegendItem(Color(0xFFD9EFE3), "Fertile")
        }
    }
    
    selected?.let { date ->
        val isLogged = data.periods.any { it.start == date }
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(date.format(DateTimeFormatter.ofPattern("d MMM yyyy"))) },
            text = { Text(if (isLogged) "This day is logged as a period start." else "What would you like to log?") },
            confirmButton = { 
                if (isLogged) {
                    TextButton(onClick = { store.deletePeriod(date); selected = null }) { Text("Delete Entry", color = Color.Red) }
                } else {
                    TextButton(onClick = { store.startPeriod(date); selected = null }) { Text("Start Period") } 
                }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Cancel") } }
        )
    }
}

@Composable
fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun SettingsScreen(data: AppData, store: Store) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        
        Text("Total periods logged: ${data.periods.size}", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(32.dp))
        
        Button(onClick = { store.clearAll() }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red), modifier = Modifier.fillMaxWidth()) { 
            Text("Delete All Data") 
        }
    }
}
