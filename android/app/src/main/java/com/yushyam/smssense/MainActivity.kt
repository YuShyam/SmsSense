package com.yushyam.smssense

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.yushyam.smssense.data.model.ForwardEvent
import com.yushyam.smssense.data.model.ForwardLogItem
import com.yushyam.smssense.data.model.InstalledAppInfo
import com.yushyam.smssense.data.model.SmsSenseConfig
import com.yushyam.smssense.data.model.SourceChannel
import com.yushyam.smssense.data.repo.SmsSensePrefs
import com.yushyam.smssense.engine.ForwardRuleEngine
import com.yushyam.smssense.engine.TelegramSender
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

// 與 DnsSense 完全統一之 Shadcn Zinc 極簡中性色票
private object SmsPalette {
    val Bg = Color(0xFFFAFAFA)
    val Surface = Color(0xFFFFFFFF)
    val Subtle = Color(0xFFF4F4F5)
    val Border = Color(0xFFE4E4E7)
    val Ink = Color(0xFF09090B)
    val InkPrimary = Color(0xFF18181B)
    val InkMuted = Color(0xFF71717A)
    val InkFaint = Color(0xFFA1A1AA)

    val GreenBg = Color(0xFFECFDF5)
    val GreenBorder = Color(0xFFA7F3D0)
    val GreenFg = Color(0xFF047857)

    val AmberBg = Color(0xFFFFFBEB)
    val AmberBorder = Color(0xFFFDE68A)
    val AmberFg = Color(0xFFB45309)

    val RedBg = Color(0xFFFEF2F2)
    val RedFg = Color(0xFFBE123C)
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
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = SmsPalette.InkPrimary,
                    onPrimary = Color.White,
                    secondary = Color(0xFF3F3F46),
                    background = SmsPalette.Bg,
                    surface = SmsPalette.Surface,
                    surfaceVariant = SmsPalette.Subtle,
                    outline = SmsPalette.Border
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = SmsPalette.Bg
                ) {
                    SmsSenseApp(
                        context = this,
                        onRequestSmsPermission = { requestSmsPermissions() },
                        onShowToast = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
                    )
                }
            }
        }
    }

    private fun requestSmsPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG
        )
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

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val loaded = loadInstalledApps(context)
            withContext(Dispatchers.Main) { installedApps = loaded }
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
                            val ready = config.enabled && config.isTelegramConfigured
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
                        if (!config.isTelegramConfigured) {
                            onShowToast("請先到「設定」填寫 Telegram Bot Token 與 Chat ID")
                            selectedTab = SmsTab.SETTINGS
                            return@OverviewTab
                        }
                        isSendingTest = true
                        scope.launch(Dispatchers.IO) {
                            val testEvent = ForwardEvent(
                                channel = SourceChannel.SMS,
                                packageName = "sms",
                                sourceTitle = "系統測試",
                                content = "【SmsSense】您的驗證碼是 829401，請於 5 分鐘內完成驗證。轉發通道連線正常。"
                            )
                            val html = ForwardRuleEngine.formatTelegramHtml(
                                config = config,
                                event = testEvent,
                                deviceModel = Build.MODEL.orEmpty()
                            )
                            val (ok, msg) = TelegramSender.sendHtmlMessage(
                                config.telegramBotToken,
                                config.telegramChatId,
                                html
                            )
                            prefs.appendLog(
                                ForwardLogItem(
                                    timestampMs = System.currentTimeMillis(),
                                    channelLabel = "測試訊息",
                                    sourceTitle = "Telegram Bot",
                                    preview = "測試連線推播",
                                    delivered = ok,
                                    statusDetail = msg
                                )
                            )
                            withContext(Dispatchers.Main) {
                                isSendingTest = false
                                refreshState()
                                onShowToast(if (ok) "測試訊息已送達 Telegram！" else msg)
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
                    onChangeBatteryThreshold = { threshold ->
                        val next = config.copy(lowBatteryThreshold = threshold)
                        prefs.saveConfig(next)
                        config = next
                        prefs.setLowBatteryAlertSent(false)
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
                    hasSmsPerm = hasSmsPerm,
                    hasCallPerm = hasCallPerm,
                    hasNotifPerm = hasNotifPerm,
                    hasPromptPerm = hasPromptPerm,
                    onSaveTelegram = { alias, token, chatId ->
                        val next = config.copy(
                            deviceAlias = alias,
                            telegramBotToken = token,
                            telegramChatId = chatId
                        )
                        prefs.saveConfig(next)
                        config = next
                        onShowToast("已儲存 Telegram 設定")
                    },
                    onRequestSmsPerm = onRequestSmsPermission,
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

    // 已安裝 App 搜尋勾選對話框（供通知白名單 / 彈窗白名單共用）
    pickerTarget?.let { target ->
        val existing = if (target == WhitelistTarget.NOTIF) config.whitelistedNotifPackages
        else config.whitelistedPromptPackages

        AppPickerDialog(
            title = if (target == WhitelistTarget.NOTIF) "選擇通知轉發白名單 App" else "選擇全畫面登入彈窗監聽 App",
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
    onGoSettings: () -> Unit,
    onGoWhitelist: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 14.dp)
    ) {
        // Hero 狀態卡
        item {
            CraftCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Telegram 單向轉發引擎", fontSize = 12.sp, color = SmsPalette.InkMuted)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (config.enabled) "守護運作中" else "已暫停轉發",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = SmsPalette.Ink
                        )
                        Text(
                            when {
                                !config.isTelegramConfigured -> "尚未設定 Bot Token 與 Chat ID"
                                config.deviceAlias.isNotBlank() -> "${config.deviceAlias} (${Build.MODEL})"
                                else -> "裝置型號：${Build.MODEL}"
                            },
                            fontSize = 13.sp,
                            color = SmsPalette.InkMuted
                        )
                    }
                    Switch(checked = config.enabled, onCheckedChange = onToggleEnabled)
                }
            }
        }

        // 五軌狀態 Bento 卡
        item {
            CraftCard {
                Text("事件感知與備用機守護狀態", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = SmsPalette.Ink)
                Spacer(Modifier.height(10.dp))
                ChannelStatusRow("實體簡訊全文", config.smsForwardEnabled && hasSmsPerm, if (hasSmsPerm) "已授權" else "缺簡訊權限")
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = SmsPalette.Border)
                ChannelStatusRow(
                    "白名單通知與全畫面通知",
                    config.notificationForwardEnabled && hasNotifPerm,
                    if (hasNotifPerm) "已監聽 ${config.whitelistedNotifPackages.size} 款" else "未開通知存取"
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = SmsPalette.Border)
                ChannelStatusRow(
                    "登入確認彈窗（唯讀）",
                    config.promptForwardEnabled && hasPromptPerm,
                    if (hasPromptPerm) "已監聽 ${config.whitelistedPromptPackages.size} 款" else "未開唯讀無障礙"
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = SmsPalette.Border)
                ChannelStatusRow(
                    "來電響鈴通知",
                    config.callForwardEnabled && hasCallPerm,
                    if (hasCallPerm) "已授權" else "缺通話權限"
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = SmsPalette.Border)
                ChannelStatusRow(
                    "低電量警報（門檻 ${config.lowBatteryThreshold}%）",
                    config.lowBatteryAlertEnabled,
                    if (batteryPercent >= 0) "目前 $batteryPercent%" else "已啟用"
                )
            }
        }

        // 快捷動作
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onSendTest,
                    enabled = !isSendingTest,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SmsPalette.InkPrimary)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isSendingTest) "發送中..." else "測試 Telegram")
                }
                OutlinedButton(
                    onClick = onGoWhitelist,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, SmsPalette.Border)
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
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "目前有 $pendingCount 筆離線待補發訊息，網路恢復後將自動補送至 Telegram。",
                            fontSize = 13.sp,
                            color = SmsPalette.AmberFg
                        )
                    }
                }
            }
        }

        if (!config.isTelegramConfigured || !hasSmsPerm || !hasNotifPerm) {
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
                            Text("尚有初始設定或權限未完成", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = SmsPalette.AmberFg)
                            Text("前往「設定」填入 Telegram 資訊並開啟必要權限。", fontSize = 12.sp, color = Color(0xFF92400E))
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
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = SmsPalette.Ink, modifier = Modifier.weight(1f))
        StatusPill(
            label = badgeText,
            bg = if (active) SmsPalette.GreenBg else SmsPalette.Subtle,
            border = if (active) SmsPalette.GreenBorder else SmsPalette.Border,
            fg = if (active) SmsPalette.GreenFg else SmsPalette.InkMuted
        )
    }
}

// =====================================================================
// Tab 2: 白名單（簡訊全文設定 + 通知白名單 + 彈窗白名單）
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
    onChangeBatteryThreshold: (Int) -> Unit,
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
        // 簡訊全文轉發卡
        item {
            CraftCard {
                Text("實體簡訊轉發（原文完整保留）", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = SmsPalette.Ink)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("轉發所有簡訊原文", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text("保留完整上下文，自行判讀不怕截錯", fontSize = 12.sp, color = SmsPalette.InkMuted)
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
                        Text("頂部附加驗證碼快捷複製", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text("偵測到驗證碼時置頂附一行單擊複製，原文仍完整保留", fontSize = 12.sp, color = SmsPalette.InkMuted)
                    }
                    Switch(checked = config.appendOtpHint, onCheckedChange = onToggleOtpHint)
                }
            }
        }

        // App 通知白名單卡
        item {
            CraftCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("App 通知白名單（含全畫面通知）", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = SmsPalette.Ink)
                        Text("僅轉發下方勾選的 App，杜絕無用通知洗版", fontSize = 12.sp, color = SmsPalette.InkMuted)
                    }
                    Switch(checked = config.notificationForwardEnabled, onCheckedChange = onToggleNotif)
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onAddNotifApp,
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SmsPalette.InkPrimary)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("從已安裝 App 勾選通知白名單")
                }
                Spacer(Modifier.height(10.dp))
                if (config.whitelistedNotifPackages.isEmpty()) {
                    Text("尚未勾選任何 App（目前不會轉發一般 App 通知）", fontSize = 12.sp, color = SmsPalette.InkMuted)
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

        // 前景登入確認彈窗白名單卡
        item {
            CraftCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("全畫面登入彈窗白名單（唯讀）", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = SmsPalette.Ink)
                        Text("偵測 Google 提示／Authenticator／銀行驗證視窗跳出", fontSize = 12.sp, color = SmsPalette.InkMuted)
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
                    Text("從已安裝 App 新增彈窗監聽", color = SmsPalette.InkPrimary)
                }
                Spacer(Modifier.height(10.dp))
                if (config.whitelistedPromptPackages.isEmpty()) {
                    Text("尚未加入任何登入彈窗監聽 App", fontSize = 12.sp, color = SmsPalette.InkMuted)
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

        // 備用機延伸守護：來電通知與低電量警報
        item {
            CraftCard {
                Text("備用機守護（來電與低電量）", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = SmsPalette.Ink)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("來電響鈴通知", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text("備用機響鈴時立即將來電號碼送到 Telegram", fontSize = 12.sp, color = SmsPalette.InkMuted)
                    }
                    Switch(checked = config.callForwardEnabled, onCheckedChange = onToggleCall)
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = SmsPalette.Border)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("低電量充電提醒", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text("低於設定門檻提醒一次，充回門檻 +10% 才重置防洗版", fontSize = 12.sp, color = SmsPalette.InkMuted)
                    }
                    Switch(checked = config.lowBatteryAlertEnabled, onCheckedChange = onToggleLowBattery)
                }
                if (config.lowBatteryAlertEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 20, 25, 30).forEach { pct ->
                            val selected = config.lowBatteryThreshold == pct
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onChangeBatteryThreshold(pct) },
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
                                        color = if (selected) Color.White else SmsPalette.Ink
                                    )
                                }
                            }
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
// Tab 3: 設定（Telegram Bot + 三軌系統權限）
// =====================================================================
@Composable
private fun SettingsTab(
    config: SmsSenseConfig,
    hasSmsPerm: Boolean,
    hasCallPerm: Boolean,
    hasNotifPerm: Boolean,
    hasPromptPerm: Boolean,
    onSaveTelegram: (String, String, String) -> Unit,
    onRequestSmsPerm: () -> Unit,
    onOpenNotifSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    var alias by remember(config.deviceAlias) { mutableStateOf(config.deviceAlias) }
    var token by remember(config.telegramBotToken) { mutableStateOf(config.telegramBotToken) }
    var chatId by remember(config.telegramChatId) { mutableStateOf(config.telegramChatId) }
    var showToken by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 14.dp)
    ) {
        item {
            CraftCard {
                Text("Telegram 推播設定", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = SmsPalette.Ink)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("裝置別名（選填，例如：備用機 S22）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Bot Token（由 @BotFather 取得）") },
                    singleLine = true,
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "切換顯示")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = chatId,
                    onValueChange = { chatId = it },
                    label = { Text("Chat ID（接收者或群組 ID）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { onSaveTelegram(alias, token, chatId) },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SmsPalette.InkPrimary)
                ) {
                    Text("儲存 Telegram 設定")
                }
            }
        }

        item {
            CraftCard {
                Text("系統權限中心（免 Root / 免 Shizuku）", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = SmsPalette.Ink)
                Spacer(Modifier.height(10.dp))

                PermissionRow(
                    title = "1. 簡訊與來電權限 (SMS / Call)",
                    subtitle = "攔截實體簡訊原文與來電號碼提醒",
                    granted = hasSmsPerm && hasCallPerm,
                    actionLabel = "授予",
                    onAction = onRequestSmsPerm
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = SmsPalette.Border)
                PermissionRow(
                    title = "2. 通知存取權限",
                    subtitle = "監聽白名單 App 通知與全畫面通知",
                    granted = hasNotifPerm,
                    actionLabel = "前往開啟",
                    onAction = onOpenNotifSettings
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = SmsPalette.Border)
                PermissionRow(
                    title = "3. 無障礙唯讀彈窗感知（選配）",
                    subtitle = "偵測 Google／銀行全畫面同意登入視窗（嚴格唯讀，無點擊能力）",
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
    val fmt = remember { SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault()) }

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
                        Text("收到簡訊、白名單通知或登入確認彈窗後，會顯示於此。", fontSize = 12.sp, color = SmsPalette.InkMuted)
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
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatusPill(
                                label = item.channelLabel,
                                bg = if (item.delivered) SmsPalette.GreenBg else SmsPalette.AmberBg,
                                border = if (item.delivered) SmsPalette.GreenBorder else SmsPalette.AmberBorder,
                                fg = if (item.delivered) SmsPalette.GreenFg else SmsPalette.AmberFg
                            )
                            Text(item.sourceTitle, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = SmsPalette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
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
            Button(onClick = onDismiss, shape = RoundedCornerShape(10.dp)) {
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
