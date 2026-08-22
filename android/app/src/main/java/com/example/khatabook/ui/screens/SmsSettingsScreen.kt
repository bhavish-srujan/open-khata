package com.example.khatabook.ui.screens

import com.example.khatabook.ui.components.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.khatabook.ui.components.bounceClick
import com.example.khatabook.ui.theme.*

enum class SmsSenderMode {
    MY_NUMBER,
    KHATABOOK_SMS
}

/** Minimum horizontal gap between a settings row's text block and its
 *  trailing control (toggle, value+chevron, etc.), so they can never touch
 *  regardless of text length or translation. */
private val ROW_MIN_GAP = 12.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsSettingsScreen(
    onBackClick: () -> Unit,
    repository: com.example.khatabook.data.repository.KhatabookRepository,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("sms_settings", android.content.Context.MODE_PRIVATE) }
    
    val activeBusiness by repository.activeBusiness.collectAsState(initial = null)
    val bizId = activeBusiness?.id ?: ""

    var senderMode by remember(bizId) { 
        val saved = prefs.getString("sender_mode_$bizId", SmsSenderMode.MY_NUMBER.name)
        mutableStateOf(SmsSenderMode.valueOf(saved ?: SmsSenderMode.MY_NUMBER.name))
    }
    
    var customerCreatedTemplate by remember(bizId) { mutableStateOf(prefs.getString("template_customer_created_$bizId", "Dear {{customer_name}}, your record was created at {{business_name}}.")!!) }
    var customerUpdatedTemplate by remember(bizId) { mutableStateOf(prefs.getString("template_customer_updated_$bizId", "Dear {{customer_name}}, your record has been updated at {{business_name}}. Your current balance is ₹{{balance}}.")!!) }
    var txAddedTemplate by remember(bizId) { mutableStateOf(prefs.getString("template_transaction_added_$bizId", "Dear {{customer_name}}, {{business_name}} {{action}}. {{balance_text}}.")!!) }
    var txUpdatedTemplate by remember(bizId) { mutableStateOf(prefs.getString("template_transaction_updated_$bizId", "Dear {{customer_name}}, a transaction was updated at {{business_name}}. New Balance: ₹{{balance}}.")!!) }
    
    // Off unless this account has explicitly turned it on — see
    // TRANSACTION_SMS_DEFAULT. A stored preference always wins, so an existing
    // user who enabled this keeps it enabled.
    var sendTransactionSms by remember(bizId) {
        val key = "send_transaction_sms_$bizId"
        mutableStateOf(prefs.getBoolean(key, com.example.khatabook.data.repository.TRANSACTION_SMS_DEFAULT))
    }
    
    val simOptions = listOf("Default", "SIM 1 (Jio)", "SIM 2 (Airtel)")
    var selectedSim by remember(bizId) { 
        val savedIndex = prefs.getInt("sim_index_$bizId", 0)
        mutableStateOf(simOptions.getOrElse(savedIndex) { simOptions[0] })
    }
    var showSimDropdown by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            Surface(color = KhatabookHeaderBlue, modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.statusBarsPadding()) {
                    TopAppBar(
                        title = { Text("SMS Settings", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                        navigationIcon = {
                            BounceIconButton(onClick = onBackClick) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = KhatabookHeaderBlue)
                    )
                }
            }
        },
        bottomBar = {
            Surface(
                color = Color.White,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding()
            ) {
                Box(modifier = Modifier.padding(14.dp)) {
                    BounceButton(
                        onClick = {
                            prefs.edit()
                                .putString("sender_mode_$bizId", senderMode.name)
                                .putInt("sim_index_$bizId", simOptions.indexOf(selectedSim).coerceAtLeast(0))
                                .putString("template_customer_created_$bizId", customerCreatedTemplate)
                                .putString("template_customer_updated_$bizId", customerUpdatedTemplate)
                                .putString("template_transaction_added_$bizId", txAddedTemplate)
                                .putString("template_transaction_updated_$bizId", txUpdatedTemplate)
                                .putBoolean("send_transaction_sms_$bizId", sendTransactionSms)
                                .apply()
                            onBackClick()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (senderMode == SmsSenderMode.MY_NUMBER) KhatabookHeaderBlue else Color(0xFF991B1B)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("SAVE", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFFF8FAFC))
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ── Sender ──────────────────────────────────────────────
            SettingsSection(title = "Send SMS From") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF1F5F9), RoundedCornerShape(10.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SenderModeTab(
                        selected = senderMode == SmsSenderMode.MY_NUMBER,
                        icon = Icons.Default.PhoneAndroid,
                        label = "My Number",
                        selectedColor = KhatabookHeaderBlue,
                        modifier = Modifier.weight(1f),
                        onClick = { senderMode = SmsSenderMode.MY_NUMBER }
                    )
                    SenderModeTab(
                        selected = senderMode == SmsSenderMode.KHATABOOK_SMS,
                        icon = Icons.Default.MarkEmailRead,
                        label = "Open Khata SMS",
                        selectedColor = Color(0xFF991B1B),
                        modifier = Modifier.weight(1f),
                        onClick = { senderMode = SmsSenderMode.KHATABOOK_SMS }
                    )
                }

                SettingsDivider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { sendTransactionSms = !sendTransactionSms },
                    // Centers the toggle against the full title+description block, not
                    // just the title line. The card's own 14.dp padding (SettingsSection)
                    // already gives this row a left inset for the text — since the row
                    // fills that width edge-to-edge, the toggle lands the same 14.dp from
                    // the card's right edge too, no extra end-padding needed here.
                    verticalAlignment = Alignment.CenterVertically,
                    // spacedBy, not SpaceBetween: once the text Column is weight(1f) and
                    // expands to fill all remaining width, SpaceBetween has no leftover
                    // space left to distribute and silently becomes a no-op — a longer or
                    // translated description would butt straight up against the toggle.
                    // spacedBy reserves this gap unconditionally.
                    horizontalArrangement = Arrangement.spacedBy(ROW_MIN_GAP)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Send transaction SMS", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                        Text(
                            "Automatically notify customers when transactions are added or updated.",
                            fontSize = 12.sp,
                            color = KhatabookTextMuted,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Switch(
                        checked = sendTransactionSms,
                        onCheckedChange = { sendTransactionSms = it },
                        // Default Material3 size/touch target only (already ≥44dp via
                        // minimumInteractiveComponentSize) — matches every other toggle
                        // in the app; do not override with a custom size here.
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = KhatabookHeaderBlue
                        )
                    )
                }

                if (senderMode == SmsSenderMode.MY_NUMBER) {
                    SettingsDivider()

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showSimDropdown = true },
                        // Same left-content / right-control pattern as "Send
                        // transaction SMS" above, both vertically centered in the
                        // row. SpaceBetween is safe here (unlike that row) because
                        // neither child below uses weight(1f) — there is always
                        // genuine leftover space for it to distribute, so the two
                        // sides can't collapse onto each other the way a
                        // weight(1f) sibling made the toggle row do.
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Description, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("SIM card for SMS", fontSize = 14.sp, color = KhatabookTextDark, fontWeight = FontWeight.Bold)
                        }

                        // DropdownMenu anchors to its nearest Box, at that Box's
                        // top-start corner — wrapping just this trigger (not the
                        // whole row) so the menu opens under "Default ▾" on the
                        // right, instead of under the row's own top-start corner
                        // over on the left, behind the icon/label.
                        Box {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(selectedSim, fontSize = 13.sp, color = KhatabookHeaderBlue, fontWeight = FontWeight.Bold)
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = KhatabookHeaderBlue)
                            }

                            DropdownMenu(expanded = showSimDropdown, onDismissRequest = { showSimDropdown = false }) {
                                simOptions.forEach { sim ->
                                    DropdownMenuItem(
                                        text = { Text(sim, fontSize = 13.sp) },
                                        onClick = {
                                            selectedSim = sim
                                            showSimDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Mode-specific info ──────────────────────────────────
            if (senderMode == SmsSenderMode.MY_NUMBER) {
                SettingsSection(title = "Why use your own number") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        BenefitRow("SMS automatically sent with each entry")
                        BenefitRow("Customer receives SMS from your trusted number")
                        BenefitRow("Complete details with SMS — entry & balance")
                    }
                }
            } else {
                SettingsSection(title = "Free SMS limit") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Message, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            "You can use Open Khata Coins to send SMS after the monthly limit is crossed",
                            fontSize = 12.sp,
                            color = KhatabookTextMuted
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF8FAFC), RoundedCornerShape(8.dp))
                            .border(1.dp, KhatabookBorderLight, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("0", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                            Text("SMS sent this month", fontSize = 10.sp, color = KhatabookTextMuted)
                        }
                        Box(modifier = Modifier.width(1.dp).height(28.dp).background(KhatabookBorderLight))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("100", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                            Text("Monthly SMS limit", fontSize = 10.sp, color = KhatabookTextMuted)
                        }
                    }

                    SettingsDivider()

                    Text("Trade-offs of Open Khata SMS", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        BulletPointRow("SMS received from a Open Khata number", Color(0xFFB91C1C))
                        BulletPointRow("Limited details on the basic SMS", Color(0xFFB91C1C))
                        BulletPointRow("Can be marked as Spam or Promotional", Color(0xFFB91C1C))
                    }
                }
            }

            // ── Preview ─────────────────────────────────────────────
            SettingsSection(
                title = "Message Preview",
                accentColor = if (senderMode == SmsSenderMode.MY_NUMBER) KhatabookHeaderBlue else Color(0xFF991B1B)
            ) {
                Text(
                    text = if (senderMode == SmsSenderMode.MY_NUMBER) "← My Business" else "← JM-KHTABK",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = KhatabookTextDark
                )
                Text("Sunday, 2 Aug, 06:21 PM", fontSize = 11.sp, color = KhatabookTextMuted)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF8FAFC), RoundedCornerShape(8.dp))
                        .border(1.dp, KhatabookBorderLight, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = if (senderMode == SmsSenderMode.MY_NUMBER)
                            "Hi, Entry added for Rs -200 on 03 Apr. Total dues : Rs +200.\nCheck history: https://khatabook.com/t/XXX\nThank you\nMy Business"
                        else
                            "You gave: ₹ 400\nBalance: +(₹ 200)\nSent by: 6366605032\n\nDetails: https://khatabook.com/t/XXX",
                        fontSize = 12.sp,
                        color = KhatabookTextDark,
                        lineHeight = 18.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = KhatabookTextMuted, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (senderMode == SmsSenderMode.MY_NUMBER) "Max SMS limit: as per your mobile plan" else "Max SMS limit: 100 per month",
                        fontSize = 11.sp,
                        color = KhatabookTextMuted
                    )
                }
            }

            // ── Templates ───────────────────────────────────────────
            SettingsSection(
                title = "Message Templates",
                subtitle = "Placeholders: {{customer_name}}, {{business_name}}, {{balance}}, {{action}}, {{balance_text}}"
            ) {
                SmsTemplateEditor("Customer Created", customerCreatedTemplate) { customerCreatedTemplate = it }
                SmsTemplateEditor("Customer Updated", customerUpdatedTemplate) { customerUpdatedTemplate = it }
                SmsTemplateEditor("Transaction Added", txAddedTemplate) { txAddedTemplate = it }
                SmsTemplateEditor("Transaction Updated", txUpdatedTemplate) { txUpdatedTemplate = it }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

/**
 * One titled card, consistently styled — every group on this screen (sender
 * mode, mode-specific info, preview, templates) uses this instead of each
 * rolling its own card/spacing/border combination.
 */
@Composable
private fun SettingsSection(
    title: String,
    subtitle: String? = null,
    accentColor: Color = KhatabookHeaderBlue,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
        if (subtitle != null) {
            Text(subtitle, fontSize = 12.sp, color = KhatabookTextMuted, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(modifier = Modifier.height(10.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = androidx.compose.foundation.BorderStroke(1.dp, KhatabookBorderLight)
        ) {
            Box(modifier = Modifier.background(accentColor.copy(alpha = 0.06f))) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
private fun SettingsDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(KhatabookBorderLight))
}

@Composable
private fun SenderModeTab(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selectedColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.bounceClick(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) selectedColor else Color.Transparent
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 2.dp else 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) Color.White else KhatabookTextMuted,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) Color.White else KhatabookTextMuted
            )
        }
    }
}

@Composable
fun SmsTemplateEditor(title: String, value: String, onValueChange: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
        border = androidx.compose.foundation.BorderStroke(1.dp, KhatabookBorderLight)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = KhatabookBorderLight,
                    focusedBorderColor = KhatabookHeaderBlue,
                    unfocusedContainerColor = Color.White,
                    focusedContainerColor = Color.White
                )
            )
        }
    }
}

@Composable
private fun BenefitRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, fontSize = 12.sp, color = KhatabookTextDark)
    }
}

@Composable
private fun BulletPointRow(text: String, bulletColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(bulletColor))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, fontSize = 12.sp, color = KhatabookTextDark)
    }
}
