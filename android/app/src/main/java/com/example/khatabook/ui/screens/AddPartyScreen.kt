package com.example.khatabook.ui.screens

import com.example.khatabook.ui.components.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.khatabook.data.model.CollectionFrequency
import com.example.khatabook.data.model.PartyType
import com.example.khatabook.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPartyScreen(
    onBackClick: () -> Unit,
    onSaveParty: (
        name: String,
        phone: String,
        type: PartyType,
        frequency: CollectionFrequency,
        gstin: String,
        flat: String,
        area: String,
        pincode: String,
        city: String,
        state: String,
        isShippingSame: Boolean
    ) -> Unit,
    modifier: Modifier = Modifier
) {
    var partyName by remember { mutableStateOf("") }
    var mobileNumber by remember { mutableStateOf("") }
    var partyType by remember { mutableStateOf(PartyType.CUSTOMER) }
    var collectionFrequency by remember { mutableStateOf(CollectionFrequency.DAILY) }
    var showAddressAccordion by remember { mutableStateOf(false) }

    // Address fields
    var gstin by remember { mutableStateOf("") }
    var flatBuilding by remember { mutableStateOf("") }
    var areaLocality by remember { mutableStateOf("") }
    var pincode by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("") }
    var isShippingSame by remember { mutableStateOf(true) }

    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Party", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = {
                    BounceIconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = KhatabookHeaderBlue)
            )
        },
        bottomBar = {
            Surface(
                color = Color.White,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.padding(14.dp)) {
                    BounceButton(
                        onClick = {
                            if (partyName.isNotBlank() && mobileNumber.isNotBlank()) {
                                onSaveParty(
                                    partyName, mobileNumber, partyType, collectionFrequency, gstin,
                                    flatBuilding, areaLocality, pincode, city, state, isShippingSame
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (partyType == PartyType.CUSTOMER) KhatabookHeaderBlue else KhatabookSupplierGreen
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) {
                        Text(
                            text = if (partyType == PartyType.CUSTOMER) "ADD CUSTOMER" else "ADD SUPPLIER",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color.White
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.White)
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            // Party Name Input
            OutlinedTextField(
                value = partyName,
                onValueChange = { partyName = it },
                label = { Text("Party name", fontSize = 12.sp) },
                placeholder = { Text("Enter party name", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = KhatabookHeaderBlue,
                    unfocusedBorderColor = KhatabookBorderLight
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Phone Number Input with Flag
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = "🇮🇳  +91",
                    onValueChange = {},
                    readOnly = true,
                    enabled = false,
                    modifier = Modifier.width(96.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledBorderColor = KhatabookBorderLight,
                        disabledTextColor = KhatabookTextDark,
                        disabledContainerColor = KhatabookBgLight
                    )
                )

                Spacer(modifier = Modifier.width(10.dp))

                OutlinedTextField(
                    value = mobileNumber,
                    onValueChange = { mobileNumber = it },
                    placeholder = { Text("Mobile Number", fontSize = 13.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = KhatabookHeaderBlue,
                        unfocusedBorderColor = KhatabookBorderLight
                    )
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Who are they? Radio Buttons
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Who are they?", fontSize = 13.sp, color = KhatabookTextDark, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.width(12.dp))

                RadioButton(
                    selected = partyType == PartyType.CUSTOMER,
                    onClick = { partyType = PartyType.CUSTOMER },
                    colors = RadioButtonDefaults.colors(selectedColor = KhatabookHeaderBlue)
                )
                Text("Customer", fontSize = 13.sp, modifier = Modifier.clickable { partyType = PartyType.CUSTOMER })

                Spacer(modifier = Modifier.width(12.dp))

                RadioButton(
                    selected = partyType == PartyType.SUPPLIER,
                    onClick = { partyType = PartyType.SUPPLIER },
                    colors = RadioButtonDefaults.colors(selectedColor = KhatabookHeaderBlue)
                )
                Text("Supplier", fontSize = 13.sp, modifier = Modifier.clickable { partyType = PartyType.SUPPLIER })
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Money Collection Frequency Selection Chips
            Text("Money Collection Frequency / Type", fontSize = 13.sp, color = KhatabookTextDark, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = collectionFrequency == CollectionFrequency.DAILY,
                    onClick = { collectionFrequency = CollectionFrequency.DAILY },
                    label = { Text("Daily", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFEFF6FF), selectedLabelColor = KhatabookHeaderBlue)
                )

                FilterChip(
                    selected = collectionFrequency == CollectionFrequency.WEEKLY,
                    onClick = { collectionFrequency = CollectionFrequency.WEEKLY },
                    label = { Text("Weekly", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFEFF6FF), selectedLabelColor = KhatabookHeaderBlue)
                )

                FilterChip(
                    selected = collectionFrequency == CollectionFrequency.MONTHLY,
                    onClick = { collectionFrequency = CollectionFrequency.MONTHLY },
                    label = { Text("Monthly", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFEFF6FF), selectedLabelColor = KhatabookHeaderBlue)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Address Accordion Toggle Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAddressAccordion = !showAddressAccordion }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (showAddressAccordion) "- ADD GSTIN & ADDRESS (OPTIONAL)" else "+ ADD GSTIN & ADDRESS (OPTIONAL)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = KhatabookHeaderBlue
                )
            }

            // Accordion Content
            AnimatedVisibility(visible = showAddressAccordion) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = gstin,
                        onValueChange = { gstin = it },
                        placeholder = { Text("GSTIN", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text("Billing address", fontSize = 12.sp, color = KhatabookTextMuted)
                    Spacer(modifier = Modifier.height(4.dp))

                    OutlinedTextField(
                        value = flatBuilding,
                        onValueChange = { flatBuilding = it },
                        placeholder = { Text("Flat / Building Number", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = areaLocality,
                        onValueChange = { areaLocality = it },
                        placeholder = { Text("Area / Locality", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = pincode,
                        onValueChange = { pincode = it },
                        placeholder = { Text("Pincode", fontSize = 12.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = city,
                            onValueChange = { city = it },
                            placeholder = { Text("City", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        OutlinedTextField(
                            value = state,
                            onValueChange = { state = it },
                            placeholder = { Text("State", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = isShippingSame,
                            onCheckedChange = { isShippingSame = it },
                            colors = CheckboxDefaults.colors(checkedColor = KhatabookHeaderBlue)
                        )
                        Text("Shipping address same as billing address?", fontSize = 12.sp, color = KhatabookTextDark)
                    }
                }
            }
        }
    }
}
