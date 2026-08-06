package com.example.khatabook.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.khatabook.data.api.InventoryItemResponse
import com.example.khatabook.data.api.NetworkClient
import com.example.khatabook.ui.components.BounceExtendedFloatingActionButton
import com.example.khatabook.ui.components.BounceIconButton
import com.example.khatabook.ui.theme.*

data class InventoryItem(val id: String, val name: String, val stock: Int, val price: Double)

/** Maps a server item onto the row model. Stock is decimal server-side (it can
 *  hold weights), but this list shows whole units. */
private fun InventoryItemResponse.toUiItem(): InventoryItem = InventoryItem(
    id = id,
    name = name,
    stock = currentStock.toInt(),
    price = sellingPrice
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemsScreen(
    onBackClick: () -> Unit
) {
    var items by remember { mutableStateOf<List<InventoryItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        isLoading = true
        loadError = null
        try {
            val response = NetworkClient.apiService.getInventoryItems()
            val body = response.body()
            if (response.isSuccessful && body != null) {
                items = body.map { it.toUiItem() }
            } else {
                loadError = when (response.code()) {
                    401 -> "Session expired. Please log in again."
                    403 -> "You do not have permission to view inventory."
                    else -> "Could not load items (HTTP ${response.code()})."
                }
            }
        } catch (e: Exception) {
            loadError = "Could not reach the server. Check your connection."
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            Surface(color = KhatabookHeaderBlue, modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.statusBarsPadding()) {
                    TopAppBar(
                        title = { Text("Items & Inventory", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                        navigationIcon = {
                            BounceIconButton(onClick = onBackClick) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                    )
                }
            }
        },
        floatingActionButton = {
            BounceExtendedFloatingActionButton(
                onClick = { /* TODO: Add item */ },
                containerColor = KhatabookHeaderBlue,
                contentColor = Color.White,
                shape = RoundedCornerShape(50)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Item")
                Spacer(modifier = Modifier.width(8.dp))
                Text("ADD ITEM", fontWeight = FontWeight.Bold)
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(KhatabookBgLight)
                .padding(paddingValues)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text("Your Items", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = KhatabookTextMuted)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                when {
                    isLoading -> item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = KhatabookHeaderBlue
                            )
                        }
                    }

                    loadError != null -> item {
                        Text(
                            text = loadError!!,
                            fontSize = 13.sp,
                            color = KhatabookGiveRed,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
                        )
                    }

                    items.isEmpty() -> item {
                        Text(
                            text = "No items yet. Add one to start tracking stock.",
                            fontSize = 13.sp,
                            color = KhatabookTextMuted,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
                        )
                    }

                    else -> items(items.size) { index ->
                        InventoryItemCard(item = items[index])
                    }
                }
            }
        }
    }
}

@Composable
fun InventoryItemCard(item: InventoryItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(KhatabookHeaderBlue.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Inventory, contentDescription = null, tint = KhatabookHeaderBlue, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = KhatabookTextDark)
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val stockColor = if (item.stock > 50) KhatabookGotGreen else KhatabookGiveRed
                    Text("Stock: ${item.stock}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = stockColor)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("₹ ${item.price}", fontSize = 13.sp, color = KhatabookTextMuted)
                }
            }
        }
    }
}
