package com.example.khatabook.ui.screens

import com.example.khatabook.ui.components.*

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.example.khatabook.utils.fetchDeviceContacts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.khatabook.data.model.CollectionFrequency
import com.example.khatabook.ui.theme.*

data class ContactItem(
    val id: String,
    val name: String,
    val phone: String,
    val avatarText: String,
    val avatarBg: Color
)

private val sampleContacts = listOf(
    ContactItem("c1", "Aakash Sharma", "+91 98111 22334", "AS", Color(0xFFEF4444)),
    ContactItem("c2", "Ankit Patel", "+91 97222 33445", "AP", Color(0xFF3B82F6)),
    ContactItem("c3", "Bhavna Gupta", "+91 99333 44556", "BG", Color(0xFF10B981)),
    ContactItem("c4", "Deepak Verma", "+91 98444 55667", "DV", Color(0xFFF59E0B)),
    ContactItem("c5", "Gaurav Singh", "+91 97555 66778", "GS", Color(0xFF8B5CF6)),
    ContactItem("c6", "Harish Kumar", "+91 99666 77889", "HK", Color(0xFFEC4899)),
    ContactItem("c7", "Karan Malhotra", "+91 98777 88990", "KM", Color(0xFF06B6D4)),
    ContactItem("c8", "Manish Joshi", "+91 97888 99001", "MJ", Color(0xFF84CC16))
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerContactPickerScreen(
    onBackClick: () -> Unit,
    onAddCustomerManualClick: () -> Unit,
    onContactSelectConfirmed: (contact: ContactItem, frequency: CollectionFrequency, amountGiven: Double) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var contactsList by remember { mutableStateOf<List<ContactItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    val context = LocalContext.current
    var permissionGranted by remember { 
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) 
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            permissionGranted = isGranted
            if (!isGranted) isLoading = false
        }
    )

    LaunchedEffect(Unit) {
        if (!permissionGranted) {
            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    LaunchedEffect(permissionGranted) {
        if (permissionGranted) {
            isLoading = true
            val fetched = fetchDeviceContacts(context)
            contactsList = fetched
            isLoading = false
        }
    }

    val filteredContacts = contactsList.filter {
        it.name.contains(searchQuery, ignoreCase = true) || it.phone.contains(searchQuery)
    }

    Scaffold(
        topBar = {
            Surface(color = KhatabookHeaderBlue, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.statusBarsPadding().padding(bottom = 12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        BounceIconButton(onClick = onBackClick) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                        Text("Select Contact", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }

                    Box(modifier = Modifier.padding(horizontal = 14.dp)) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Customer name", fontSize = 13.sp, color = KhatabookTextMuted) },
                            trailingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = KhatabookTextMuted) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White,
                                focusedBorderColor = KhatabookHeaderBlue,
                                unfocusedBorderColor = Color.Transparent
                            )
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.White)
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAddCustomerManualClick() }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFEFF6FF)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = KhatabookHeaderBlue)
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Text(
                                text = "Add New Customer Manually",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = KhatabookHeaderBlue,
                                modifier = Modifier.weight(1f)
                            )

                            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = KhatabookHeaderBlue)
                        }

                        HorizontalDivider(color = KhatabookBorderLight)
                    }

                    if (isLoading) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = KhatabookHeaderBlue)
                            }
                        }
                    }

                    items(filteredContacts) { contact ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    // INSTANTLY SYNC NUMBER & NAME FROM CONTACT
                                    onContactSelectConfirmed(contact, CollectionFrequency.DAILY, 0.0)
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(contact.avatarBg),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = contact.avatarText,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = contact.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = KhatabookTextDark
                                )
                                Text(
                                    text = contact.phone,
                                    fontSize = 12.sp,
                                    color = KhatabookTextMuted
                                )
                            }
                        }
                    }
                }

                // A-Z Sidebar Index
                Column(
                    modifier = Modifier.padding(end = 4.dp, top = 12.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ('A'..'Z').forEach { char ->
                        Text(
                            text = char.toString(),
                            fontSize = 8.sp,
                            color = KhatabookTextMuted,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}
