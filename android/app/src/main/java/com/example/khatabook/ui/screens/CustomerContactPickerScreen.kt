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

/**
 * Explains why the contact list is empty and offers a way out, instead of a
 * silent blank list indistinguishable from "you have no contacts". Shared by
 * [CustomerContactPickerScreen] and [StaffContactPickerScreen] — same gap,
 * same fix, both in this package so no import is needed at the call site.
 *
 * [ActivityResultContracts.RequestPermission] only ever shows the system
 * dialog once per app install after a permanent denial, so "ask again" is
 * not always possible from here — Settings is the only way back for that
 * case, and is offered unconditionally rather than trying to detect it.
 */
@Composable
internal fun ContactsPermissionDeniedNotice(context: android.content.Context) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Contacts permission needed",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = KhatabookTextDark
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Grant contacts access to pick people from your phonebook, or use \"Add New Customer Manually\" above.",
            fontSize = 13.sp,
            color = KhatabookTextMuted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(14.dp))
        BounceTextButton(
            onClick = {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", context.packageName, null)
                )
                context.startActivity(intent)
            }
        ) {
            Text("OPEN SETTINGS", fontWeight = FontWeight.Bold, color = KhatabookHeaderBlue)
        }
    }
}

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
                    } else if (!permissionGranted) {
                        // Previously a denial just left this list silently empty —
                        // indistinguishable from "you have no contacts" — with no
                        // explanation and no way to grant it short of finding
                        // Settings on your own. "Add New Customer Manually" above
                        // still works either way.
                        item { ContactsPermissionDeniedNotice(context) }
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
