package com.example.khatabook.ui.components

import com.example.khatabook.ui.components.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.example.khatabook.data.api.NetworkClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.CalendarToday
import com.example.khatabook.utils.SheetDates
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Scanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.khatabook.data.model.Party
import com.example.khatabook.ui.theme.*

data class LedgerSheetRecord(
    val id: String,
    val date: String,
    var customerCodeOrName: String,
    var amountCollected: Double,
    var isSelected: Boolean = true,
    var isNewCustomer: Boolean = false,
    var newPhone: String = "",
    var newAddress: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartAiSheetScannerDialog(
    parties: List<Party>,
    onDismiss: () -> Unit,
    onBulkLedgerUpdate: (records: List<LedgerSheetRecord>, date: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var currentStep by remember { mutableStateOf(1) }
    var selectedSource by remember { mutableStateOf("Sample Sheet (21/01/24)") }
    // Today until a scan says otherwise. Previously a hardcoded sample date,
    // which would have been applied verbatim to real collections.
    var sheetDate by remember { mutableStateOf(SheetDates.format(SheetDates.today())) }
    var sheetDateError by remember { mutableStateOf<String?>(null) }
    var showSheetDatePicker by remember { mutableStateOf(false) }
    // Whether the date shown was actually read off the sheet, so the UI can
    // say "extracted" versus "defaulted to today" rather than implying the
    // model found a date it never saw.
    var sheetDateWasExtracted by remember { mutableStateOf(false) }
    var editingRecordId by remember { mutableStateOf<String?>(null) }
    var editAmountText by remember { mutableStateOf("") }
    var editNameText by remember { mutableStateOf("") }
    val context = LocalContext.current
    var uploadError by remember { mutableStateOf<String?>(null) }
    
    val extractedRecords = remember { mutableStateListOf<LedgerSheetRecord>() }

    if (showSheetDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = SheetDates.parseOrNull(sheetDate) ?: SheetDates.today()
        )
        DatePickerDialog(
            onDismissRequest = { showSheetDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        sheetDate = SheetDates.format(it)
                        sheetDateError = null
                    }
                    showSheetDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showSheetDatePicker = false }) { Text("CANCEL") }
            }
        ) {
            DatePicker(state = pickerState, colors = khatabookDatePickerColors())
        }
    }

    /**
     * Validates the sheet date, then hands the records over.
     *
     * Returns false (and shows the error) rather than silently substituting a
     * date: these rows become real money in real ledgers, so a date the user
     * cannot see is worse than refusing to proceed.
     */
    fun acceptRecords(active: List<LedgerSheetRecord>): Boolean {
        val parsed = SheetDates.parseOrNull(sheetDate)
        if (parsed == null) {
            sheetDateError = "Enter a valid date (${SheetDates.DISPLAY_PATTERN.lowercase()})"
            return false
        }
        if (!SheetDates.isNotInFuture(parsed)) {
            sheetDateError = "Date cannot be in the future"
            return false
        }
        onBulkLedgerUpdate(active, SheetDates.format(parsed))
        return true
    }
    val coroutineScope = rememberCoroutineScope()
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    fun performScan(imagePart: MultipartBody.Part) {
        currentStep = 2 // 2 means scanning
        uploadError = null
        coroutineScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    NetworkClient.apiService.extractLedger(imagePart)
                }
                
                if (response.isSuccessful) {
                    val data = response.body()
                    if (data != null) {
                        // The backend already drops dates that aren't real
                        // calendar dates, so anything arriving here is either
                        // genuine or empty. Re-parsing locally keeps this
                        // correct even against an older backend.
                        val extractedSheetDate = SheetDates.parseOrNull(data.date)
                        sheetDateWasExtracted = extractedSheetDate != null
                        sheetDate = SheetDates.format(extractedSheetDate ?: SheetDates.today())
                        sheetDateError = null
                        extractedRecords.clear()
                        
                        data.records.forEach { extracted ->
                            // Try to find a matching party (exact match or partial match)
                            val matchedParty = parties.find { 
                                it.name.equals(extracted.customerName, ignoreCase = true) 
                            } ?: parties.find { 
                                it.name.contains(extracted.customerName, ignoreCase = true) || 
                                extracted.customerName.contains(it.name, ignoreCase = true)
                            }
                            
                            val finalName = matchedParty?.name ?: extracted.customerName
                            
                            // A row keeps its own date only when the sheet
                            // actually carried a different one for that row;
                            // otherwise it follows the sheet date, including
                            // any edit the user makes before accepting.
                            val rowDate = SheetDates.parseOrNull(extracted.transactionDate)

                            extractedRecords.add(
                                LedgerSheetRecord(
                                    id = UUID.randomUUID().toString(),
                                    date = rowDate?.let { SheetDates.format(it) } ?: "",
                                    customerCodeOrName = finalName,
                                    amountCollected = extracted.amount,
                                    isSelected = true,
                                    isNewCustomer = matchedParty == null
                                )
                            )
                        }
                        currentStep = 3 // Success, show extracted data
                    } else {
                        uploadError = "AI failed to process the image."
                        currentStep = 1 // Go back to start
                    }
                } else {
                    val rawBody = response.errorBody()?.string()
                    uploadError = when {
                        response.code() == 401 -> "Authentication failed. Please log in again."
                        !rawBody.isNullOrBlank() -> com.example.khatabook.utils.parseApiErrorMessage(
                            rawBody,
                            "Failed to connect to AI server (HTTP ${response.code()}). Check backend URL and network connectivity."
                        )
                        else -> "Failed to connect to AI server (HTTP ${response.code()}). Check backend URL and network connectivity."
                    }
                    currentStep = 1
                }
            } catch (e: Exception) {
                uploadError = "Error: ${e.message}"
                currentStep = 1
            }
        }
    }

    // Both launchers hand off to the same processImageUriForUpload: a
    // full-resolution TakePicture() capture and a gallery pick need
    // identical treatment (decode, EXIF-correct, bound, re-encode), and
    // previously only the camera path got any processing at all.
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        Log.d("SmartAiScanner", "TakePicture result: success=$success uri=$uri")
        if (success && uri != null) {
            selectedSource = "Camera Capture"
            coroutineScope.launch {
                val part = withContext(Dispatchers.Default) { processImageUriForUpload(context, uri) }
                if (part != null) {
                    performScan(part)
                } else {
                    uploadError = "Failed to process photo. Please try again."
                }
            }
        } else if (!success) {
            Log.w("SmartAiScanner", "Camera reported failure/cancellation")
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedSource = "Gallery Image Upload"
            coroutineScope.launch {
                val part = withContext(Dispatchers.Default) { processImageUriForUpload(context, uri) }
                if (part != null) performScan(part) else uploadError = "Failed to read image"
            }
        }
    }

    val selectedCount by remember { derivedStateOf { extractedRecords.count { it.isSelected } } }
    val totalCollected by remember { derivedStateOf { extractedRecords.filter { it.isSelected }.sumOf { it.amountCollected } } }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier.fillMaxWidth().fillMaxHeight(if (currentStep == 1) 0.52f else 0.88f)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEFF6FF)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Scanner, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Scan Khata", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                            Text(
                                text = when (currentStep) {
                                    1 -> "Select Image Source"
                                    2 -> "Scanning & Extracting Data..."
                                    else -> "OCR Extracted Table & Edit"
                                },
                                fontSize = 10.sp,
                                color = KhatabookHeaderBlue,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    BounceIconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = KhatabookTextMuted)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = KhatabookBorderLight)

                if (currentStep == 1) {
                    // Step 1: Image Source Picker
                    Text("How would you like to scan your ledger notebook page?", fontSize = 12.sp, color = KhatabookTextMuted)
                    Spacer(modifier = Modifier.height(14.dp))

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .bounceClick {
                                try {
                                    val uri = createCameraImageUri(context)
                                    pendingCameraUri = uri
                                    cameraLauncher.launch(uri)
                                } catch (e: Exception) {
                                    uploadError = "Camera not available"
                                }
                            },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, KhatabookBorderLight)
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Take Photo with Camera", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                                Text("Snap a live photo of handwritten notebook page", fontSize = 11.sp, color = KhatabookTextMuted)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .bounceClick {
                                try {
                                    galleryLauncher.launch("image/*")
                                } catch (e: Exception) {
                                    uploadError = "Gallery not available"
                                }
                            },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, KhatabookBorderLight)
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Upload Image from Gallery", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                                Text("Choose an existing ledger page photo from phone gallery", fontSize = 11.sp, color = KhatabookTextMuted)
                            }
                        }
                    }
                    
                    if (uploadError != null) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = uploadError ?: "",
                            fontSize = 12.sp,
                            color = Color.Red,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else if (currentStep == 2) {
                    // Step 2: Scanning Loading State
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = KhatabookHeaderBlue, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("AI is extracting ledger records...", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Analyzing handwriting and values from image", fontSize = 12.sp, color = KhatabookTextMuted)
                        }
                    }
                } else if (currentStep == 3) {
                    // Step 3: Extracted Data Review & Edit Table
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, KhatabookBorderLight)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (sheetDateWasExtracted) "Sheet Date (extracted):" else "Sheet Date (not on sheet):",
                                    fontSize = 11.sp,
                                    color = KhatabookTextMuted
                                )
                                // Editable before accepting — the model can
                                // misread a handwritten date, and this is the
                                // last point at which that is cheap to fix.
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    BasicTextField(
                                        value = sheetDate,
                                        onValueChange = { sheetDate = it; sheetDateError = null },
                                        singleLine = true,
                                        textStyle = androidx.compose.ui.text.TextStyle(
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (sheetDateError != null) KhatabookGiveRed else KhatabookHeaderBlue
                                        ),
                                        modifier = Modifier.width(96.dp)
                                    )
                                    BounceIconButton(
                                        onClick = { showSheetDatePicker = true },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.CalendarToday,
                                            contentDescription = "Pick sheet date",
                                            tint = KhatabookHeaderBlue,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                if (sheetDateError != null) {
                                    Text(sheetDateError!!, fontSize = 10.sp, color = KhatabookGiveRed)
                                }
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text("Total Cash Collected:", fontSize = 11.sp, color = KhatabookTextMuted)
                                Text("₹ ${totalCollected.toInt()}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = KhatabookGotGreen)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Extracted Records ($selectedCount Selected) — Click Edit to alter amount",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = KhatabookTextDark
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Table Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(KhatabookBgLight, RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Select", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = KhatabookTextMuted, modifier = Modifier.width(40.dp))
                        Text("Customer / Code", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = KhatabookTextMuted, modifier = Modifier.weight(1f))
                        Text("Collected (₹)", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = KhatabookTextMuted, modifier = Modifier.width(80.dp))
                        Text("Edit", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = KhatabookTextMuted, modifier = Modifier.width(36.dp))
                    }

                    // Extracted List
                    LazyColumn(
                        modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(extractedRecords) { idx, rec ->
                            val isEditing = editingRecordId == rec.id

                            if (isEditing) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFFEFF6FF), RoundedCornerShape(8.dp))
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = editNameText,
                                        onValueChange = { editNameText = it },
                                        label = { Text("Name", fontSize = 10.sp) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(6.dp)
                                    )

                                    Spacer(modifier = Modifier.width(6.dp))

                                    OutlinedTextField(
                                        value = editAmountText,
                                        onValueChange = { editAmountText = it },
                                        label = { Text("Amount (₹)", fontSize = 10.sp) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        modifier = Modifier.width(90.dp),
                                        shape = RoundedCornerShape(6.dp)
                                    )

                                    Spacer(modifier = Modifier.width(6.dp))

                                    BounceButton(
                                        onClick = {
                                            val newAmt = editAmountText.toDoubleOrNull() ?: rec.amountCollected
                                            extractedRecords[idx] = rec.copy(
                                                customerCodeOrName = editNameText.ifBlank { rec.customerCodeOrName },
                                                amountCollected = newAmt
                                            )
                                            editingRecordId = null
                                        },
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = KhatabookHeaderBlue),
                                        modifier = Modifier.bounceClick {
                                            val newAmt = editAmountText.toDoubleOrNull() ?: rec.amountCollected
                                            extractedRecords[idx] = rec.copy(
                                                customerCodeOrName = editNameText.ifBlank { rec.customerCodeOrName },
                                                amountCollected = newAmt
                                            )
                                            editingRecordId = null
                                        }
                                    ) {
                                        Text("Save", fontSize = 11.sp)
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            extractedRecords[idx] = rec.copy(isSelected = !rec.isSelected)
                                        }
                                        .padding(horizontal = 6.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = rec.isSelected,
                                        onCheckedChange = { checked ->
                                            extractedRecords[idx] = rec.copy(isSelected = checked)
                                        },
                                        modifier = Modifier.size(32.dp)
                                    )

                                    Spacer(modifier = Modifier.width(6.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = rec.customerCodeOrName,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = KhatabookTextDark
                                        )
                                        if (rec.isNewCustomer) {
                                            Text(
                                                text = "New Customer",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = KhatabookGiveRed
                                            )
                                        }
                                    }

                                    Text(
                                        text = "₹ ${rec.amountCollected.toInt()}",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = KhatabookGotGreen,
                                        modifier = Modifier.width(80.dp)
                                    )

                                    BounceIconButton(
                                        onClick = {
                                            editingRecordId = rec.id
                                            editNameText = rec.customerCodeOrName
                                            editAmountText = rec.amountCollected.toInt().toString()
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit Amount", tint = KhatabookHeaderBlue, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }

                            if (idx < extractedRecords.size - 1) {
                                HorizontalDivider(color = KhatabookBorderLight)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    BounceButton(
                        onClick = {
                            val active = extractedRecords.filter { it.isSelected }
                            val newCustomers = active.filter { it.isNewCustomer }
                            if (newCustomers.isNotEmpty()) {
                                currentStep = 4
                            } else {
                                if (acceptRecords(active)) onDismiss()
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = KhatabookGotGreen),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .bounceClick {
                                val active = extractedRecords.filter { it.isSelected }
                                val newCustomers = active.filter { it.isNewCustomer }
                                if (newCustomers.isNotEmpty()) {
                                    currentStep = 4
                                } else {
                                    if (acceptRecords(active)) onDismiss()
                                }
                            }
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "SYNC $selectedCount CUSTOMERS TO LEDGER",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                } else if (currentStep == 4) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "New Customers Found",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = KhatabookTextDark
                        )
                        BounceIconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = KhatabookTextMuted)
                        }
                    }

                    Text(
                        text = "Please enter their contact details so we can add them to your parties list.",
                        fontSize = 12.sp,
                        color = KhatabookTextMuted,
                        modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
                    )

                    val newCustomers = extractedRecords.filter { it.isSelected && it.isNewCustomer }
                    
                    LazyColumn(
                        modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(newCustomers) { idx, rec ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF9FAFB)),
                                border = androidx.compose.foundation.BorderStroke(1.dp, KhatabookBorderLight)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = rec.customerCodeOrName,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = KhatabookTextDark
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = rec.newPhone,
                                        onValueChange = { newPhone ->
                                            val i = extractedRecords.indexOfFirst { it.id == rec.id }
                                            if (i >= 0) extractedRecords[i] = rec.copy(newPhone = newPhone)
                                        },
                                        placeholder = { Text("Phone Number", fontSize = 12.sp) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                        modifier = Modifier.fillMaxWidth().height(50.dp),
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    OutlinedTextField(
                                        value = rec.newAddress,
                                        onValueChange = { newAddr ->
                                            val i = extractedRecords.indexOfFirst { it.id == rec.id }
                                            if (i >= 0) extractedRecords[i] = rec.copy(newAddress = newAddr)
                                        },
                                        placeholder = { Text("Billing Address", fontSize = 12.sp) },
                                        modifier = Modifier.fillMaxWidth().height(50.dp),
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(14.dp))
                    
                    BounceButton(
                        onClick = {
                            val active = extractedRecords.filter { it.isSelected }
                            if (acceptRecords(active)) onDismiss()
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = KhatabookGotGreen),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text(
                            text = "SAVE CUSTOMERS & SYNC",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}
}

private const val SCAN_IMAGE_JPEG_QUALITY = 85

/**
 * Creates a fresh cache-file Uri for a full-resolution camera capture, via
 * the FileProvider already declared in AndroidManifest.xml/file_paths.xml.
 * TakePicture() (unlike the old TakePicturePreview()) needs this Uri up
 * front — it writes the real photo there instead of returning a thumbnail
 * through Binder extras.
 */
fun createCameraImageUri(context: Context): Uri {
    val imagesDir = File(context.cacheDir, "images").apply { mkdirs() }
    val file = File(imagesDir, "camera_${UUID.randomUUID()}.jpg")
    val uri = FileProvider.getUriForFile(context, "com.example.khatabook.fileprovider", file)

    // ActivityResultContracts.TakePicture() does not itself grant the
    // camera app write access to this Uri — grantUriPermissions="true" on
    // the <provider> only makes the provider capable of having permission
    // granted, it does not grant it to whoever receives the capture intent.
    // Without this, some camera apps report success while having written
    // nothing, and the subsequent decode fails with nothing to explain why.
    val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
    val resolvedActivities = context.packageManager.queryIntentActivities(
        captureIntent, PackageManager.MATCH_DEFAULT_ONLY,
    )
    for (info in resolvedActivities) {
        context.grantUriPermission(
            info.activityInfo.packageName, uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    return uri
}

/**
 * Longest edge a decoded scan image is bounded to. Gemini's own image
 * preprocessing downsamples well below this for extraction anyway, so this
 * is not a quality tradeoff for OCR — it exists to bound peak memory during
 * [BitmapFactory.decodeStream], which previously ran at the source's full
 * resolution with no limit at all. A modern phone camera (108MP+, common on
 * mid-range 2024+ devices) decoded uncompressed is comfortably capable of an
 * [OutOfMemoryError] on this path — an `Error`, not an `Exception`, so it
 * would not have been caught by this function's own `catch (e: Exception)`
 * and would have crashed the app instead of surfacing [uploadError].
 */
private const val SCAN_MAX_DIMENSION = 2200

/** The smallest power-of-2 [BitmapFactory.Options.inSampleSize] that brings
 *  the source's longer edge at or under [SCAN_MAX_DIMENSION] — chosen so the
 *  downsampling happens *during* decode (bounding peak memory), not as a
 *  second allocate-then-shrink pass after a full-resolution decode already
 *  happened. */
private fun scanSampleSize(sourceWidth: Int, sourceHeight: Int): Int {
    var sampleSize = 1
    while (maxOf(sourceWidth, sourceHeight) / sampleSize > SCAN_MAX_DIMENSION) {
        sampleSize *= 2
    }
    return sampleSize
}

/**
 * Shared by both the camera and gallery pickers. Previously the camera path
 * captured only a low-resolution preview thumbnail, and the gallery path
 * uploaded the picked file completely unprocessed — mislabeled as
 * image/jpeg regardless of its real format (HEIC is a common default on
 * modern phones). This decodes bounded to [SCAN_MAX_DIMENSION] (see its doc
 * comment), corrects EXIF rotation so a sideways photo doesn't defeat
 * extraction, and always re-encodes as genuine JPEG — so the label sent to
 * the backend is never a lie regardless of the source file's actual format.
 */
private const val SCAN_TAG = "SmartAiScanner"

fun processImageUriForUpload(context: Context, uri: Uri): MultipartBody.Part? {
    return try {
        val orientation = context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = scanSampleSize(bounds.outWidth, bounds.outHeight)
        }

        val decodedBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        }
        if (decodedBitmap == null) {
            Log.e(SCAN_TAG, "Could not decode image at $uri — file missing, unreadable, or not a real image")
            return null
        }

        val rotatedBitmap = applyExifRotation(decodedBitmap, orientation)

        val tempFile = File(context.cacheDir, "scan_${UUID.randomUUID()}.jpg")
        FileOutputStream(tempFile).use { out ->
            rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, SCAN_IMAGE_JPEG_QUALITY, out)
        }

        if (rotatedBitmap !== decodedBitmap) rotatedBitmap.recycle()
        decodedBitmap.recycle()

        val requestFile = tempFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
        MultipartBody.Part.createFormData("image", tempFile.name, requestFile)
    } catch (e: Exception) {
        Log.e(SCAN_TAG, "Failed to process image at $uri", e)
        null
    }
}

/** Camera/gallery JPEGs are frequently stored as upright pixels plus an EXIF
 *  rotation flag rather than pre-rotated — decoding without correcting this
 *  would send Gemini a sideways image and defeat extraction accuracy. */
private fun applyExifRotation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
