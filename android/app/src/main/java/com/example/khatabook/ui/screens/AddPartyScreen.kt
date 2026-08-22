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
import com.example.khatabook.data.model.InterestPeriodUnit
import com.example.khatabook.data.model.Party
import com.example.khatabook.data.model.PartyType
import com.example.khatabook.data.repository.PLACEHOLDER_CUSTOMER_PHONE
import com.example.khatabook.ui.theme.*

/** Last-10-digits comparison, tolerant of spaces/dashes/leading-0/+91
 *  formatting differences between what was typed here and what a party was
 *  originally saved with — an exact string match would miss "9876543210"
 *  vs "+91 98765 43210" as the same number. */
private fun normalizedPhoneDigits(phone: String): String =
    phone.filter { it.isDigit() }.takeLast(10)

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
        isShippingSame: Boolean,
        interestEnabled: Boolean,
        interestRate: Double,
        interestPeriodValue: Int,
        interestPeriodUnit: InterestPeriodUnit
    ) -> Unit,
    modifier: Modifier = Modifier,
    initialName: String = "",
    initialPhone: String = "",
    /** Already-saved parties for the active business, used to warn about a
     *  duplicate phone number before it is submitted — see [duplicateParty]. */
    existingParties: List<Party> = emptyList()
) {
    var partyName by remember { mutableStateOf(initialName) }
    var mobileNumber by remember { mutableStateOf(initialPhone) }
    var partyType by remember { mutableStateOf(PartyType.CUSTOMER) }
    var collectionFrequency by remember { mutableStateOf(CollectionFrequency.DAILY) }
    var showAddressAccordion by remember { mutableStateOf(false) }
    var showDuplicateConfirm by remember { mutableStateOf(false) }

    // Never flags PLACEHOLDER_CUSTOMER_PHONE as a "duplicate" — every party
    // created without a real number (e.g. from Scan Khata) shares that exact
    // placeholder, so treating it as a match would flag unrelated parties as
    // duplicates of each other.
    val duplicateParty by remember(mobileNumber, existingParties) {
        derivedStateOf {
            val digits = normalizedPhoneDigits(mobileNumber)
            if (digits.length < 10 || mobileNumber == PLACEHOLDER_CUSTOMER_PHONE) {
                null
            } else {
                existingParties.firstOrNull {
                    it.phone != PLACEHOLDER_CUSTOMER_PHONE && normalizedPhoneDigits(it.phone) == digits
                }
            }
        }
    }

    // Address fields
    var gstin by remember { mutableStateOf("") }
    var flatBuilding by remember { mutableStateOf("") }
    var areaLocality by remember { mutableStateOf("") }
    var pincode by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("") }
    var isShippingSame by remember { mutableStateOf(true) }

    // Interest fields — optional, off by default so a party created without
    // ever touching this section behaves identically to before this feature
    // existed. Kept as raw text so an in-progress "10." or empty field
    // doesn't get clobbered by a parse-and-reformat on every keystroke.
    var showInterestAccordion by remember { mutableStateOf(false) }
    var interestEnabled by remember { mutableStateOf(false) }
    var interestRateText by remember { mutableStateOf("") }
    var interestPeriodValueText by remember { mutableStateOf("") }
    var interestPeriodUnit by remember { mutableStateOf(InterestPeriodUnit.DAYS) }
    var interestError by remember { mutableStateOf<String?>(null) }

    val scrollState = rememberScrollState()

    fun trySave() {
        // Name is the only required field. A phone number is contact detail,
        // not identity — a shopkeeper routinely records a customer before ever
        // having one, and blocking that forced fake numbers into the ledger.
        if (partyName.isBlank()) return

        val rate = interestRateText.toDoubleOrNull()
        val period = interestPeriodValueText.toIntOrNull()
        if (interestEnabled) {
            interestError = when {
                rate == null || rate < 0.0 -> "Enter a valid interest rate"
                period == null || period <= 0 -> "Enter a valid period (greater than 0)"
                else -> null
            }
            if (interestError != null) return
        } else {
            interestError = null
        }

        if (duplicateParty != null) {
            showDuplicateConfirm = true
        } else {
            onSaveParty(
                partyName, mobileNumber, partyType, collectionFrequency, gstin,
                flatBuilding, areaLocality, pincode, city, state, isShippingSame,
                interestEnabled, rate ?: 0.0, period ?: 0, interestPeriodUnit
            )
        }
    }

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
                        onClick = { trySave() },
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
                    placeholder = { Text("Mobile Number (optional)", fontSize = 13.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    isError = duplicateParty != null,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = KhatabookHeaderBlue,
                        unfocusedBorderColor = KhatabookBorderLight
                    )
                )
            }

            duplicateParty?.let { existing ->
                Text(
                    text = "⚠ Already saved as \"${existing.name}\" in this business",
                    fontSize = 12.sp,
                    color = KhatabookGiveRed,
                    modifier = Modifier.padding(top = 4.dp)
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

            Spacer(modifier = Modifier.height(16.dp))

            // Interest Accordion Toggle Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showInterestAccordion = !showInterestAccordion }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (showInterestAccordion) "- ADD INTEREST (OPTIONAL)" else "+ ADD INTEREST (OPTIONAL)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = KhatabookHeaderBlue
                )
            }

            AnimatedVisibility(visible = showInterestAccordion) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = interestEnabled,
                            onCheckedChange = {
                                interestEnabled = it
                                if (!it) interestError = null
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = KhatabookHeaderBlue)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Charge interest on this account", fontSize = 13.sp, color = KhatabookTextDark)
                    }

                    AnimatedVisibility(visible = interestEnabled) {
                        Column(modifier = Modifier.padding(top = 10.dp)) {
                            OutlinedTextField(
                                value = interestRateText,
                                onValueChange = { interestRateText = it; interestError = null },
                                label = { Text("Interest Rate (%)", fontSize = 12.sp) },
                                placeholder = { Text("e.g. 5", fontSize = 13.sp) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = KhatabookHeaderBlue,
                                    unfocusedBorderColor = KhatabookBorderLight
                                )
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = interestPeriodValueText,
                                    onValueChange = { interestPeriodValueText = it; interestError = null },
                                    label = { Text("Period", fontSize = 12.sp) },
                                    placeholder = { Text("e.g. 100", fontSize = 13.sp) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = KhatabookHeaderBlue,
                                        unfocusedBorderColor = KhatabookBorderLight
                                    )
                                )

                                Spacer(modifier = Modifier.width(10.dp))

                                FilterChip(
                                    selected = interestPeriodUnit == InterestPeriodUnit.DAYS,
                                    onClick = { interestPeriodUnit = InterestPeriodUnit.DAYS },
                                    label = { Text("Days", fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFEFF6FF), selectedLabelColor = KhatabookHeaderBlue)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                FilterChip(
                                    selected = interestPeriodUnit == InterestPeriodUnit.MONTHS,
                                    onClick = { interestPeriodUnit = InterestPeriodUnit.MONTHS },
                                    label = { Text("Months", fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFEFF6FF), selectedLabelColor = KhatabookHeaderBlue)
                                )
                            }

                            interestError?.let {
                                Text(it, fontSize = 12.sp, color = KhatabookGiveRed, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDuplicateConfirm) {
        val existing = duplicateParty
        AlertDialog(
            onDismissRequest = { showDuplicateConfirm = false },
            title = { Text("Party already exists") },
            text = {
                Text(
                    if (existing != null)
                        "\"${existing.name}\" is already saved with this phone number in this business. Add \"$partyName\" anyway as a separate party?"
                    else
                        "A party with this phone number already exists. Add anyway?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDuplicateConfirm = false
                    onSaveParty(
                        partyName, mobileNumber, partyType, collectionFrequency, gstin,
                        flatBuilding, areaLocality, pincode, city, state, isShippingSame,
                        interestEnabled, interestRateText.toDoubleOrNull() ?: 0.0,
                        interestPeriodValueText.toIntOrNull() ?: 0, interestPeriodUnit
                    )
                }) { Text("ADD ANYWAY") }
            },
            dismissButton = {
                TextButton(onClick = { showDuplicateConfirm = false }) { Text("CANCEL") }
            }
        )
    }
}
