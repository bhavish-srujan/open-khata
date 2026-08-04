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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.khatabook.ui.theme.*

val businessCategories = listOf(
    "Kirana / Grocery",
    "Apparel / Clothing",
    "Electronics / Mobile",
    "Hardware & Tools",
    "Pharmacy & Medical",
    "Restaurant / Food",
    "Services & Freelance",
    "Personal / Home"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserDetailsScreen(
    onDetailsSubmitted: (userName: String, businessName: String, category: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var userName by remember { mutableStateOf("") }
    var businessName by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(businessCategories[0]) }
    var showCategoryDropdown by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile & Business Setup", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = KhatabookHeaderBlue)
            )
        },
        bottomBar = {
            Surface(
                color = Color.White,
                shadowElevation = 12.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.padding(16.dp)) {
                    BounceButton(
                        onClick = {
                            if (userName.isNotBlank() && businessName.isNotBlank()) {
                                onDetailsSubmitted(userName, businessName, selectedCategory)
                            }
                        },
                        enabled = userName.isNotBlank() && businessName.isNotBlank(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = KhatabookHeaderBlue),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(
                            text = "FINISH SETUP & ENTER APP",
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
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Circle Avatar Preview
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(KhatabookHeaderBlue),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (userName.isNotBlank()) userName.take(1).uppercase() else "👤",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Welcome to Khatabook!",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = KhatabookTextDark
            )
            Text(
                text = "Please enter your profile and business details to complete registration",
                fontSize = 13.sp,
                color = KhatabookTextMuted,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
            )

            // User Name Input
            OutlinedTextField(
                value = userName,
                onValueChange = { userName = it },
                label = { Text("Your Full Name") },
                placeholder = { Text("e.g. Ramesh Kumar") },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = KhatabookHeaderBlue) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = KhatabookHeaderBlue,
                    unfocusedBorderColor = KhatabookBorderLight
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Business Name Input
            OutlinedTextField(
                value = businessName,
                onValueChange = { businessName = it },
                label = { Text("Business / Shop Name") },
                placeholder = { Text("e.g. Ramesh Traders") },
                leadingIcon = { Text("🏢", fontSize = 16.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = KhatabookHeaderBlue,
                    unfocusedBorderColor = KhatabookBorderLight
                )
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Business Category Selector
            Text(
                text = "Select Business Category",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = KhatabookTextDark,
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(modifier = Modifier.height(8.dp))

            ExposedDropdownMenuBox(
                expanded = showCategoryDropdown,
                onExpandedChange = { showCategoryDropdown = !showCategoryDropdown },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = selectedCategory,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showCategoryDropdown) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = KhatabookHeaderBlue,
                        unfocusedBorderColor = KhatabookBorderLight
                    )
                )

                ExposedDropdownMenu(
                    expanded = showCategoryDropdown,
                    onDismissRequest = { showCategoryDropdown = false }
                ) {
                    businessCategories.forEach { category ->
                        DropdownMenuItem(
                            text = { Text(category) },
                            onClick = {
                                selectedCategory = category
                                showCategoryDropdown = false
                            }
                        )
                    }
                }
            }
        }
    }
}
