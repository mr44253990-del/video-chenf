package com.example

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var hasOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasAccessibility by remember { mutableStateOf(isAccessibilityEnabled(context)) }
    
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlay = Settings.canDrawOverlays(context)
                hasAccessibility = isAccessibilityEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (hasOverlay && hasAccessibility) {
        SettingsScreen(modifier = modifier)
    } else {
        SetupScreen(
            modifier = modifier,
            hasOverlay = hasOverlay,
            hasAccessibility = hasAccessibility,
            context = context
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = context.getSharedPreferences("WaveScrollPrefs", Context.MODE_PRIVATE)
    
    var doubleWaveTime by remember { mutableFloatStateOf(prefs.getLong("doubleWaveTime", 600L).toFloat()) }
    var longPressTime by remember { mutableFloatStateOf(prefs.getLong("longPressTime", 1000L).toFloat()) }
    var scrollSpeed by remember { mutableFloatStateOf(prefs.getLong("scrollSpeed", 300L).toFloat()) }

    var btnColor by remember { mutableStateOf(prefs.getString("btnColor", "Green/Red") ?: "Green/Red") }
    var btnSize by remember { mutableStateOf(prefs.getString("btnSize", "Medium") ?: "Medium") }
    var btnPosition by remember { mutableStateOf(prefs.getString("btnPosition", "Top-End") ?: "Top-End") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(
            text = "WaveScroll Settings",
            style = MaterialTheme.typography.headlineMedium
        )

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Text(
                text = "💡 টিপস:\n- একবার হাত নাড়ালে ভিডিও চেঞ্জ হবে।\n- দ্রুত দুইবার হাত নাড়ালে ভিডিও লাইক হবে।\n- কিছুক্ষণ হাত ধরে রাখলে ভিডিও Pause/Play হবে।\n- স্ক্রিনে 'Active' লেখায় বারবার ২ বার টাচ করলে অপশনটি অফ/অন হবে।",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        HorizontalDivider()

        Text("Gesture Timings", style = MaterialTheme.typography.titleMedium)
        
        Column {
            Text("Double Wave Delay: ${doubleWaveTime.toInt()} ms")
            Slider(
                value = doubleWaveTime,
                onValueChange = { 
                    doubleWaveTime = it 
                    prefs.edit().putLong("doubleWaveTime", it.toLong()).apply()
                },
                valueRange = 300f..1500f
            )
            Text("কত সময়ের মধ্যে দুইবার হাত নাড়ালে 'লাইক' হবে", style = MaterialTheme.typography.bodySmall)
        }

        Column {
            Text("Hold Time (Video Play/Pause): ${longPressTime.toInt()} ms")
            Slider(
                value = longPressTime,
                onValueChange = { 
                    longPressTime = it 
                    prefs.edit().putLong("longPressTime", it.toLong()).apply()
                },
                valueRange = 500f..2500f
            )
        }

        Column {
            Text("Scroll Swipe Speed: ${scrollSpeed.toInt()} ms")
            Slider(
                value = scrollSpeed,
                onValueChange = { 
                    scrollSpeed = it 
                    prefs.edit().putLong("scrollSpeed", it.toLong()).apply()
                },
                valueRange = 100f..1000f
            )
        }

        HorizontalDivider()

        Text("Active Button UI", style = MaterialTheme.typography.titleMedium)
        
        SettingDropdown("Color Scheme", btnColor, listOf("Green/Red", "Blue/Yellow")) { 
            btnColor = it; prefs.edit().putString("btnColor", it).apply() 
        }

        SettingDropdown("Button Size", btnSize, listOf("Small", "Medium", "Large")) { 
            btnSize = it; prefs.edit().putString("btnSize", it).apply() 
        }

        SettingDropdown("Button Position", btnPosition, listOf("Top-Start", "Top-End", "Bottom-Start", "Bottom-End")) { 
            btnPosition = it; prefs.edit().putString("btnPosition", it).apply() 
        }
        
        Spacer(modifier = Modifier.height(16.dp))
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
            label = { Text(label) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
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
    context: Context
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "WaveScroll Setup",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        Text(
            text = "এই অ্যাপটি টিকটকে হাতের ইশারা দিয়ে ভিডিও চেঞ্জ করার জন্য তৈরি। এটি ব্যাকগ্রাউন্ডে চলবে। দয়া করে নিচের পারমিশনগুলো দিন:",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        Button(
            onClick = {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (hasOverlay) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(if (hasOverlay) "১. ডিসপ্লে ওভারলে দেওয়া হয়েছে" else "১. ডিসপ্লে ওভারলে পারমিশন দিন")
        }

        Button(
            onClick = {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (hasAccessibility) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(if (hasAccessibility) "২. অ্যাক্সেসিবিলিটি সার্ভিস চালু করা হয়েছে" else "২. অ্যাক্সেসিবিলিটি সার্ভিস চালু করুন (WaveScroll)")
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
