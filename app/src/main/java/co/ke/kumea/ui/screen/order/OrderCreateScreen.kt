package co.ke.kumea.ui.screen.order

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.ke.kumea.data.local.OrderChannels
import co.ke.kumea.util.Money

/**
 * Record a Biofix sale (P1-T3). Channel is a forced explicit choice — there is
 * no default chip, mirroring the server's REQUIRED channel. The agent picker
 * only offers commission-eligible agents (officers are never shown; the server
 * would reject them anyway). Save is ONLINE: a server rejection surfaces its
 * message inline; nothing is stored locally on failure.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderCreateScreen(
    onBack: () -> Unit,
    viewModel: OrderCreateViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var farmerMenuExpanded by remember { mutableStateOf(false) }
    var skuMenuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Record Sale") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Farmer picker (a Farm IS the farmer record) ────────────────
            val selectedFarmerName =
                state.farmers.firstOrNull { it.id == state.selectedFarmerId }?.name
                    ?: "No farmers — pull to refresh"
            ExposedDropdownMenuBox(
                expanded = farmerMenuExpanded,
                onExpandedChange = { farmerMenuExpanded = it },
            ) {
                OutlinedTextField(
                    value = selectedFarmerName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Farmer") },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = farmerMenuExpanded)
                    },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = farmerMenuExpanded,
                    onDismissRequest = { farmerMenuExpanded = false },
                ) {
                    state.farmers.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.name) },
                            onClick = {
                                viewModel.onFarmerSelected(option.id)
                                farmerMenuExpanded = false
                            },
                        )
                    }
                }
            }

            // ── SKU (pack size) ────────────────────────────────────────────
            ExposedDropdownMenuBox(
                expanded = skuMenuExpanded,
                onExpandedChange = { skuMenuExpanded = it },
            ) {
                OutlinedTextField(
                    value = state.sku,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Pack size") },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = skuMenuExpanded)
                    },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = skuMenuExpanded,
                    onDismissRequest = { skuMenuExpanded = false },
                ) {
                    SkuOptions.forEach { sku ->
                        DropdownMenuItem(
                            text = { Text(sku) },
                            onClick = {
                                viewModel.onSkuSelected(sku)
                                skuMenuExpanded = false
                            },
                        )
                    }
                }
            }

            // ── Quantity ───────────────────────────────────────────────────
            val qtyInvalid = state.qty.isNotBlank() &&
                (state.parsedQty == null || state.parsedQty!! <= 0)
            OutlinedTextField(
                value = state.qty,
                onValueChange = viewModel::onQtyChange,
                label = { Text("Quantity") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                isError = qtyInvalid,
                supportingText = {
                    if (qtyInvalid) Text("Whole number, at least 1")
                },
                modifier = Modifier.fillMaxWidth(),
            )

            // ── Unit price (integer cents under the hood; KES at the edge) ─
            val priceInvalid = state.unitPrice.isNotBlank() &&
                (state.parsedUnitPrice == null || state.parsedUnitPrice!! <= 0)
            OutlinedTextField(
                value = state.unitPrice,
                onValueChange = viewModel::onUnitPriceChange,
                label = { Text("Unit price (KES)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                isError = priceInvalid,
                supportingText = {
                    val total = state.lineTotalPreview
                    when {
                        priceInvalid -> Text("More than zero, up to 2 decimals")
                        total != null -> Text("Total: ${Money.formatCents(total)}")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            // ── Channel (REQUIRED — no default) ────────────────────────────
            Text("Channel", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrderChannels.forEach { channel ->
                    FilterChip(
                        selected = state.channel == channel,
                        onClick = { viewModel.onChannelSelected(channel) },
                        label = { Text(channel) },
                    )
                }
            }

            // ── Agent (commercial attribution; officers never offered) ─────
            if (state.agents.isNotEmpty()) {
                Text("Sold by agent (optional)", style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.agents.forEach { agent ->
                        FilterChip(
                            // Selection keys on the stable UUID (attribution);
                            // the label still shows the human-readable code.
                            selected = state.selectedAgentId == agent.id,
                            onClick = { viewModel.onAgentSelected(agent.id) },
                            label = { Text("${agent.agentCode} (${agent.role}, ${agent.region})") },
                        )
                    }
                }
            }

            state.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(
                onClick = { viewModel.saveOrder(onSuccess = onBack) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isSaving,
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Record Sale")
                }
            }
        }
    }
}
