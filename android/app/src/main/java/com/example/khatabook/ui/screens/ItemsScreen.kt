package com.example.khatabook.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.khatabook.data.api.CreateItemRequest
import com.example.khatabook.data.api.InventoryItemResponse
import com.example.khatabook.data.api.NetworkClient
import com.example.khatabook.ui.components.BounceButton
import com.example.khatabook.ui.components.BounceExtendedFloatingActionButton
import com.example.khatabook.ui.components.BounceIconButton
import com.example.khatabook.ui.components.BounceTextButton
import com.example.khatabook.ui.theme.*
import kotlinx.coroutines.launch

data class InventoryItem(val id: String, val name: String, val stock: Int, val price: Double, val lowStockThreshold: Int)

/** Maps a server item onto the row model. Stock is decimal server-side (it can
 *  hold weights), but this list shows whole units. */
private fun InventoryItemResponse.toUiItem(): InventoryItem = InventoryItem(
    id = id,
    name = name,
    stock = currentStock.toInt(),
    price = sellingPrice,
    lowStockThreshold = lowStockThreshold.toInt()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemsScreen(
    onBackClick: () -> Unit,
    /** OWNER/MANAGER only, matching the server's own restriction on item creation. */
    canManageInventory: Boolean = true,
    repository: com.example.khatabook.data.repository.KhatabookRepository
) {
    // Repository-cached: renders whatever was cached instantly, refreshes in
    // the background. This used to be a bare NetworkClient call on every
    // visit, with a blocking spinner and nothing shown offline.
    val cachedItems by repository.items.collectAsState()
    val items = cachedItems.map { it.toUiItem() }
    var isLoading by remember { mutableStateOf(cachedItems.isEmpty()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var showAddItemDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    suspend fun reload(force: Boolean = false) {
        isLoading = cachedItems.isEmpty()
        loadError = null
        val ok = repository.syncItems(force = force)
        if (!ok && cachedItems.isEmpty()) {
            // Only surface an error when there is nothing cached to fall back
            // on — a failed background refresh over an existing cache is not
            // something the user needs to be told about.
            loadError = "Could not reach the server. Check your connection."
        }
        isLoading = false
    }

    LaunchedEffect(Unit) { reload() }

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
                onClick = {
                    if (canManageInventory) {
                        showAddItemDialog = true
                    } else {
                        android.widget.Toast.makeText(
                            context,
                            "Only owners and managers can add items",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                },
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

    if (showAddItemDialog) {
        var nameText by remember { mutableStateOf("") }
        var priceText by remember { mutableStateOf("") }
        var stockText by remember { mutableStateOf("") }
        var nameError by remember { mutableStateOf<String?>(null) }
        var isSaving by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { if (!isSaving) showAddItemDialog = false },
            title = { Text("Add Item", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = nameText,
                        onValueChange = { nameText = it; nameError = null },
                        label = { Text("Item name") },
                        singleLine = true,
                        isError = nameError != null,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    if (nameError != null) {
                        Text(nameError!!, color = KhatabookGiveRed, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = priceText,
                        onValueChange = { priceText = it },
                        label = { Text("Selling price (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = stockText,
                        onValueChange = { stockText = it },
                        label = { Text("Opening stock") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            },
            confirmButton = {
                BounceButton(
                    onClick = {
                        if (nameText.isBlank()) {
                            nameError = "Enter an item name"
                        } else {
                            isSaving = true
                            scope.launch {
                                try {
                                    val response = NetworkClient.apiService.createItem(
                                        CreateItemRequest(
                                            name = nameText.trim(),
                                            sellingPrice = priceText.toDoubleOrNull(),
                                            currentStock = stockText.toDoubleOrNull()
                                        )
                                    )
                                    if (response.isSuccessful) {
                                        showAddItemDialog = false
                                        reload(force = true)
                                    } else {
                                        android.widget.Toast.makeText(
                                            context,
                                            "Could not add item (HTTP ${response.code()})",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, "Could not reach the server", android.widget.Toast.LENGTH_SHORT).show()
                                } finally {
                                    isSaving = false
                                }
                            }
                        }
                    },
                    enabled = !isSaving,
                    colors = ButtonDefaults.buttonColors(containerColor = KhatabookHeaderBlue)
                ) {
                    Text(if (isSaving) "Saving..." else "SAVE ITEM", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                BounceTextButton(onClick = { if (!isSaving) showAddItemDialog = false }) {
                    Text("Cancel")
                }
            }
        )
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
                    val stockColor = if (item.stock > item.lowStockThreshold) KhatabookGotGreen else KhatabookGiveRed
                    Text("Stock: ${item.stock}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = stockColor)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("₹ ${item.price}", fontSize = 13.sp, color = KhatabookTextMuted)
                }
            }
        }
    }
}
