package com.example.homeezch

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

@Suppress("DEPRECATION")
private suspend fun locate(context: Context): Location? = withTimeoutOrNull(15000L) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return@withTimeoutOrNull null
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return@withTimeoutOrNull null
    suspendCancellableCoroutine { continuation ->
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                manager.removeUpdates(this)
                if (continuation.isActive) continuation.resume(location)
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }
            override fun onProviderEnabled(provider: String) { }
            override fun onProviderDisabled(provider: String) { }
        }
        continuation.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }
        runCatching {
            val providers = manager.getProviders(true)
            val provider = when { LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
                "fused" in providers -> "fused"
                else -> null }
            if (provider == null) { continuation.resume(null); return@runCatching }
            val last = manager.getLastKnownLocation(provider)
            if (last != null && android.os.SystemClock.elapsedRealtimeNanos() - last.elapsedRealtimeNanos in 0..(60*60*1_000_000_000L)) continuation.resume(last)
            else manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        }.onFailure { if (continuation.isActive) continuation.resume(null) }
    }
}

@Composable
internal fun WeatherSlot(refresh: Int, ready: Boolean = true, homeReset: Int = 0) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val prefs = remember { context.getSharedPreferences("ezch_launcher_prefs", 0) }
    var auto by remember { mutableStateOf(prefs.getBoolean("weather_auto", true)) }
    var point by remember { mutableStateOf(WeatherAccess.loadPoint(context)) }
    var networkLocation by remember { mutableStateOf(prefs.getBoolean("weather_network_location", false)) }
    var result by remember { mutableStateOf(WeatherResult(null, "Определить местоположение")) }
    var revision by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(homeReset) { if (homeReset > 0) open = false }
    var query by remember { mutableStateOf(point?.city.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var updateOwner by remember { mutableStateOf<Any?>(null) }
    val updating = updateOwner != null
    var message by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<WeatherPoint>>(emptyList()) }
    var focused by remember { mutableStateOf(false) }
    fun choose(newPoint: WeatherPoint, automatic: Boolean, fromNetwork: Boolean = false) {
        if (busy) return
        busy = true
        scope.launch {
            val saved = withContext(Dispatchers.IO) { WeatherAccess.savePoint(context, newPoint) }
            busy = false
            if (saved) {
                auto = automatic; networkLocation = fromNetwork
                prefs.edit().putBoolean("weather_auto", automatic).putBoolean("weather_network_location", fromNetwork)
                    .putLong("weather_location_time", System.currentTimeMillis()).apply()
                point = newPoint; revision++; candidates = emptyList(); message = "Местоположение сохранено"
            } else message = "Не удалось сохранить местоположение"
        }
    }
    fun detect(force: Boolean = false) {
        if (busy) return
        val age = System.currentTimeMillis() - prefs.getLong("weather_location_time", 0)
        if (!force && point != null && age in 0 until 6*60*60*1000L) return
        busy = true; message = "Определяем местоположение…"
        if (point == null) result = WeatherResult(null, message)
        scope.launch {
            try {
                val location = locate(context)
                val city = location?.let { withContext(Dispatchers.IO) { runCatching {
                    @Suppress("DEPRECATION")
                    Geocoder(context, Locale("ru")).getFromLocation(it.latitude, it.longitude, 1)?.firstOrNull()?.locality
                }.getOrNull() } }
                val approximate = if (location == null) NetworkLocation.load() else null
                busy = false
                if (location != null) choose(WeatherPoint(city ?: "Моё местоположение", location.latitude, location.longitude), true)
                else if (approximate != null) {
                    choose(approximate, true, true)
                    message = "Город определён по сети. При VPN можно выбрать город вручную."
                } else {
                    message = "Локация и сеть недоступны. Выберите город вручную."
                    if (point == null) result = WeatherResult(null, "Нет локации / сети")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { busy = false; message = "Локация недоступна. Выберите город." }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        detect(force = true)
    }
    LaunchedEffect(ready, refresh, auto, lifecycle) {
        if (!ready || !auto) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (!granted && !prefs.getBoolean("weather_prompt_v09", false)) {
                    prefs.edit().putBoolean("weather_prompt_v09", true).apply()
                    permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                } else detect()
                delay(15*60*1000L)
            }
        }
    }
    LaunchedEffect(point, revision, refresh, ready, lifecycle) {
        if (!ready) return@LaunchedEffect
        val selected = point
        if (selected == null) { result = WeatherResult(null, if (busy) "Определяем…" else "Ожидаем локацию"); return@LaunchedEffect }
        if (result.reading == null) result = WeatherResult(null, "Загрузка…")
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var forced = revision > 0
            while (true) {
                val owner = Any()
                updateOwner = owner
                try {
                    result = OpenMeteoWeather.load(context, selected, forced)
                } finally {
                    if (updateOwner === owner) updateOwner = null
                }
                forced = result.status.isNotBlank()
                delay(if (result.status.isNotBlank()) 60_000L else 30*60*1000L)
            }
        }
    }
    Column(Modifier.width(104.dp).clip(DesktopShape).border(if (focused) 4.dp else 0.dp,
        if (focused) Color(0xFF48B9FF) else Color.Transparent, DesktopShape)
        .onFocusChanged { focused = it.isFocused }.clickable { query = point?.city.orEmpty(); open = true }.padding(4.dp)) {
        Text(point?.city ?: "Город", fontSize = 11.sp, color = Color.White, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text(result.reading?.let { "${it.temperature}° · ${OpenMeteoWeather.condition(it.condition)}" } ?: "Погода: —",
            fontSize = 11.sp, color = Color.White, maxLines = 1)
        Text(result.status.ifBlank { if (networkLocation) "Open-Meteo · по сети" else "Open-Meteo" }, fontSize = 8.sp, color = Color(0xFFB6C6DA), maxLines = 1)
    }
    if (open) AlertDialog(onDismissRequest = { if (!busy) open = false }, title = { Text("Погода по местоположению") }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            Text("Open-Meteo · без ключа · некоммерческое использование", fontSize = 12.sp)
            Text(result.status, fontSize = 12.sp)
            TextButton(enabled = point != null && !busy && !updating, onClick = { revision++; message = "" }) { Text("Обновить погоду сейчас") }
            Text("Если ТВ не передаёт локацию, город определяется примерно по внешнему IP через IPWhois. VPN может менять город.", fontSize = 12.sp)
            TextButton(enabled = !busy, onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) detect(force = true)
                else permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }) { Text("Авто — определить местоположение") }
            TextButton(onClick = { SettingsRouter.open(context, listOf(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS), Intent(android.provider.Settings.ACTION_SETTINGS))) }) { Text("Включить геолокацию") }
            OutlinedTextField(query, { query = it.take(80); candidates = emptyList() }, label = { Text("Город, если ТВ не передаёт локацию") }, singleLine = true)
            TextButton(enabled = query.isNotBlank() && !busy, onClick = {
                busy = true; message = "Ищем город…"
                val search = query
                scope.launch {
                    try {
                        candidates = OpenMeteoWeather.search(search)
                        message = if (candidates.isEmpty()) "Город не найден или нет сети" else "Выберите город ниже"
                    } finally { busy = false }
                }
            }) { Text("Найти город") }
            candidates.forEach { candidate -> TextButton(enabled = !busy, onClick = { choose(candidate, false) }) { Text(candidate.city) } }
            result.reading?.let { reading -> Text("Получено: ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(reading.fetchedAt))}", fontSize = 12.sp) }
            Text(if (updating) "Обновляем погоду…" else message, fontSize = 12.sp)
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://open-meteo.com/"))) }.onFailure { message = "Браузер недоступен" }
            }) { Text("Данные: Open-Meteo · CC BY 4.0") }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = { open = false }) { Text("Закрыть") } })
}
