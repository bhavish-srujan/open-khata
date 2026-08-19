package com.example.khatabook.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import coil.compose.AsyncImage
import com.example.khatabook.ui.components.BounceIconButton
import com.example.khatabook.ui.components.bounceClick
import com.example.khatabook.data.repository.KhatabookRepository
import kotlinx.coroutines.launch
import com.example.khatabook.ui.theme.KhatabookBorderLight
import com.example.khatabook.ui.theme.KhatabookGiveRed
import com.example.khatabook.ui.theme.KhatabookHeaderBlue
import com.example.khatabook.ui.theme.KhatabookTextDark
import com.example.khatabook.ui.theme.KhatabookTextMuted
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─── Edit Dialog ────────────────────────────────────────────────────────────

@Composable
private fun EditFieldDialog(
    title: String,
    currentValue: String,
    hint: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(currentValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Edit $title",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = KhatabookTextDark
            )
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(hint.ifBlank { "Enter $title" }, color = KhatabookTextMuted) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = KhatabookHeaderBlue,
                    unfocusedBorderColor = KhatabookBorderLight
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }) {
                Text("Save", color = KhatabookHeaderBlue, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = KhatabookTextMuted)
            }
        },
        shape = RoundedCornerShape(16.dp)
    )
}

// ─── Category Picker Dialog ──────────────────────────────────────────────────

private val BUSINESS_CATEGORY_OPTIONS = listOf(
    "Retail", "Wholesale", "Manufacturing", "Services", "Restaurant / Food",
    "Healthcare", "Education", "Construction", "Transport", "Agriculture",
    "IT / Technology", "Real Estate", "Finance", "Hospitality", "Other"
)

@Composable
private fun CategoryPickerDialog(
    currentValue: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var selected by remember { mutableStateOf(currentValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Business Category", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                for (cat in BUSINESS_CATEGORY_OPTIONS) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = cat }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected == cat,
                            onClick = { selected = cat },
                            colors = RadioButtonDefaults.colors(selectedColor = KhatabookHeaderBlue)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(cat, fontSize = 15.sp, color = KhatabookTextDark)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selected) }) {
                Text("Save", color = KhatabookHeaderBlue, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = KhatabookTextMuted)
            }
        },
        shape = RoundedCornerShape(16.dp)
    )
}

// ─── Main Screen ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookProfileScreen(
    onBackClick: () -> Unit,
    repository: KhatabookRepository
) {
    val context = LocalContext.current
    val prefs = remember {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "khatabook_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    val scope = rememberCoroutineScope()

    val activeBusiness by repository.activeBusiness.collectAsState(initial = null)
    val registeredNumber = prefs.getString("auth_phone", prefs.getString("registered_number", ""))
                              ?.removePrefix("+91") ?: ""

    // ── Editable State ────────────────────────────────────────────
    var userName          by remember(registeredNumber) { mutableStateOf(prefs.getString("user_name_$registeredNumber", "User") ?: "User") }
    var businessName      by remember(activeBusiness?.id) { mutableStateOf(activeBusiness?.name ?: "") }
    var businessAddress   by remember(activeBusiness?.id) { mutableStateOf(activeBusiness?.address ?: "") }
    var businessCategory  by remember(activeBusiness?.id) { mutableStateOf(activeBusiness?.category ?: "") }
    var businessType      by remember(activeBusiness?.id) { mutableStateOf(activeBusiness?.type ?: "") }
    var gstin             by remember(activeBusiness?.id) { mutableStateOf(activeBusiness?.gstNumber ?: "") }
    var bankAccount       by remember(activeBusiness?.id) { mutableStateOf(activeBusiness?.bankAccount ?: "") }
    var profileImageUri   by remember(activeBusiness?.id) { mutableStateOf(activeBusiness?.profileImageUrl) }

    // ── Dialog state ──────────────────────────────────────────────
    var editingField    by remember { mutableStateOf<String?>(null) }
    var showCategoryPicker by remember { mutableStateOf(false) }

    // ── Image Pickers ─────────────────────────────────────────────
    var showImagePicker by remember { mutableStateOf(false) }
    var tempCameraUri   by remember { mutableStateOf<Uri?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            profileImageUri = it.toString()
            scope.launch { repository.updateBusinessProfile(profileImageUrl = it.toString()) }
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && tempCameraUri != null) {
            profileImageUri = tempCameraUri.toString()
            scope.launch { repository.updateBusinessProfile(profileImageUrl = tempCameraUri.toString()) }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val photoFile = File.createTempFile(
                "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}_", ".jpg", context.cacheDir
            )
            tempCameraUri = FileProvider.getUriForFile(context, "com.example.khatabook.fileprovider", photoFile)
            cameraLauncher.launch(tempCameraUri!!)
        } else {
            Toast.makeText(context, "Camera permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Profile strength ──────────────────────────────────────────
    val filledCount = listOf(userName, businessName, businessAddress, businessCategory, businessType, gstin, bankAccount, profileImageUri)
        .count { !it.isNullOrBlank() }
    val strengthPct = (filledCount / 8f)
    val strengthLabel = when {
        strengthPct >= 0.8f -> "Strong"
        strengthPct >= 0.5f -> "Good"
        strengthPct >= 0.3f -> "Fair"
        else -> "Weak"
    }
    val strengthColor = when (strengthLabel) {
        "Strong" -> Color(0xFF16A34A)
        "Good"   -> Color(0xFFF59E0B)
        "Fair"   -> Color(0xFFF97316)
        else     -> KhatabookGiveRed
    }

    // ── Dialogs ───────────────────────────────────────────────────
    when (editingField) {
        "Name" -> EditFieldDialog(
            title = "Name", currentValue = userName,
            onDismiss = { editingField = null },
            onSave = { v ->
                userName = v
                prefs.edit().putString("user_name_$registeredNumber", v).apply()
                scope.launch { repository.updateMe(v) }
                editingField = null
            }
        )
        "Business name" -> EditFieldDialog(
            title = "Business Name", currentValue = businessName,
            onDismiss = { editingField = null },
            onSave = { v ->
                businessName = v
                scope.launch { repository.updateBusinessProfile(name = v) }
                editingField = null
            }
        )
        "Business address" -> EditFieldDialog(
            title = "Business Address", currentValue = businessAddress,
            hint = "e.g. MG Road, Bengaluru 560001",
            onDismiss = { editingField = null },
            onSave = { v ->
                businessAddress = v
                scope.launch { repository.updateBusinessProfile(address = v) }
                editingField = null
            }
        )
        "Business Type" -> EditFieldDialog(
            title = "Business Type", currentValue = businessType,
            hint = "e.g. Proprietorship, Partnership, Pvt Ltd",
            onDismiss = { editingField = null },
            onSave = { v ->
                businessType = v
                scope.launch { repository.updateBusinessProfile(type = v) }
                editingField = null
            }
        )
        "GSTIN" -> EditFieldDialog(
            title = "GSTIN", currentValue = gstin,
            hint = "e.g. 29ABCDE1234F1Z5",
            keyboardType = KeyboardType.Text,
            onDismiss = { editingField = null },
            onSave = { v ->
                gstin = v
                scope.launch { repository.updateBusinessProfile(gstNumber = v) }
                editingField = null
            }
        )
        "Bank account" -> EditFieldDialog(
            title = "Bank Account Number", currentValue = bankAccount,
            hint = "Enter account number",
            keyboardType = KeyboardType.Number,
            onDismiss = { editingField = null },
            onSave = { v ->
                bankAccount = v
                scope.launch { repository.updateBusinessProfile(bankAccount = v) }
                editingField = null
            }
        )
    }

    if (showCategoryPicker) {
        CategoryPickerDialog(
            currentValue = businessCategory,
            onDismiss = { showCategoryPicker = false },
            onSave = { v ->
                businessCategory = v
                scope.launch { repository.updateBusinessProfile(category = v) }
                showCategoryPicker = false
            }
        )
    }

    if (showImagePicker) {
        AlertDialog(
            onDismissRequest = { showImagePicker = false },
            title = {
                Text("Select Image Source", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showImagePicker = false
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                    val photoFile = File.createTempFile(
                                        "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}_", ".jpg", context.cacheDir
                                    )
                                    tempCameraUri = FileProvider.getUriForFile(context, "com.example.khatabook.fileprovider", photoFile)
                                    cameraLauncher.launch(tempCameraUri!!)
                                } else permissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                            .padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, tint = KhatabookHeaderBlue)
                        Spacer(Modifier.width(16.dp))
                        Text("Camera", fontSize = 16.sp)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showImagePicker = false
                                galleryLauncher.launch("image/*")
                            }
                            .padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Description, contentDescription = null, tint = KhatabookHeaderBlue)
                        Spacer(Modifier.width(16.dp))
                        Text("Gallery / Google Photos", fontSize = 16.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showImagePicker = false }) {
                    Text("Cancel", color = KhatabookTextMuted)
                }
            }
        )
    }

    // ── UI ─────────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            Surface(color = KhatabookHeaderBlue, modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.statusBarsPadding()) {
                    TopAppBar(
                        title = { Text("Book Profile", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                        navigationIcon = {
                            BounceIconButton(onClick = onBackClick) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                    )
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(Color.White).padding(paddingValues)
        ) {
            // ── Avatar + Strength ─────────────────────────────────
            item {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(24.dp))
                    Box(modifier = Modifier.size(100.dp).bounceClick { showImagePicker = true }) {
                        if (profileImageUri != null) {
                            AsyncImage(
                                model = profileImageUri, contentDescription = "Profile",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(100.dp).clip(CircleShape).border(2.dp, Color.LightGray, CircleShape)
                            )
                        } else {
                            Box(
                                modifier = Modifier.size(100.dp).clip(CircleShape)
                                    .background(Color(0xFFE5E7EB)).border(2.dp, Color.LightGray, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(60.dp))
                            }
                        }
                        Box(
                            modifier = Modifier.align(Alignment.BottomEnd).offset(x = (-4).dp, y = (-4).dp)
                                .size(28.dp).clip(CircleShape).background(KhatabookHeaderBlue).border(2.dp, Color.White, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Add photo", color = KhatabookHeaderBlue, fontSize = 14.sp, modifier = Modifier.bounceClick { showImagePicker = true })
                    Spacer(Modifier.height(16.dp))

                    // Strength bar
                    Box(modifier = Modifier.fillMaxWidth().height(6.dp).padding(horizontal = 16.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFE5E7EB))) {
                        Box(modifier = Modifier.fillMaxWidth(strengthPct).fillMaxHeight().background(strengthColor))
                    }
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Profile strength : $strengthLabel", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                        Text("${(strengthPct * 100).toInt()}%", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = strengthColor)
                    }

                }
            }

            // ── Personal Info ─────────────────────────────────────
            item { SectionHeader("Personal Info") }
            item {
                ProfileItemRow(
                    icon = Icons.Default.Person, title = "Name",
                    subtitle = userName.ifBlank { "Tap to add name" },
                    subtitleMuted = userName.isBlank(),
                    showChevron = true,
                    onClick = { editingField = "Name" }
                )
                HorizontalDivider(color = Color(0xFFE5E7EB), modifier = Modifier.padding(start = 56.dp, end = 16.dp))
                ProfileItemRow(
                    icon = Icons.Default.Phone, title = "Registered number",
                    subtitle = registeredNumber.ifBlank { "Not available" },
                    showChevron = false,
                    onClick = {}  // Phone number is read-only (set by auth flow)
                )
                HorizontalDivider(color = Color(0xFFE5E7EB), modifier = Modifier.padding(start = 56.dp, end = 16.dp))
                ProfileItemRow(
                    icon = Icons.Default.Store, title = "Business name",
                    subtitle = businessName.ifBlank { "Tap to add business name" },
                    subtitleMuted = businessName.isBlank(),
                    showChevron = true,
                    onClick = { editingField = "Business name" }
                )
            }

            // ── Business Info ─────────────────────────────────────
            item { SectionHeader("Business info") }
            item {
                ProfileItemRow(
                    icon = Icons.Default.LocationOn, title = "Business address",
                    subtitle = businessAddress.ifBlank { null },
                    showChevron = true,
                    showAddDetails = businessAddress.isBlank(),
                    onClick = { editingField = "Business address" }
                )
                HorizontalDivider(color = Color(0xFFE5E7EB), modifier = Modifier.padding(start = 56.dp, end = 16.dp))
                ProfileItemRow(
                    icon = Icons.Default.Category, title = "Business Category",
                    subtitle = businessCategory.ifBlank { null },
                    showChevron = businessCategory.isNotBlank(),
                    showAddDetails = businessCategory.isBlank(),
                    onClick = { showCategoryPicker = true }
                )
                HorizontalDivider(color = Color(0xFFE5E7EB), modifier = Modifier.padding(start = 56.dp, end = 16.dp))
                ProfileItemRow(
                    icon = Icons.Default.Style, title = "Business Type",
                    subtitle = businessType.ifBlank { null },
                    showChevron = businessType.isNotBlank(),
                    showAddDetails = businessType.isBlank(),
                    onClick = { editingField = "Business Type" }
                )
            }

            // ── Financial Info ────────────────────────────────────
            item { SectionHeader("Financial info") }
            item {
                ProfileItemRow(
                    icon = Icons.Default.Assignment, title = "GSTIN",
                    subtitle = gstin.ifBlank { null },
                    showChevron = gstin.isNotBlank(),
                    showAddDetails = gstin.isBlank(),
                    onClick = { editingField = "GSTIN" }
                )
                HorizontalDivider(color = Color(0xFFE5E7EB), modifier = Modifier.padding(start = 56.dp, end = 16.dp))
                ProfileItemRow(
                    icon = Icons.Default.AccountBalance, title = "Bank account",
                    subtitle = bankAccount.ifBlank { null },
                    showChevron = bankAccount.isNotBlank(),
                    showAddDetails = bankAccount.isBlank(),
                    onClick = { editingField = "Bank account" }
                )
            }

            // ── Staff Info ────────────────────────────────────────
            // showAddDetails = false: there is no staff-details destination
            // wired from this screen, so the "Add Details" pill previously
            // shown here did nothing when tapped.
            item { SectionHeader("Staff info") }
            item {
                ProfileItemRow(
                    icon = Icons.Default.Person, title = "Details",
                    subtitle = null, showAddDetails = false, onClick = {}
                )
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

// ─── Shared composables ───────────────────────────────────────────────────────

@Composable
fun SectionHeader(title: String) {
    Box(
        modifier = Modifier.fillMaxWidth().background(Color(0xFFF3F4F6))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(text = title, color = KhatabookTextMuted, fontSize = 14.sp)
    }
}

@Composable
fun ProfileItemRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    subtitleMuted: Boolean = false,
    showChevron: Boolean = false,
    showAddDetails: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().bounceClick(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = title, tint = KhatabookTextDark, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = if (subtitle != null) 12.sp else 16.sp,
                color = if (subtitle != null) KhatabookTextMuted else KhatabookTextDark
            )
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    fontSize = 16.sp,
                    color = if (subtitleMuted) KhatabookTextMuted else KhatabookTextDark
                )
            }
        }
        if (showChevron) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = KhatabookHeaderBlue, modifier = Modifier.size(24.dp))
        } else if (showAddDetails) {
            Surface(
                shape = RoundedCornerShape(50),
                border = androidx.compose.foundation.BorderStroke(1.dp, KhatabookHeaderBlue),
                color = Color.Transparent
            ) {
                Text("Add Details", color = KhatabookHeaderBlue, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}
