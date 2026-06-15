package com.example

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.PrimaryNeon
import com.example.ui.theme.SecondaryNeon
import com.example.ui.theme.TertiaryNeon

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    MainScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun AppBottomNav(currentTab: Int, onTabSelected: (Int) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        NavigationBarItem(selected = currentTab == 0, onClick = { onTabSelected(0) }, icon = { Text("⚙️") }, label = { Text("Settings") })
        NavigationBarItem(selected = currentTab == 1, onClick = { onTabSelected(1) }, icon = { Text("ℹ️") }, label = { Text("About") })
    }
}

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var hasOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasAccessibility by remember { mutableStateOf(isAccessibilityEnabled(context)) }
    var hasAudioPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    var currentTab by remember { mutableStateOf(0) }

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlay = Settings.canDrawOverlays(context)
                hasAccessibility = isAccessibilityEnabled(context)
                hasAudioPermission = ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (hasOverlay && hasAccessibility && hasAudioPermission) {
        Scaffold(
            bottomBar = { AppBottomNav(currentTab, { currentTab = it }) }
        ) { padding ->
            if (currentTab == 0) {
                SettingsScreen(modifier = modifier.padding(padding))
            } else {
                AboutScreen(modifier = modifier.padding(padding))
            }
        }
    } else {
        SetupScreen(
            modifier = modifier,
            hasOverlay = hasOverlay,
            hasAccessibility = hasAccessibility,
            hasAudioPermission = hasAudioPermission,
            onAudioPermissionGranted = { hasAudioPermission = true },
            context = context
        )
    }
}

@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("About Developer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = PrimaryNeon)
        
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Name: Rakibul", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text("College: Kapilmuni College", fontSize = 16.sp)
                Text("Location: Khulna, Bangladesh", fontSize = 16.sp)
                Text("Email: mr4425390@gmail.com", fontSize = 16.sp, color = PrimaryNeon)
            }
        }
        
        Spacer(Modifier.height(16.dp))
        
        Text("App Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("WaveScroll Pro\nCreated for immersive, touch-free scrolling on TikTok, YouTube Shorts, and Instagram Reels.\nThis version features live audio visualization and customizable smart gestures.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = context.getSharedPreferences("WaveScrollPrefs", Context.MODE_PRIVATE)
    
    var doubleWaveTime by remember { mutableFloatStateOf(prefs.getLong("doubleWaveTime", 250L).toFloat()) }
    var longPressTime by remember { mutableFloatStateOf(prefs.getLong("longPressTime", 1000L).toFloat()) }
    var scrollSpeed by remember { mutableFloatStateOf(prefs.getLong("scrollSpeed", 300L).toFloat()) }

    var btnColor by remember { mutableStateOf(prefs.getString("btnColor", "Green/Red") ?: "Green/Red") }
    var btnSize by remember { mutableStateOf(prefs.getString("btnSize", "Medium") ?: "Medium") }
    var btnPosition by remember { mutableStateOf(prefs.getString("btnPosition", "Top-End") ?: "Top-End") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("💡 ", fontSize = 28.sp)
                Column {
                    Text("Gesture Control", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = PrimaryNeon))
                    Text("Customize your gesture experience", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text("▶ Preview", color = PrimaryNeon)
            }
        }

        // Tips Card with Glow
        Box(
            modifier = Modifier
                .shadow(8.dp, RoundedCornerShape(16.dp))
                .background(
                    brush = Brush.linearGradient(listOf(Color(0xFF2A1C20), Color(0xFF1E1724))),
                    shape = RoundedCornerShape(16.dp)
                )
                .clip(RoundedCornerShape(16.dp))
        ) {
            Text(
                text = "💡 টিপস:\n- একবার হাত নাড়ালে ভিডিও চেঞ্জ হবে।\n- দ্রুত দুইবার হাত নাড়ালে ভিডিও লাইক হবে।\n- কিছুক্ষণ হাত ধরে রাখলে ভিডিও Pause/Play হবে।\n- স্ক্রিনে 'Active' লেখায় বারবার ২ বার টাচ করলে অপশনটি অফ/অন হবে।",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
        }

        // Action Grid
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionCube(Modifier.weight(1f), "🖐", "Wave", "Like", Color(0xFF42A5F5))
            ActionCube(Modifier.weight(1f), "✋", "Hold", "Play/Pause", Color(0xFFFFA726))
            ActionCube(Modifier.weight(1f), "↔️", "Swipe", "Scroll", Color(0xFFAB47BC))
            ActionCube(Modifier.weight(1f), "👆", "Tap", "Toggle", Color(0xFF66BB6A))
        }

        // Timings Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Gesture Timings", fontWeight = FontWeight.Bold)
                    Text("↺ Restore Defaults", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                
                NeonSliderRow("〰️", "Double Wave Delay", "দুইবার হাত নাড়ানোর সময়ের ব্যবধান", doubleWaveTime, 150f..1000f, SecondaryNeon) { 
                    doubleWaveTime = it; prefs.edit().putLong("doubleWaveTime", it.toLong()).apply() 
                }
                
                NeonSliderRow("⏸", "Hold Time (Video Play/Pause)", "হাত ধরে রাখার সময়", longPressTime, 500f..2500f, PrimaryNeon) { 
                    longPressTime = it; prefs.edit().putLong("longPressTime", it.toLong()).apply() 
                }
                
                NeonSliderRow("↕", "Scroll Swipe Speed", "স্ক্রোল করার গতি", scrollSpeed, 100f..1000f, Color(0xFF66BB6A)) { 
                    scrollSpeed = it; prefs.edit().putLong("scrollSpeed", it.toLong()).apply() 
                }
            }
        }

        // UI Settings
        Text("Active Button UI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Color Scheme", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SettingDropdown("", btnColor, listOf("Green/Red", "Blue/Yellow")) { 
                        btnColor = it; prefs.edit().putString("btnColor", it).apply() 
                    }
                }
            }
            Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
               Column(Modifier.padding(12.dp)) {
                    Text("Bubble Size", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    var scale by remember { androidx.compose.runtime.mutableFloatStateOf(prefs.getFloat("bubbleScale", 1.0f)) }
                    androidx.compose.material3.Slider(
                        value = scale,
                        onValueChange = { 
                            scale = it
                            prefs.edit().putFloat("bubbleScale", it).apply() 
                        },
                        valueRange = 0.5f..2.0f
                    )
                }
            }
        }
        
        // Custom App Settings
        Text("Custom Apps (Package Names)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        var customAppInput by remember { mutableStateOf("") }
        var customAppsList by remember { mutableStateOf(prefs.getStringSet("customApps", emptySet()) ?: emptySet()) }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = customAppInput,
                    onValueChange = { customAppInput = it },
                    label = { Text("e.g. com.facebook.katana") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryNeon
                    ),
                    trailingIcon = {
                        TextButton(onClick = {
                            if (customAppInput.isNotBlank()) {
                                val newList = customAppsList.toMutableSet()
                                newList.add(customAppInput.trim())
                                customAppsList = newList
                                prefs.edit().putStringSet("customApps", newList).apply()
                                customAppInput = ""
                            }
                        }) {
                            Text("Add", color = PrimaryNeon)
                        }
                    }
                )

                if (customAppsList.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        customAppsList.forEach { app ->
                            Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(app, fontSize = 14.sp)
                                TextButton(onClick = {
                                    val newList = customAppsList.toMutableSet()
                                    newList.remove(app)
                                    customAppsList = newList
                                    prefs.edit().putStringSet("customApps", newList).apply()
                                }) {
                                    Text("Remove", color = Color.Red, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        
        SettingDropdown("Position on screen", btnPosition, listOf("Top-Start", "Top-End", "Bottom-Start", "Bottom-End")) { 
            btnPosition = it; prefs.edit().putString("btnPosition", it).apply() 
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun ActionCube(modifier: Modifier = Modifier, icon: String, title: String, subtitle: String, tint: Color) {
    Card(
        modifier = modifier.aspectRatio(0.85f),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(tint.copy(alpha = 0.2f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(icon, fontSize = 20.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun NeonSliderRow(icon: String, title: String, sub: String, value: Float, range: ClosedFloatingPointRange<Float>, neonColor: Color, onValueChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10f)),
            contentAlignment = Alignment.Center
        ) {
            Text(icon, color = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("${value.toInt()} ms", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = range,
                colors = SliderDefaults.colors(thumbColor = neonColor, activeTrackColor = neonColor, inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingDropdown(label: String, selectedValue: String, options: List<String>, onSelectionChanged: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selectedValue,
            onValueChange = {},
            readOnly = true,
            label = if (label.isNotEmpty()) { { Text(label) } } else null,
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(
                unfocusedBorderColor = Color.Transparent,
                focusedBorderColor = PrimaryNeon
            ),
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelectionChanged(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun SetupScreen(
    modifier: Modifier = Modifier,
    hasOverlay: Boolean,
    hasAccessibility: Boolean,
    hasAudioPermission: Boolean,
    onAudioPermissionGranted: () -> Unit,
    context: Context
) {
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) {
                onAudioPermissionGranted()
            }
        }
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Box(modifier = Modifier.size(64.dp).background(PrimaryNeon.copy(alpha=0.2f), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            Text("🖐", fontSize = 32.sp)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "WaveScroll Setup",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = PrimaryNeon
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "এই অ্যাপটি টিকটক, YouTube Shorts, এবং Instagram Reels-এ হাতের ইশারা দিয়ে ভিডিও চেঞ্জ করার জন্য। মিউজিক ভিজ্যুয়ালাইজারের জন্য অডিও পারমিশন প্রয়োজন।",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))

        Button(
            onClick = {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                context.startActivity(intent)
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (hasOverlay) MaterialTheme.colorScheme.surfaceVariant else PrimaryNeon,
                contentColor = if (hasOverlay) Color.White else Color.Black
            )
        ) {
            Text(if (hasOverlay) "✔ ডিসপ্লে ওভারলে দেওয়া হয়েছে" else "১. ডিসপ্লে ওভারলে দিন")
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                context.startActivity(intent)
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (hasAccessibility) MaterialTheme.colorScheme.surfaceVariant else SecondaryNeon,
                contentColor = Color.White
            )
        ) {
            Text(if (hasAccessibility) "✔ অ্যাক্সেসিবিলিটি চালু হয়েছে" else "২. সার্ভসটি চালু করুন (WaveScroll)")
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (hasAudioPermission) MaterialTheme.colorScheme.surfaceVariant else TertiaryNeon,
                contentColor = if (hasAudioPermission) Color.White else Color.Black
            )
        ) {
            Text(if (hasAudioPermission) "✔ অডিও পারমিশন দেওয়া হয়েছে" else "৩. অডিও পারমিশন দিন (Visualizer)")
        }
    }
}

fun isAccessibilityEnabled(context: Context): Boolean {
    var accessibilityEnabled = 0
    try {
        accessibilityEnabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED
        )
    } catch (e: Settings.SettingNotFoundException) {
        e.printStackTrace()
    }
    if (accessibilityEnabled == 1) {
        val settingValue = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        if (settingValue != null) {
            return settingValue.contains(context.packageName)
        }
    }
    return false
}
