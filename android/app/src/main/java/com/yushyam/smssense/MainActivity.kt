package com.yushyam.smssense

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.yushyam.smssense.data.model.ForwardEvent
import com.yushyam.smssense.data.model.ForwardLogItem
import com.yushyam.smssense.data.model.InstalledAppInfo
import com.yushyam.smssense.data.model.SmsSenseConfig
import com.yushyam.smssense.data.model.SourceChannel
import com.yushyam.smssense.data.model.TargetDestination
import com.yushyam.smssense.data.model.ThemeMode
import com.yushyam.smssense.data.repo.SmsSensePrefs
import com.yushyam.smssense.engine.ForwardRuleEngine
import com.yushyam.smssense.engine.SimSlotResolver
import com.yushyam.smssense.receiver.BatteryAlertReceiver
import com.yushyam.smssense.service.ForwardDispatcher
import com.yushyam.smssense.service.NotificationSenseService
import com.yushyam.smssense.service.PromptAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Shadcn/UI Zinc 動態深淺色票定義 (Dynamic Light & Dark Zinc Palette)
private object SmsPalette {
    var isDark by mutableStateOf(false)

    val Bg: Color get() = if (isDark) Color(0xFF09090B) else Color(0xFFFAFAFA)
    val Surface: Color get() = if (isDark) Color(0xFF18181B) else Color(0xFFFFFFFF)
    val Subtle: Color get() = if (isDark) Color(0xFF27272A) else Color(0xFFF4F4F5)
    val Border: Color get() = if (isDark) Color(0xFF27272A) else Color(0xFFE4E4E7)
    val Ink: Color get() = if (isDark) Color(0xFFFAFAFA) else Color(0xFF09090B)
    val InkPrimary: Color get() = if (isDark) Color(0xFFFAFAFA) else Color(0xFF18181B)
    val OnPrimary: Color get() = if (isDark) Color(0xFF18181B) else Color(0xFFFFFFFF)
    val InkMuted: Color get() = if (isDark) Color(0xFFA1A1AA) else Color(0xFF71717A)
    val InkFaint: Color get() = if (isDark) Color(0xFF71717A) else Color(0xFFA1A1AA)

    val GreenBg: Color get() = if (isDark) Color(0xFF052E16) else Color(0xFFECFDF5)
    val GreenBorder: Color get() = if (isDark) Color(0xFF14532D) else Color(0xFFA7F3D0)
    val GreenFg: Color get() = if (isDark) Color(0xFF34D399) else Color(0xFF047857)

    val AmberBg: Color get() = if (isDark) Color(0xFF271705) else Color(0xFFFFFBEB)
    val AmberBorder: Color get() = if (isDark) Color(0xFF78350F) else Color(0xFFFDE68A)
    val AmberFg: Color get() = if (isDark) Color(0xFFFBBF24) else Color(0xFFB45309)
    val AmberSubText: Color get() = if (isDark) Color(0xFFFDE68A) else Color(0xFF92400E)

    val RedBg: Color get() = if (isDark) Color(0xFF2A0A12) else Color(0xFFFEF2F2)
    val RedFg: Color get() = if (isDark) Color(0xFFFB7185) else Color(0xFFBE123C)
}

private enum class SmsTab(val title: String, val icon: ImageVector) {
    OVERVIEW("總覽", Icons.Default.Dashboard),
    WHITELIST("白名單", Icons.AutoMirrored.Filled.Rule),
    SETTINGS("設定", Icons.Default.Tune),
    LOGS("紀錄", Icons.AutoMirrored.Filled.ReceiptLong)
}

private enum class WhitelistTarget { NOTIF, PROMPT }

class MainActivity : ComponentActivity() {

    private val smsPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        ForwardDispatcher.notifyUi(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ForwardDispatcher.syncBatteryMonitor(this)

        setContent {
            SmsSenseApp(
                context = this,
                onRequestSmsPermission = { requestSmsPermissions() },
                onShowToast = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        ForwardDispatcher.notifyUi(this)
    }

    private fun requestSmsPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            perms.add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        smsPermLauncher.launch(perms.toTypedArray())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsSenseApp(
    context: Context,
    onRequestSmsPermission: () -> Unit,
    onShowToast: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val prefs = remember { SmsSensePrefs(context) }
    val lifecycleOwner = LocalLifecycleOwner.current

    var selectedTab by remember { mutableStateOf(SmsTab.OVERVIEW) }
    var config by remember { mutableStateOf(prefs.loadConfig()) }
    var logs by remember { mutableStateOf(prefs.loadLogs()) }
    var pendingCount by remember { mutableStateOf(prefs.loadPendingQueue().size) }
    var isSendingTest by remember { mutableStateOf(false) }

    var hasSmsPerm by remember { mutableStateOf(checkSmsPermission(context)) }
    var hasCallPerm by remember { mutableStateOf(checkCallPermission(context)) }
    var hasNotifPerm by remember { mutableStateOf(checkNotifListenerEnabled(context)) }
    var hasPromptPerm by remember { mutableStateOf(checkAccessibilityEnabled(context)) }
    var batteryPercent by remember { mutableStateOf(BatteryAlertReceiver.readBatteryStatus(context).first) }

    var installedApps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
    var pickerTarget by remember { mutableStateOf<WhitelistTarget?>(null) }

    val systemDark = isSystemInDarkTheme()
    val currentThemeMode = ThemeMode.fromSetting(config.themeMode)
    val useDark = when (currentThemeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    SmsPalette.isDark = useDark

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (context as? Activity)?.window ?: return@SideEffect
            @Suppress("DEPRECATION")
            window.statusBarColor = SmsPalette.Surface.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = SmsPalette.Surface.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !useDark
            controller.isAppearanceLightNavigationBars = !useDark
        }
    }

    val colorScheme = if (useDark) {
        darkColorScheme(
            primary = SmsPalette.InkPrimary,
            onPrimary = SmsPalette.OnPrimary,
            secondary = Color(0xFFD4D4D8),
            background = SmsPalette.Bg,
            surface = SmsPalette.Surface,
            onSurface = SmsPalette.Ink,
            surfaceVariant = SmsPalette.Subtle,
            onSurfaceVariant = SmsPalette.InkMuted,
            outline = SmsPalette.Border
        )
    } else {
        lightColorScheme(
            primary = SmsPalette.InkPrimary,
            onPrimary = SmsPalette.OnPrimary,
            secondary = Color(0xFF3F3F46),
            background = SmsPalette.Bg,
            surface = SmsPalette.Surface,
            onSurface = SmsPalette.Ink,
            surfaceVariant = SmsPalette.Subtle,
            onSurfaceVariant = SmsPalette.InkMuted,
            outline = SmsPalette.Border
        )
    }

    fun refreshState() {
        config = prefs.loadConfig()
        logs = prefs.loadLogs()
        pendingCount = prefs.loadPendingQueue().size
        hasSmsPerm = checkSmsPermission(context)
        hasCallPerm = checkCallPermission(context)
        hasNotifPerm = checkNotifListenerEnabled(context)
        hasPromptPerm = checkAccessibilityEnabled(context)
        batteryPercent = BatteryAlertReceiver.readBatteryStatus(context).first
    }

    fun setThemeMode(mode: ThemeMode) {
        val next = config.copy(themeMode = mode.settingValue)
        prefs.saveConfig(next)
        config = next
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val loaded = loadInstalledApps(context)
            withContext(Dispatchers.Main) { installedApps = loaded }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                refreshState()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ForwardDispatcher.ACTION_LOGS_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refreshState()
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    val appLabelMap = remember(installedApps) {
        installedApps.associate { it.packageName to it.label }
    }

    MaterialTheme(colorScheme = colorScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = SmsPalette.Bg
        ) {
            Scaffold(
                containerColor = SmsPalette.Bg,
                topBar = {
                    Column {
                        TopAppBar(
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = SmsPalette.Surface),
                            title = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text("SmsSense", fontWeight = FontWeight.Bold, fontSize = 19.sp, color = SmsPalette.Ink)
                                    val ready = config.enabled && config.isAnyDestinationConfigured
                                    StatusPill(
                                        label = if (ready) "轉發中" else "待設定",
                                        bg = if (ready) SmsPalette.GreenBg else SmsPalette.AmberBg,
                                        border = if (ready) SmsPalette.GreenBorder else SmsPalette.AmberBorder,
                                        fg = if (ready) SmsPalette.GreenFg else SmsPalette.AmberFg
                                    )
                                }
                            },
                            actions = {
                                IconButton(onClick = {
                                    val nextMode = currentThemeMode.next()
                                    setThemeMode(nextMode)
                                    onShowToast("外觀：${nextMode.labelZh}")
                                }) {
                                    Icon(
                                        when (currentThemeMode) {
                                            ThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
                                            ThemeMode.LIGHT -> Icons.Default.LightMode
                                            ThemeMode.DARK -> Icons.Default.DarkMode
                                        },
                                        contentDescription = "切換深淺色",
                                        tint = SmsPalette.InkPrimary
                                    )
                                }
                                IconButton(onClick = {
                                    refreshState()
                                    onShowToast("狀態已更新")
                                }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "重新整理", tint = SmsPalette.InkPrimary)
                                }
                            }
                        )
                        HorizontalDivider(color = SmsPalette.Border, thickness = 1.dp)
                    }
                },
                bottomBar = {
                    Column {
                        HorizontalDivider(color = SmsPalette.Border, thickness = 1.dp)
                        NavigationBar(containerColor = SmsPalette.Surface, tonalElevation = 0.dp) {
                            SmsTab.entries.forEach { tab ->
                                val selected = selectedTab == tab
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        selectedTab = tab
                                        refreshState()
                                    },
                                    icon = { Icon(tab.icon, contentDescription = tab.title, modifier = Modifier.size(22.dp)) },
                                    label = {
                                        Text(
                                            tab.title,
                                            fontSize = 12.sp,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = SmsPalette.Ink,
                                        selectedTextColor = SmsPalette.Ink,
                                        unselectedIconColor = SmsPalette.InkMuted,
                                        unselectedTextColor = SmsPalette.InkMuted,
                                        indicatorColor = SmsPalette.Subtle
                                    )
                                )
                            }
                        }
                    }
                }
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    when (selectedTab) {
                        SmsTab.OVERVIEW -> OverviewTab(
                            config = config,
                            hasSmsPerm = hasSmsPerm,
                            hasCallPerm = hasCallPerm,
                            hasNotifPerm = hasNotifPerm,
                            hasPromptPerm = hasPromptPerm,
                            batteryPercent = batteryPercent,
                            pendingCount = pendingCount,
                            isSendingTest = isSendingTest,
                            onToggleEnabled = { checked ->
                                val next = config.copy(enabled = checked)
                                prefs.saveConfig(next)
                                config = next
                                ForwardDispatcher.syncBatteryMonitor(context)
                            },
                            onSendTest = {
                                if (!config.isAnyDestinationConfigured) {
                                    onShowToast("請先到「設定」填寫推播通道資訊")
                                    selectedTab = SmsTab.SETTINGS
                                    return@OverviewTab
                                }
                                isSendingTest = true
                                scope.launch(Dispatchers.IO) {
                                    val detectedSim = SimSlotResolver.resolveSimDescription(context, Intent())
                                    val testEvent = ForwardEvent(
                                        channel = SourceChannel.SMS,
                                        packageName = "sms",
                                        sourceTitle = "系統測試",
                                        receiverSimInfo = detectedSim,
                                        content = "【SmsSense】您的驗證碼是 829401，請於 5 分鐘內完成驗證。轉發通道連線正常。"
                                    )
                                    val model = Build.MODEL.orEmpty()
                                    val html = ForwardRuleEngine.formatTelegramHtml(
                                        config = config,
                                        event = testEvent,
                                        deviceModel = model
                                    )
                                    val plain = ForwardRuleEngine.formatPlainText(
                                        config = config,
                                        event = testEvent,
                                        deviceModel = model
                                    )
                                    val activeTargets = mutableListOf<TargetDestination>()
                                    if (config.isTelegramConfigured) activeTargets.add(TargetDestination.TELEGRAM)
                                    if (config.isLineConfigured) activeTargets.add(TargetDestination.LINE)
                                    if (config.isWebhookConfigured) activeTargets.add(TargetDestination.WEBHOOK)

                                    val results = ForwardDispatcher.sendToTargets(config, activeTargets, html, plain)
                                    val allOk = results.isNotEmpty() && results.all { it.second }
                                    val detail = results.joinToString(" | ") { "${it.first.label}: ${it.third}" }

                                    prefs.appendLog(
                                        ForwardLogItem(
                                            timestampMs = System.currentTimeMillis(),
                                            channelLabel = "測試訊息",
                                            sourceTitle = activeTargets.joinToString("+") { it.label },
                                            preview = "測試連線推播",
                                            delivered = allOk,
                                            statusDetail = detail
                                        )
                                    )
                                    withContext(Dispatchers.Main) {
                                        isSendingTest = false
                                        refreshState()
                                        onShowToast(if (allOk) "測試訊息已送達（${activeTargets.joinToString { it.label }}）！" else detail)
                                    }
                                }
                            },
                            onFlushPending = {
                                scope.launch(Dispatchers.IO) {
                                    val (sent, remain) = ForwardDispatcher.flushPendingQueueBlocking(context)
                                    withContext(Dispatchers.Main) {
                                        refreshState()
                                        onShowToast("已補發 $sent 筆，剩餘 $remain 筆待送")
                                    }
                                }
                            },
                            onGoSettings = { selectedTab = SmsTab.SETTINGS },
                            onGoWhitelist = { selectedTab = SmsTab.WHITELIST }
                        )

                        SmsTab.WHITELIST -> WhitelistTab(
                            config = config,
                            appLabelMap = appLabelMap,
                            onToggleSms = { checked ->
                                val next = config.copy(smsForwardEnabled = checked)
                                prefs.saveConfig(next)
                                config = next
                            },
                            onToggleOtpHint = { checked ->
                                val next = config.copy(appendOtpHint = checked)
                                prefs.saveConfig(next)
                                config = next
                            },
                            onToggleNotif = { checked ->
                                val next = config.copy(notificationForwardEnabled = checked)
                                prefs.saveConfig(next)
                                config = next
                            },
                            onTogglePrompt = { checked ->
                                val next = config.copy(promptForwardEnabled = checked)
                                prefs.saveConfig(next)
                                config = next
                            },
                            onToggleCall = { checked ->
                                val next = config.copy(callForwardEnabled = checked)
                                prefs.saveConfig(next)
                                config = next
                            },
                            onToggleLowBattery = { checked ->
                                val next = config.copy(lowBatteryAlertEnabled = checked)
                                prefs.saveConfig(next)
                                config = next
                                ForwardDispatcher.syncBatteryMonitor(context)
                            },
                            onToggleBatteryThreshold = { pct ->
                                val current = config.lowBatteryThresholds.toMutableSet()
                                if (pct in current) {
                                    if (current.size > 1) {
                                        current.remove(pct)
                                    } else {
                                        onShowToast("請至少保留一項電量通知門檻")
                                        return@WhitelistTab
                                    }
                                } else {
                                    current.add(pct)
                                }
                                val next = config.copy(lowBatteryThresholds = current.sorted())
                                prefs.saveConfig(next)
                                config = prefs.loadConfig()
                                prefs.saveSentBatteryThresholds(emptySet())
                                ForwardDispatcher.syncBatteryMonitor(context)
                            },
                            onAddNotifApp = { pickerTarget = WhitelistTarget.NOTIF },
                            onRemoveNotifApp = { pkg ->
                                val next = config.copy(whitelistedNotifPackages = config.whitelistedNotifPackages.filterNot { it == pkg })
                                prefs.saveConfig(next)
                                config = next
                            },
                            onAddPromptApp = { pickerTarget = WhitelistTarget.PROMPT },
                            onRemovePromptApp = { pkg ->
                                val next = config.copy(whitelistedPromptPackages = config.whitelistedPromptPackages.filterNot { it == pkg })
                                prefs.saveConfig(next)
                                config = next
                            }
                        )

                        SmsTab.SETTINGS -> SettingsTab(
                            config = config,
                            currentThemeMode = currentThemeMode,
                            hasSmsPerm = hasSmsPerm,
                            hasCallPerm = hasCallPerm,
                            hasNotifPerm = hasNotifPerm,
                            hasPromptPerm = hasPromptPerm,
                            pendingCount = pendingCount,
                            onSelectThemeMode = { mode -> setThemeMode(mode) },
                            onSaveConfig = { updated ->
                                prefs.saveConfig(updated)
                                config = prefs.loadConfig()
                                onShowToast("設定已儲存")
                            },
                            onFlushPending = {
                                scope.launch(Dispatchers.IO) {
                                    val (sent, remain) = ForwardDispatcher.flushPendingQueueBlocking(context)
                                    withContext(Dispatchers.Main) {
                                        refreshState()
                                        onShowToast("已補發 $sent 筆，剩餘 $remain 筆待送")
                                    }
                                }
                            },
                            onClearPending = {
                                prefs.clearPendingQueue()
                                refreshState()
                                onShowToast("已清空待補發佇列")
                            },
                            onRequestSmsPerm = onRequestSmsPermission,
                            onOpenAppDetails = {
                                val intent = Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null)
                                )
                                context.startActivity(intent)
                            },
                            onOpenNotifSettings = {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            },
                            onOpenAccessibilitySettings = {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        )

                        SmsTab.LOGS -> LogsTab(
                            logs = logs,
                            pendingCount = pendingCount,
                            onFlushPending = {
                                scope.launch(Dispatchers.IO) {
                                    val (sent, remain) = ForwardDispatcher.flushPendingQueueBlocking(context)
                                    withContext(Dispatchers.Main) {
                                        refreshState()
                                        onShowToast("已補發 $sent 筆，剩餘 $remain 筆待送")
                                    }
                                }
                            },
                            onClearLogs = {
                                prefs.clearLogs()
                                refreshState()
                                onShowToast("已清除紀錄")
                            }
                        )
                    }
                }
            }

            pickerTarget?.let { target ->
                val existing = if (target == WhitelistTarget.NOTIF) config.whitelistedNotifPackages
                else config.whitelistedPromptPackages

                AppPickerDialog(
                    title = if (target == WhitelistTarget.NOTIF) "選擇應用通知白名單" else "選擇登入彈窗白名單",
                    installedApps = installedApps,
                    selectedPackages = existing.toSet(),
                    onDismiss = { pickerTarget = null },
                    onTogglePackage = { pkg ->
                        val updated = if (pkg in existing) existing.filterNot { it == pkg } else existing + pkg
                        val next = if (target == WhitelistTarget.NOTIF) {
                            config.copy(whitelistedNotifPackages = updated)
                        } else {
                            config.copy(whitelistedPromptPackages = updated)
                        }
                        prefs.saveConfig(next)
                        config = next
                    }
                )
            }
        }
    }
}

// =====================================================================
// Tab 1: 總覽
// =====================================================================
@Composable
private fun OverviewTab(
    config: SmsSenseConfig,
    hasSmsPerm: Boolean,
    hasCallPerm: Boolean,
    hasNotifPerm: Boolean,
    hasPromptPerm: Boolean,
    batteryPercent: Int,
    pendingCount: Int,
    isSendingTest: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onSendTest: () -> Unit,
    onFlushPending: () -> Unit,
    onGoSettings: () -> Unit,
    onGoWhitelist: () -> Unit
) {
    val activeLabels = config.activeDestinationLabels()
    val thresholdText = config.lowBatteryThresholds.sorted().joinToString("/") { "$it%" }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 14.dp)
    ) {
        item {
            CraftCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (activeLabels.isEmpty()) "推播通道未設定"
                            else "通道：${activeLabels.joinToString(" · ")}",
                            fontSize = 12.sp,
                            color = SmsPalette.InkMuted
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (config.enabled) "轉發運作中" else "已暫停轉發",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = SmsPalette.Ink
                        )
                        Text(
                            when {
                                !config.isAnyDestinationConfigured -> "請前往「設定」配置推播通道"
                                config.deviceAlias.isNotBlank() -> "${config.deviceAlias} (${Build.MODEL})"
                                else -> Build.MODEL
                            },
                            fontSize = 13.sp,
                            color = SmsPalette.InkMuted
                        )
                    }
                    Switch(checked = config.enabled, onCheckedChange = onToggleEnabled)
                }
            }
        }

        item {
            CraftCard {
                Text("監控狀態", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = SmsPalette.Ink)
                Spacer(Modifier.height(10.dp))

                ChannelStatusRow(
                    title = "簡訊轉發",
                    active = config.smsForwardEnabled && hasSmsPerm,
                    badgeText = when {
                        !config.smsForwardEnabled -> "已停用"
                        hasSmsPerm -> "已就緒"
                        else -> "未授權"
                    }
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = SmsPalette.Border)

                ChannelStatusRow(
                    title = "來電通知",
                    active = config.callForwardEnabled && hasCallPerm,
                    badgeText = when {
                        !config.callForwardEnabled -> "已停用"
                        hasCallPerm -> "已就緒"
                        else -> "未授權"
                    }
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = SmsPalette.Border)

                ChannelStatusRow(
                    title = "應用通知",
                    active = config.notificationForwardEnabled && hasNotifPerm,
                    badgeText = when {
                        !config.notificationForwardEnabled -> "已停用"
                        hasNotifPerm -> "${config.whitelistedNotifPackages.size} 款應用"
                        else -> "未授權"
                    }
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = SmsPalette.Border)

                ChannelStatusRow(
                    title = "登入彈窗",
                    active = config.promptForwardEnabled && hasPromptPerm,
                    badgeText = when {
                        !config.promptForwardEnabled -> "已停用"
                        hasPromptPerm -> "${config.whitelistedPromptPackages.size} 款應用"
                        else -> "未開啟"
                    }
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = SmsPalette.Border)

                ChannelStatusRow(
                    title = "電量通知",
                    active = config.lowBatteryAlertEnabled,
                    badgeText = when {
                        !config.lowBatteryAlertEnabled -> "已停用"
                        batteryPercent >= 0 -> "$thresholdText · 目前 $batteryPercent%"
                        else -> thresholdText
                    }
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = SmsPalette.Border)

                ChannelStatusRow(
                    title = "斷網補發",
                    active = config.offlineQueueEnabled,
                    badgeText = when {
                        !config.offlineQueueEnabled -> "已停用"
                        pendingCount > 0 -> "待送 $pendingCount 筆"
                        else -> "已就緒"
                    }
                )
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onSendTest,
                    enabled = !isSendingTest,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SmsPalette.InkPrimary,
                        contentColor = SmsPalette.OnPrimary
                    )
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isSendingTest) "發送中..." else "發送測試")
                }
                OutlinedButton(
                    onClick = onGoWhitelist,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, SmsPalette.Border),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = SmsPalette.Surface,
                        contentColor = SmsPalette.InkPrimary
                    )
                ) {
                    Text("管理白名單", color = SmsPalette.InkPrimary, fontWeight = FontWeight.Medium)
                }
            }
        }

        if (pendingCount > 0) {
            item {
                Surface(
                    color = SmsPalette.AmberBg,
                    border = BorderStroke(1.dp, SmsPalette.AmberBorder),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "尚有 $pendingCount 筆離線訊息待補發，連網後將自動送出。",
                            fontSize = 13.sp,
                            color = SmsPalette.AmberFg,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onFlushPending) {
                            Text("立即補發", fontWeight = FontWeight.Bold, color = SmsPalette.AmberFg)
                        }
                    }
                }
            }
        }

        if (!config.isAnyDestinationConfigured || !hasSmsPerm || !hasNotifPerm) {
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onGoSettings() },
                    color = SmsPalette.AmberBg,
                    border = BorderStroke(1.dp, SmsPalette.AmberBorder),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("尚有設定或權限未完成", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = SmsPalette.AmberFg)
                            Text("點擊前往「設定」配置通道或開啟權限。", fontSize = 12.sp, color = SmsPalette.AmberSubText)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = SmsPalette.AmberFg)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelStatusRow(title: String, active: Boolean, badgeText: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = SmsPalette.Ink, modifier = Modifier.weight(1f))
        StatusPill(
            label = badgeText,
            bg = if (active) SmsPalette.GreenBg else SmsPalette.Subtle,
            border = if (active) SmsPalette.GreenBorder else SmsPalette.Border,
            fg = if (active) SmsPalette.GreenFg else SmsPalette.InkMuted
        )
    }
}

// =====================================================================
// Tab 2: 白名單
// =====================================================================
@Composable
private fun WhitelistTab(
    config: SmsSenseConfig,
    appLabelMap: Map<String, String>,
    onToggleSms: (Boolean) -> Unit,
    onToggleOtpHint: (Boolean) -> Unit,
    onToggleNotif: (Boolean) -> Unit,
    onTogglePrompt: (Boolean) -> Unit,
    onToggleCall: (Boolean) -> Unit,
    onToggleLowBattery: (Boolean) -> Unit,
    onToggleBatteryThreshold: (Int) -> Unit,
    onAddNotifApp: () -> Unit,
    onRemoveNotifApp: (String) -> Unit,
    onAddPromptApp: () -> Unit,
    onRemovePromptApp: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 14.dp)
    ) {
        item {
            CraftCard {
                Text("簡訊與來電", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = SmsPalette.Ink)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("簡訊轉發", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = SmsPalette.Ink)
                        Text("保留完整原文，並標註系統 SIM 卡槽與發生時間", fontSize = 12.sp, color = SmsPalette.InkMuted)
                    }
                    Switch(checked = config.smsForwardEnabled, onCheckedChange = onToggleSms)
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = SmsPalette.Border)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("置頂顯示驗證碼", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = SmsPalette.Ink)
                        Text("偵測到驗證碼時於頂部附一行單擊複製", fontSize = 12.sp, color = SmsPalette.InkMuted)
                    }
                    Switch(checked = config.appendOtpHint, onCheckedChange = onToggleOtpHint)
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = SmsPalette.Border)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("來電通知", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = SmsPalette.Ink)
                        Text("響鈴時推播來電號碼與收受 SIM 卡槽", fontSize = 12.sp, color = SmsPalette.InkMuted)
                    }
                    Switch(checked = config.callForwardEnabled, onCheckedChange = onToggleCall)
                }
            }
        }

        item {
            CraftCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("電量通知", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = SmsPalette.Ink)
                        Text("可複選多個通知門檻，電量依序跌破時各提醒一次", fontSize = 12.sp, color = SmsPalette.InkMuted)
                    }
                    Switch(checked = config.lowBatteryAlertEnabled, onCheckedChange = onToggleLowBattery)
                }
                if (config.lowBatteryAlertEnabled) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmsSenseConfig.AVAILABLE_BATTERY_THRESHOLDS.forEach { pct ->
                            val selected = pct in config.lowBatteryThresholds
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onToggleBatteryThreshold(pct) },
                                color = if (selected) SmsPalette.InkPrimary else SmsPalette.Subtle,
                                border = BorderStroke(1.dp, if (selected) SmsPalette.InkPrimary else SmsPalette.Border),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "$pct%",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (selected) SmsPalette.OnPrimary else SmsPalette.Ink
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            CraftCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("應用通知白名單", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = SmsPalette.Ink)
                        Text("僅轉發下方勾選的 App 通知", fontSize = 12.sp, color = SmsPalette.InkMuted)
                    }
                    Switch(checked = config.notificationForwardEnabled, onCheckedChange = onToggleNotif)
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onAddNotifApp,
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SmsPalette.InkPrimary,
                        contentColor = SmsPalette.OnPrimary
                    )
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("勾選通知白名單 App")
                }
                Spacer(Modifier.height(10.dp))
                if (config.whitelistedNotifPackages.isEmpty()) {
                    Text("尚未勾選任何 App", fontSize = 12.sp, color = SmsPalette.InkMuted)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        config.whitelistedNotifPackages.forEach { pkg ->
                            PackageRow(
                                label = appLabelMap[pkg] ?: pkg,
                                packageName = pkg,
                                onRemove = { onRemoveNotifApp(pkg) }
                            )
                        }
                    }
                }
            }
        }

        item {
            CraftCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("登入彈窗白名單", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = SmsPalette.Ink)
                        Text("偵測 Google 提示／Authenticator 登入確認視窗", fontSize = 12.sp, color = SmsPalette.InkMuted)
                    }
                    Switch(checked = config.promptForwardEnabled, onCheckedChange = onTogglePrompt)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onAddPromptApp,
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, SmsPalette.Border)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp), tint = SmsPalette.InkPrimary)
                    Spacer(Modifier.width(6.dp))
                    Text("勾選彈窗監聽 App", color = SmsPalette.InkPrimary)
                }
                Spacer(Modifier.height(10.dp))
                if (config.whitelistedPromptPackages.isEmpty()) {
                    Text("尚未加入任何 App", fontSize = 12.sp, color = SmsPalette.InkMuted)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        config.whitelistedPromptPackages.forEach { pkg ->
                            PackageRow(
                                label = appLabelMap[pkg] ?: pkg,
                                packageName = pkg,
                                onRemove = { onRemovePromptApp(pkg) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PackageRow(label: String, packageName: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SmsPalette.Subtle, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = SmsPalette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(packageName, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = SmsPalette.InkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.DeleteOutline, contentDescription = "移除", tint = SmsPalette.RedFg)
        }
    }
}

// =====================================================================
// Tab 3: 設定
// =====================================================================
@Composable
private fun SettingsTab(
    config: SmsSenseConfig,
    currentThemeMode: ThemeMode,
    hasSmsPerm: Boolean,
    hasCallPerm: Boolean,
    hasNotifPerm: Boolean,
    hasPromptPerm: Boolean,
    pendingCount: Int,
    onSelectThemeMode: (ThemeMode) -> Unit,
    onSaveConfig: (SmsSenseConfig) -> Unit,
    onFlushPending: () -> Unit,
    onClearPending: () -> Unit,
    onRequestSmsPerm: () -> Unit,
    onOpenAppDetails: () -> Unit,
    onOpenNotifSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    var alias by remember(config.deviceAlias) { mutableStateOf(config.deviceAlias) }
    var offlineQueueEnabled by remember(config.offlineQueueEnabled) { mutableStateOf(config.offlineQueueEnabled) }

    var selectedChannelTab by remember { mutableStateOf(TargetDestination.TELEGRAM) }

    var tgEnabled by remember(config.telegramEnabled) { mutableStateOf(config.telegramEnabled) }
    var tgToken by remember(config.telegramBotToken) { mutableStateOf(config.telegramBotToken) }
    var tgChatId by remember(config.telegramChatId) { mutableStateOf(config.telegramChatId) }
    var showTgToken by remember { mutableStateOf(false) }

    var lineEnabled by remember(config.lineEnabled) { mutableStateOf(config.lineEnabled) }
    var lineToken by remember(config.lineChannelAccessToken) { mutableStateOf(config.lineChannelAccessToken) }
    var lineTarget by remember(config.lineTargetId) { mutableStateOf(config.lineTargetId) }
    var showLineToken by remember { mutableStateOf(false) }

    var webhookEnabled by remember(config.webhookEnabled) { mutableStateOf(config.webhookEnabled) }
    var webhookUrl by remember(config.webhookUrl) { mutableStateOf(config.webhookUrl) }
    var webhookSecret by remember(config.webhookSecretHeader) { mutableStateOf(config.webhookSecretHeader) }
    var showWebhookSecret by remember { mutableStateOf(false) }

    fun buildDraftConfig(): SmsSenseConfig {
        return config.copy(
            deviceAlias = alias,
            offlineQueueEnabled = offlineQueueEnabled,
            telegramEnabled = tgEnabled,
            telegramBotToken = tgToken,
            telegramChatId = tgChatId,
            lineEnabled = lineEnabled,
            lineChannelAccessToken = lineToken,
            lineTargetId = lineTarget,
            webhookEnabled = webhookEnabled,
            webhookUrl = webhookUrl,
            webhookSecretHeader = webhookSecret
        )
    }

    val draft = buildDraftConfig()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 14.dp)
    ) {
        // 0. 外觀主題切換卡 (跟隨系統 / 淺色 / 深色)
        item {
            CraftCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("外觀主題", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = SmsPalette.Ink)
                    Text("支援自動跟隨系統或手動切換", fontSize = 12.sp, color = SmsPalette.InkMuted)
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SmsPalette.Subtle, RoundedCornerShape(10.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ThemeMode.entries.forEach { mode ->
                        val selected = currentThemeMode == mode
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(7.dp))
                                .clickable { onSelectThemeMode(mode) },
                            color = if (selected) SmsPalette.Surface else Color.Transparent,
                            border = if (selected) BorderStroke(1.dp, SmsPalette.Border) else null,
                            shape = RoundedCornerShape(7.dp)
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    mode.labelZh,
                                    fontSize = 13.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (selected) SmsPalette.Ink else SmsPalette.InkMuted
                                )
                            }
                        }
                    }
                }
            }
        }

        // 1. 推播通道（Shadcn Segmented Control 膠囊切換器）
        item {
            CraftCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("推播通道", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = SmsPalette.Ink)
                    Text(
                        "可同時啟用多軌並行",
                        fontSize = 12.sp,
                        color = SmsPalette.InkMuted
                    )
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SmsPalette.Subtle, RoundedCornerShape(10.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TargetDestination.entries.forEach { dest ->
                        val isSelected = selectedChannelTab == dest
                        val isConfigured = when (dest) {
                            TargetDestination.TELEGRAM -> draft.isTelegramConfigured
                            TargetDestination.LINE -> draft.isLineConfigured
                            TargetDestination.WEBHOOK -> draft.isWebhookConfigured
                        }
                        val isSwitchOn = when (dest) {
                            TargetDestination.TELEGRAM -> tgEnabled
                            TargetDestination.LINE -> lineEnabled
                            TargetDestination.WEBHOOK -> webhookEnabled
                        }
                        val dotColor = when {
                            isConfigured -> SmsPalette.GreenFg
                            isSwitchOn -> SmsPalette.AmberFg
                            else -> SmsPalette.InkFaint
                        }

                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(7.dp))
                                .clickable { selectedChannelTab = dest },
                            color = if (isSelected) SmsPalette.Surface else Color.Transparent,
                            border = if (isSelected) BorderStroke(1.dp, SmsPalette.Border) else null,
                            shape = RoundedCornerShape(7.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(dotColor, CircleShape)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = dest.label,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (isSelected) SmsPalette.Ink else SmsPalette.InkMuted
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                when (selectedChannelTab) {
                    TargetDestination.TELEGRAM -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("啟用 Telegram 推播", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = SmsPalette.Ink)
                                Text("透過 Telegram Bot 傳送 HTML 格式訊息", fontSize = 12.sp, color = SmsPalette.InkMuted)
                            }
                            Switch(checked = tgEnabled, onCheckedChange = { tgEnabled = it })
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = tgToken,
                            onValueChange = { tgToken = it },
                            label = { Text("Bot Token（由 @BotFather 取得）") },
                            singleLine = true,
                            visualTransformation = if (showTgToken) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showTgToken = !showTgToken }) {
                                    Icon(if (showTgToken) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "切換顯示")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = tgChatId,
                            onValueChange = { tgChatId = it },
                            label = { Text("Chat ID（接收者或群組 ID）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    TargetDestination.LINE -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("啟用 LINE 推播", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = SmsPalette.Ink)
                                Text("透過 LINE Messaging API 推播至個人或群組", fontSize = 12.sp, color = SmsPalette.InkMuted)
                            }
                            Switch(checked = lineEnabled, onCheckedChange = { lineEnabled = it })
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = lineToken,
                            onValueChange = { lineToken = it },
                            label = { Text("Channel Access Token") },
                            singleLine = true,
                            visualTransformation = if (showLineToken) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showLineToken = !showLineToken }) {
                                    Icon(if (showLineToken) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "切換顯示")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = lineTarget,
                            onValueChange = { lineTarget = it },
                            label = { Text("Target ID（User ID 或 Group ID）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    TargetDestination.WEBHOOK -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("啟用 Webhook 推播", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = SmsPalette.Ink)
                                Text("相容 Discord / Slack / n8n / 自建 HTTP POST", fontSize = 12.sp, color = SmsPalette.InkMuted)
                            }
                            Switch(checked = webhookEnabled, onCheckedChange = { webhookEnabled = it })
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = webhookUrl,
                            onValueChange = { webhookUrl = it },
                            label = { Text("Webhook URL (https://...)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = webhookSecret,
                            onValueChange = { webhookSecret = it },
                            label = { Text("Secret Header / Bearer Token（選填）") },
                            singleLine = true,
                            visualTransformation = if (showWebhookSecret) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showWebhookSecret = !showWebhookSecret }) {
                                    Icon(if (showWebhookSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "切換顯示")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = SmsPalette.Border)

                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("裝置別名（選填，留空則顯示系統型號）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = { onSaveConfig(buildDraftConfig()) },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SmsPalette.InkPrimary,
                        contentColor = SmsPalette.OnPrimary
                    )
                ) {
                    Text("儲存設定", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // 2. 斷網補發佇列設定
        item {
            CraftCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("斷網自動佇列補發", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = SmsPalette.Ink)
                        Text(
                            "無網路時自動暫存原始發生時間與收受門號，待恢復連線後由系統背景自動補送。",
                            fontSize = 12.sp,
                            color = SmsPalette.InkMuted
                        )
                    }
                    Switch(
                        checked = offlineQueueEnabled,
                        onCheckedChange = {
                            offlineQueueEnabled = it
                            onSaveConfig(buildDraftConfig().copy(offlineQueueEnabled = it))
                        }
                    )
                }
                if (pendingCount > 0) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onFlushPending,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("立即補發 ($pendingCount 筆)", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = onClearPending,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("清空佇列", fontSize = 12.sp, color = SmsPalette.RedFg)
                        }
                    }
                }
            }
        }

        // 3. 系統權限中心
        item {
            CraftCard {
                Text("系統權限中心", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = SmsPalette.Ink)
                Spacer(Modifier.height(10.dp))

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (!hasNotifPerm || !hasPromptPerm)) {
                    Surface(
                        color = SmsPalette.AmberBg,
                        border = BorderStroke(1.dp, SmsPalette.AmberBorder),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "權限選項反灰無法開啟？",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = SmsPalette.AmberFg
                            )
                            Text(
                                "Android 13+ / 小米系統需先開啟「應用程式資訊」➔ 點右上角「⋮」或滑至底部勾選「允許受限制的設定」，返回即可正常開啟。",
                                fontSize = 12.sp,
                                color = SmsPalette.AmberSubText
                            )
                            OutlinedButton(
                                onClick = onOpenAppDetails,
                                modifier = Modifier.fillMaxWidth().height(38.dp),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, SmsPalette.AmberFg)
                            ) {
                                Text("開啟應用程式資訊（解鎖受限制設定）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = SmsPalette.AmberFg)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                PermissionRow(
                    title = "1. 簡訊與電話權限",
                    subtitle = "讀取簡訊、來電號碼與系統雙卡 SIM 資訊",
                    granted = hasSmsPerm && hasCallPerm,
                    actionLabel = "開啟權限",
                    onAction = onRequestSmsPerm
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = SmsPalette.Border)
                PermissionRow(
                    title = "2. 通知存取權限",
                    subtitle = "讀取白名單 App 通知",
                    granted = hasNotifPerm,
                    actionLabel = "前往開啟",
                    onAction = onOpenNotifSettings
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = SmsPalette.Border)
                PermissionRow(
                    title = "3. 無障礙視窗偵測（選配）",
                    subtitle = "偵測 Google／銀行全畫面登入確認彈窗",
                    granted = hasPromptPerm,
                    actionLabel = "前往開啟",
                    onAction = onOpenAccessibilitySettings
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = SmsPalette.Ink)
            Text(subtitle, fontSize = 12.sp, color = SmsPalette.InkMuted)
        }
        Spacer(Modifier.width(8.dp))
        if (granted) {
            StatusPill("已開啟", SmsPalette.GreenBg, SmsPalette.GreenBorder, SmsPalette.GreenFg)
        } else {
            TextButton(onClick = onAction) {
                Text(actionLabel, fontWeight = FontWeight.SemiBold, color = SmsPalette.InkPrimary)
            }
        }
    }
}

// =====================================================================
// Tab 4: 紀錄
// =====================================================================
@Composable
private fun LogsTab(
    logs: List<ForwardLogItem>,
    pendingCount: Int,
    onFlushPending: () -> Unit,
    onClearLogs: () -> Unit
) {
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("近期轉發紀錄（${logs.size} 筆）", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = SmsPalette.Ink)
                Row {
                    if (pendingCount > 0) {
                        TextButton(onClick = onFlushPending) {
                            Text("立即補發 ($pendingCount)", fontSize = 13.sp)
                        }
                    }
                    if (logs.isNotEmpty()) {
                        TextButton(onClick = onClearLogs) {
                            Text("清除", color = SmsPalette.RedFg, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        if (logs.isEmpty()) {
            item {
                CraftCard {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.History, contentDescription = null, tint = SmsPalette.InkFaint, modifier = Modifier.size(36.dp))
                        Text("尚無轉發紀錄", fontWeight = FontWeight.SemiBold, color = SmsPalette.Ink)
                        Text("收到簡訊、來電、白名單通知或登入彈窗後，會顯示於此。", fontSize = 12.sp, color = SmsPalette.InkMuted)
                    }
                }
            }
        } else {
            items(logs) { item ->
                CraftCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            StatusPill(
                                label = item.channelLabel,
                                bg = if (item.delivered) SmsPalette.GreenBg else SmsPalette.AmberBg,
                                border = if (item.delivered) SmsPalette.GreenBorder else SmsPalette.AmberBorder,
                                fg = if (item.delivered) SmsPalette.GreenFg else SmsPalette.AmberFg
                            )
                            Text(item.sourceTitle, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = SmsPalette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(fmt.format(Date(item.timestampMs)), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = SmsPalette.InkMuted)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(item.preview, fontSize = 13.sp, color = SmsPalette.InkPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(item.statusDetail, fontSize = 11.sp, color = SmsPalette.InkMuted)
                }
            }
        }
    }
}

// =====================================================================
// 已安裝 App 搜尋勾選對話框
// =====================================================================
@Composable
private fun AppPickerDialog(
    title: String,
    installedApps: List<InstalledAppInfo>,
    selectedPackages: Set<String>,
    onDismiss: () -> Unit,
    onTogglePackage: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }

    val filtered = remember(installedApps, query, showSystem, selectedPackages) {
        val q = query.trim().lowercase()
        installedApps.filter { app ->
            val passSystem = showSystem || !app.isSystem || app.packageName in selectedPackages ||
                app.packageName == "com.google.android.gms"
            val passQuery = q.isEmpty() || app.label.lowercase().contains(q) || app.packageName.lowercase().contains(q)
            passSystem && passQuery
        }.sortedWith(compareByDescending<InstalledAppInfo> { it.packageName in selectedPackages }.thenBy { it.label.lowercase() })
    }

    AlertDialog(
        containerColor = SmsPalette.Surface,
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = SmsPalette.Ink) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜尋 App 名稱或套件名...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("顯示系統套件（如 Google Play 服務）", fontSize = 12.sp, color = SmsPalette.InkMuted)
                    Switch(checked = showSystem, onCheckedChange = { showSystem = it })
                }
                LazyColumn(modifier = Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(filtered, key = { it.packageName }) { app ->
                        val checked = app.packageName in selectedPackages
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onTogglePackage(app.packageName) }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { onTogglePackage(app.packageName) })
                            Column(Modifier.weight(1f)) {
                                Text(app.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = SmsPalette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(app.packageName, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = SmsPalette.InkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SmsPalette.InkPrimary,
                    contentColor = SmsPalette.OnPrimary
                )
            ) {
                Text("完成")
            }
        }
    )
}

// =====================================================================
// 共用元件與權限檢查輔助函式
// =====================================================================
@Composable
private fun CraftCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = SmsPalette.Surface,
        border = BorderStroke(1.dp, SmsPalette.Border),
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun StatusPill(label: String, bg: Color, border: Color, fg: Color) {
    Row(
        modifier = Modifier
            .background(bg, RoundedCornerShape(999.dp))
            .border(1.dp, border, RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(modifier = Modifier.size(6.dp).background(fg, CircleShape))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

private fun checkSmsPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
}

private fun checkCallPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
}

private fun checkNotifListenerEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
    val cn = ComponentName(context, NotificationSenseService::class.java).flattenToString()
    return flat.contains(cn) || flat.contains("${context.packageName}/.${NotificationSenseService::class.java.simpleName}")
}

private fun checkAccessibilityEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val cn = ComponentName(context, PromptAccessibilityService::class.java).flattenToString()
    return flat.contains(cn) || flat.contains("${context.packageName}/.service.${PromptAccessibilityService::class.java.simpleName}")
}

@Suppress("DEPRECATION")
private fun loadInstalledApps(context: Context): List<InstalledAppInfo> {
    val pm = context.packageManager
    val apps = try {
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
    } catch (_: Exception) {
        emptyList()
    }
    return apps.mapNotNull { info ->
        if (info.packageName == context.packageName) return@mapNotNull null
        val label = try {
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            info.packageName
        }
        val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        InstalledAppInfo(info.packageName, label, isSystem)
    }
}
