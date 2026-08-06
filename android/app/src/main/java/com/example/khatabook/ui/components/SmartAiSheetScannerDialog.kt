package com.example.khatabook.ui.components

import com.example.khatabook.ui.components.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import android.net.Uri
import android.content.Context
import androidx.compose.ui.platform.LocalContext
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
    var isSelected: Boolean = true
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
    var sheetDate by remember { mutableStateOf("21/01/2024") }
    var editingRecordId by remember { mutableStateOf<String?>(null) }
    var editAmountText by remember { mutableStateOf("") }
    var editNameText by remember { mutableStateOf("") }
    val context = LocalContext.current
    var uploadError by remember { mutableStateOf<String?>(null) }
    
    val extractedRecords = remember { mutableStateListOf<LedgerSheetRecord>() }
    val coroutineScope = rememberCoroutineScope()

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
                        sheetDate = data.date.takeIf { it.isNotBlank() } ?: "Unknown Date"
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
                            
                            extractedRecords.add(
                                LedgerSheetRecord(
                                    id = UUID.randomUUID().toString(),
                                    date = data.date,
                                    customerCodeOrName = finalName,
                                    amountCollected = extracted.amount,
                                    isSelected = true
                                )
                            )
                        }
                        currentStep = 3 // Success, show extracted data
                    } else {
                        uploadError = "AI failed to process the image."
                        currentStep = 1 // Go back to start
                    }
                } else {
                    val serverMessage = response.errorBody()?.string()?.takeIf { it.isNotBlank() }
                    uploadError = when {
                        response.code() == 401 -> "Authentication failed. Please log in again."
                        serverMessage != null -> "Server error ${response.code()}: $serverMessage"
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

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            selectedSource = "Camera Capture"
            val part = bitmapToMultipart(context, bitmap)
            if (part != null) performScan(part) else uploadError = "Failed to process photo"
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedSource = "Gallery Image Upload"
            val part = uriToMultipart(context, uri)
            if (part != null) performScan(part) else uploadError = "Failed to read image"
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
                                    cameraLauncher.launch(null)
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
                } else {
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
                            Column {
                                Text("Extracted Sheet Date:", fontSize = 11.sp, color = KhatabookTextMuted)
                                Text(sheetDate, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = KhatabookHeaderBlue)
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
                        itemsIndexed(extractedRecords) { idx, record ->
                            val isEditing = editingRecordId == record.id

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
                                            val newAmt = editAmountText.toDoubleOrNull() ?: record.amountCollected
                                            extractedRecords[idx] = record.copy(
                                                customerCodeOrName = editNameText.ifBlank { record.customerCodeOrName },
                                                amountCollected = newAmt
                                            )
                                            editingRecordId = null
                                        },
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = KhatabookHeaderBlue),
                                        modifier = Modifier.bounceClick {
                                            val newAmt = editAmountText.toDoubleOrNull() ?: record.amountCollected
                                            extractedRecords[idx] = record.copy(
                                                customerCodeOrName = editNameText.ifBlank { record.customerCodeOrName },
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
                                            extractedRecords[idx] = record.copy(isSelected = !record.isSelected)
                                        }
                                        .padding(horizontal = 6.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = record.isSelected,
                                        onCheckedChange = { checked ->
                                            extractedRecords[idx] = record.copy(isSelected = checked)
                                        },
                                        modifier = Modifier.size(32.dp)
                                    )

                                    Spacer(modifier = Modifier.width(6.dp))

                                    Text(
                                        text = record.customerCodeOrName,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = KhatabookTextDark,
                                        modifier = Modifier.weight(1f)
                                    )

                                    Text(
                                        text = "₹ ${record.amountCollected.toInt()}",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = KhatabookGotGreen,
                                        modifier = Modifier.width(80.dp)
                                    )

                                    BounceIconButton(
                                        onClick = {
                                            editingRecordId = record.id
                                            editNameText = record.customerCodeOrName
                                            editAmountText = record.amountCollected.toInt().toString()
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
                            onBulkLedgerUpdate(active, sheetDate)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = KhatabookGotGreen),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .bounceClick {
                                val active = extractedRecords.filter { it.isSelected }
                                onBulkLedgerUpdate(active, sheetDate)
                                onDismiss()
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
                }
            }
        }
    }
}

fun uriToMultipart(context: Context, uri: Uri): MultipartBody.Part? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val tempFile = File(context.cacheDir, "scan_${UUID.randomUUID()}.jpg")
        val outputStream = FileOutputStream(tempFile)
        inputStream.copyTo(outputStream)
        outputStream.close()
        inputStream.close()
        val requestFile = tempFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
        MultipartBody.Part.createFormData("image", tempFile.name, requestFile)
    } catch (e: Exception) {
        null
    }
}

fun bitmapToMultipart(context: Context, bitmap: Bitmap): MultipartBody.Part? {
    return try {
        val tempFile = File(context.cacheDir, "scan_${UUID.randomUUID()}.jpg")
        val outputStream = FileOutputStream(tempFile)
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        outputStream.flush()
        outputStream.close()
        val requestFile = tempFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
        MultipartBody.Part.createFormData("image", tempFile.name, requestFile)
    } catch (e: Exception) {
        null
    }
}
