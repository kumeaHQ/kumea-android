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
import androidx.compose.material3.HorizontalDivider
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
import co.ke.kumea.domain.model.KumeaNPack
import co.ke.kumea.util.Money

/**
 * Record a Kumea N sale.
 *
 * ── THREE INPUTS REMOVED, 18 AUG (KWAP-06 §3.1–3.3) ─────────────────────────
 *
 * This screen used to ask for a unit PRICE (free text), a CHANNEL (chip row) and
 * a "Sold by" AGENT (a picker listing every commission-eligible agent on the
 * device). All three decided money against a commission engine that is live and
 * backdated to 1 June.
 *
 * They are gone. Price is derived from the pack, channel and attribution from
 * the caller's own agent record. What is left to enter is the farmer, the pack
 * and the quantity — the three things only the person in the shamba knows.
 *
 * The derived values are still SHOWN, read-only. A WAO reading a price back to a
 * farmer needs to see it; what they must not be able to do is disagree with it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderCreateScreen(
    onBack: () -> Unit,
    viewModel: OrderCreateViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var farmerMenuExpanded by remember { mutableStateOf(false) }
    val blocked = viewModel.blockedReason(state)

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
                    ?: "No farmers — register one first"
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

            // ── Pack size — the ONLY thing that decides the price ───────────
            Text("Pack size", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KumeaNPack.catalogue.forEach { pack ->
                    FilterChip(
                        selected = state.pack == pack,
                        onClick = { viewModel.onPackSelected(pack) },
                        label = { Text(pack.label) },
                    )
                }
            }

            // ── Quantity ───────────────────────────────────────────────────
            val qtyInvalid = state.qty.isNotBlank() &&
                (state.parsedQty == null || state.parsedQty!! <= 0)
            OutlinedTextField(
                value = state.qty,
                onValueChange = viewModel::onQtyChange,
                label = { Text("Number of sachets") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                isError = qtyInvalid,
                supportingText = {
                    if (qtyInvalid) Text("Whole number, at least 1")
                },
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()

            // ── The derived money, read-only ───────────────────────────────
            //
            // Shown because a WAO reads the price back to the farmer out loud.
            // NOT editable, because the matrix decides it — see PriceMatrix.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Price per ${state.pack.label} sachet",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        Money.formatCents(state.unitPriceCents),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                state.lineTotalCents?.let { total ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Total", style = MaterialTheme.typography.titleMedium)
                        Text(Money.formatCents(total), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            // ── Who this is attributed to — stated, never chosen ────────────
            state.seller?.let { seller ->
                val attribution = when {
                    seller.attributedAgentCode != null -> "Sold by you (${seller.attributedAgentCode})"
                    seller.attributedAgentId != null -> "Sold by you"
                    // A dealer records the channel and attributes to nobody:
                    // the dealer's margin is Order-level, not a commission rule.
                    else -> "Recorded as ${seller.channel ?: "—"} — no agent commission"
                }
                Text(
                    text = attribution,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            (state.error ?: blocked)?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(
                onClick = { viewModel.saveOrder(onSuccess = onBack) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isSaving && blocked == null,
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
