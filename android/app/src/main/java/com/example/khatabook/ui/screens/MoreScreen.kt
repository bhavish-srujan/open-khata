package com.example.khatabook.ui.screens

import com.example.khatabook.ui.components.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.khatabook.ui.components.bounceClick
import com.example.khatabook.ui.theme.*

data class GridShortcutVector(
    val title: String,
    val icon: ImageVector,
    val iconBgColor: Color
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    onPartiesTabClick: () -> Unit,
    onCashbookClick: () -> Unit,
    onStaffClick: () -> Unit,
    onCollectionClick: () -> Unit,
    onSettingsClick: () -> Unit = {},
    onSmsSettingsClick: () -> Unit = {},
    onSwitchAccountClick: () -> Unit = {},
    onChangeLanguageClick: () -> Unit = {},
    onEditProfileClick: () -> Unit = {},
    onBillsClick: () -> Unit = {},
    onItemsClick: () -> Unit = {},
    onHelpSupportClick: () -> Unit = {},
    onAboutUsClick: () -> Unit = {},
    businessName: String = "My Business",
    userName: String = "User",
    phoneNumber: String = "",
    selectedLanguageName: String = "English",
    /** OWNER/MANAGER only, matching the server's own restriction on staff management. */
    canManageStaff: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scrollState = rememberScrollState()
    var isStoreNameExpanded by remember { mutableStateOf(false) }
    val shortcuts = listOf(
        GridShortcutVector("Cashbook", Icons.Default.AccountBalanceWallet, Color(0xFFE0F2FE)),
        GridShortcutVector("Bills", Icons.AutoMirrored.Filled.ReceiptLong, Color(0xFFFEE2E2)),
        GridShortcutVector("Items", Icons.Default.Inventory, Color(0xFFF3E8FF)),
        GridShortcutVector("Staff", Icons.Default.Group, Color(0xFFFEF3C7)),
        GridShortcutVector("Collection", Icons.Default.CalendarMonth, Color(0xFFFFEDD5)),
        GridShortcutVector("Shop Insurance", Icons.Default.Security, Color(0xFFFCE7F3))
    )

    Scaffold(
        topBar = {
            Surface(color = KhatabookHeaderBlue, modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.statusBarsPadding()) {
                        TopAppBar(
                            title = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Store, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(businessName.ifBlank { "My Business" }, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Switch Profile", tint = Color.White)
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
                shadowElevation = 0.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, KhatabookBorderLight),
                modifier = Modifier.fillMaxWidth().navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // PARTIES TAB (INACTIVE)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .bounceClick { onPartiesTabClick() }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.5f)
                                .height(3.dp)
                                .background(Color.Transparent)
                        )
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(top = 6.dp, bottom = 8.dp)
                        ) {
                            Icon(Icons.Default.Person, contentDescription = "Parties", tint = KhatabookTextMuted, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("Parties", fontSize = 11.sp, color = KhatabookTextMuted)
                        }
                    }

                    // MORE TAB (ACTIVE WITH BLUE TOP LINE)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .bounceClick { }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.5f)
                                .height(3.dp)
                                .background(KhatabookHeaderBlue, RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp))
                        )
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(top = 6.dp, bottom = 8.dp)
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More", tint = KhatabookHeaderBlue, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("More", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = KhatabookHeaderBlue)
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(KhatabookBgLight)
                .verticalScroll(scrollState)
                .padding(14.dp)
        ) {
            // Profile Card (Original App Style)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .bounceClick { onEditProfileClick() }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(50.dp)
                                    .clip(CircleShape)
                                    .background(KhatabookHeaderBlue),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (businessName.isNotBlank()) businessName.take(1).uppercase() else "M",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = if (businessName.isNotBlank()) businessName else userName.ifBlank { "My Business" },
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = KhatabookTextDark
                                )
                                Text(
                                    text = "${phoneNumber.ifBlank { "Not authenticated" }} • Edit Profile",
                                    fontSize = 11.sp,
                                    color = KhatabookHeaderBlue,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = KhatabookTextMuted)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text("BUSINESS SERVICES & TOOLS", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = KhatabookTextMuted, modifier = Modifier.padding(start = 4.dp))

            Spacer(modifier = Modifier.height(8.dp))

            // 6 Grid Shortcuts
            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GridItemCardVector(
                        shortcut = shortcuts[0],
                        modifier = Modifier.weight(1f),
                        onClick = onCashbookClick
                    )
                    GridItemCardVector(
                        shortcut = shortcuts[1],
                        modifier = Modifier.weight(1f),
                        onClick = onBillsClick
                    )
                    GridItemCardVector(
                        shortcut = shortcuts[2],
                        modifier = Modifier.weight(1f),
                        onClick = onItemsClick
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GridItemCardVector(
                        shortcut = shortcuts[3],
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (canManageStaff) {
                                onStaffClick()
                            } else {
                                android.widget.Toast.makeText(
                                    context,
                                    "Only owners and managers can manage staff",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                    GridItemCardVector(
                        shortcut = shortcuts[4],
                        modifier = Modifier.weight(1f),
                        onClick = onCollectionClick
                    )
                    GridItemCardVector(
                        shortcut = shortcuts[5],
                        modifier = Modifier.weight(1f),
                        onClick = {
                            android.widget.Toast.makeText(context, "Shop Insurance is coming soon", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text("OTHERS & SETTINGS", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = KhatabookTextMuted, modifier = Modifier.padding(start = 4.dp))

            Spacer(modifier = Modifier.height(8.dp))

            // Options List
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                    MoreListRowItem(
                        icon = Icons.Default.Settings,
                        title = "Settings",
                        onClick = onSettingsClick
                    )

                    HorizontalDivider(color = KhatabookBorderLight)

                    MoreListRowItem(
                        icon = Icons.Default.SwitchAccount,
                        title = "Switch Account",
                        onClick = onSwitchAccountClick
                    )

                    HorizontalDivider(color = KhatabookBorderLight)

                    MoreListRowItem(
                        icon = Icons.Default.Smartphone,
                        title = "SMS Settings",
                        onClick = onSmsSettingsClick
                    )

                    HorizontalDivider(color = KhatabookBorderLight)

                    MoreListRowItem(
                        icon = Icons.Default.Language,
                        title = "App Language",
                        subtitle = selectedLanguageName,
                        onClick = onChangeLanguageClick
                    )

                    HorizontalDivider(color = KhatabookBorderLight)

                    MoreListRowItem(
                        icon = Icons.AutoMirrored.Filled.Help,
                        title = "Help & Support",
                        onClick = onHelpSupportClick
                    )

                    HorizontalDivider(color = KhatabookBorderLight)

                    MoreListRowItem(
                        icon = Icons.Default.Store,
                        title = "About Us",
                        onClick = onAboutUsClick
                    )
                }
        }
    }
}
}

@Composable
private fun MoreListRowItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bounceClick { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = KhatabookTextDark)
            if (subtitle != null) {
                Text(subtitle, fontSize = 11.sp, color = KhatabookTextMuted)
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = KhatabookTextMuted)
    }
}

@Composable
fun GridItemCardVector(
    shortcut: GridShortcutVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.bounceClick { onClick() },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(shortcut.iconBgColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(shortcut.icon, contentDescription = shortcut.title, tint = KhatabookHeaderBlue, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = shortcut.title,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = KhatabookTextDark
            )
        }
    }
}
