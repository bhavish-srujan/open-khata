package com.example.khatabook.ui.screens

import com.example.khatabook.ui.components.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.khatabook.ui.components.bounceClick
import com.example.khatabook.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeleteKhataScreen(
    businessName: String = "My Business",
    onBackClick: () -> Unit,
    onDeleteConfirmed: () -> Unit,
    modifier: Modifier = Modifier
) {
    var inputText by remember { mutableStateOf("") }
    val isValid = inputText.trim() == businessName.trim()
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        topBar = {
            Surface(color = KhatabookHeaderBlue, modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.statusBarsPadding()) {
                    TopAppBar(
                        title = { Text("Delete Khata", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                        navigationIcon = {
                            BounceIconButton(onClick = onBackClick) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = KhatabookHeaderBlue)
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.White)
                .padding(20.dp)
        ) {
            Text(
                text = "You are going to delete '$businessName'. All the data in this book on Open Khata and Cashbook will be moved to the Recycle Bin. If you have any entries in a book with the same name on Cashbook, we would advise you to download reports from Cashbook to backup your data.",
                fontSize = 14.sp,
                color = KhatabookGiveRed,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("To delete, type '$businessName'", color = KhatabookTextMuted, fontSize = 14.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(6.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = KhatabookHeaderBlue,
                    unfocusedBorderColor = KhatabookBorderLight
                )
            )

            Spacer(modifier = Modifier.height(20.dp))

            BounceButton(
                onClick = {
                    // Deleting a khata (moving its data to a Recycle Bin, per the
                    // notice above) has no backend or Room support yet — see
                    // RecycleBinScreen's own "DELETE ALL PERMANENTLY" button for the
                    // same, established placeholder. Previously this called
                    // onDeleteConfirmed() directly, which only navigated back
                    // without deleting anything — the business, its parties and its
                    // ledger entries were all left completely untouched, so a user
                    // who typed the confirmation and tapped DELETE was told nothing
                    // and had every reason to believe their data was gone.
                    if (isValid) {
                        android.widget.Toast.makeText(context, "Coming soon", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = isValid,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = KhatabookGiveRed,
                    disabledContainerColor = Color(0xFFD1D5DB),
                    disabledContentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = "DELETE",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color.White
                )
            }
        }
    }
}
