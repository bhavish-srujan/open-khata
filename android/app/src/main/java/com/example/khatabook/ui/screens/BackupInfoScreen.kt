package com.example.khatabook.ui.screens

import com.example.khatabook.ui.components.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.khatabook.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupInfoScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            Surface(color = KhatabookHeaderBlue, modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.statusBarsPadding()) {
                    TopAppBar(
                        title = { Text("Backup Information", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
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
                text = "Your data is automatically backed-up when your phone is connected to the internet. In case you format this phone or mistakenly delete the app, just download the app again and login using your Khatabook-registered number. Your data will be automatically restored.",
                fontSize = 14.sp,
                color = KhatabookTextMuted,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "LAST BACKUP AT",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = KhatabookTextMuted
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "06:20 PM 02 Aug 2026",
                fontSize = 14.sp,
                color = KhatabookTextDark,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
