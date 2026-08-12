package co.ke.kumea.ui.screen.note

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.ke.kumea.R
import co.ke.kumea.data.local.NoteType
import co.ke.kumea.ui.common.PaperCard
import co.ke.kumea.ui.theme.Clay
import co.ke.kumea.ui.theme.ClayWash
import co.ke.kumea.ui.theme.KumeaButtonShape
import co.ke.kumea.util.Money
import kotlinx.datetime.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteCreateScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit = onBack,
    viewModel: NoteDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var fieldMenuExpanded by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    val purchaseFallbackBody = state.purchaseItem?.let { stringResource(it.labelRes()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.verb_add_record)) },
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
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Type selector ──────────────────────────────────────────────
            Text(stringResource(R.string.note_type), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NoteType.entries.forEach { type ->
                    FilterChip(
                        selected = state.type == type,
                        onClick = { viewModel.onTypeChange(type) },
                        label = { Text(typeLabel(type)) },
                    )
                }
            }

            // ── Purchase picklist (Build-3 §5) — the input IS the pick ─────
            if (state.type == NoteType.PURCHASE) {
                Text(
                    stringResource(R.string.purchase_what),
                    style = MaterialTheme.typography.labelLarge,
                )
                PurchaseItem.entries.toList().chunked(2).forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowItems.forEach { item ->
                            PurchaseItemCard(
                                item = item,
                                selected = state.purchaseItem == item,
                                onClick = { viewModel.onPurchaseItemSelected(item) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            // ── Amount — neutral Ink, KES at the edge, cents underneath ────
            val amountInvalid = state.amount.isNotBlank() && state.parsedPreview == null
            OutlinedTextField(
                value = state.amount,
                onValueChange = viewModel::onAmountChange,
                label = {
                    Text(
                        if (state.amountRequired) stringResource(R.string.amount_kes)
                        else stringResource(R.string.cost_optional_kes),
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                isError = amountInvalid,
                supportingText = {
                    if (state.amount.isNotBlank()) {
                        val preview = state.parsedPreview
                        if (preview != null) {
                            Text("= ${Money.formatCents(preview)}")
                        } else {
                            Text(stringResource(R.string.amount_hint))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            // ── Note text — optional for a purchase, the record otherwise ──
            OutlinedTextField(
                value = state.body,
                onValueChange = viewModel::onBodyChange,
                label = {
                    Text(
                        if (state.type == NoteType.PURCHASE) stringResource(R.string.note_optional)
                        else stringResource(R.string.note_label),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )

            // ── Field picker (a Note attaches to a Field) ──────────────────
            val selectedFieldName =
                state.fields.firstOrNull { it.id == state.selectedFieldId }?.name
                    ?: stringResource(R.string.no_fields_hint)
            ExposedDropdownMenuBox(
                expanded = fieldMenuExpanded,
                onExpandedChange = { fieldMenuExpanded = it },
            ) {
                OutlinedTextField(
                    value = selectedFieldName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.field_label)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = fieldMenuExpanded)
                    },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = fieldMenuExpanded,
                    onDismissRequest = { fieldMenuExpanded = false },
                ) {
                    state.fields.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.name) },
                            onClick = {
                                viewModel.onFieldSelected(option.id)
                                fieldMenuExpanded = false
                            },
                        )
                    }
                }
            }

            // ── Date (occurredAt) ──────────────────────────────────────────
            Box {
                OutlinedTextField(
                    value = Instant.fromEpochMilliseconds(state.occurredAtMillis).toString().take(10),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.date_label)) },
                    trailingIcon = {
                        Icon(
                            painterResource(R.drawable.ic_calendar),
                            contentDescription = stringResource(R.string.date_label),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                // readOnly text fields swallow clicks — overlay a transparent
                // tap target so the whole field opens the picker.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { showDatePicker = true },
                )
            }

            state.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(
                onClick = { viewModel.saveNote(purchaseFallbackBody, onSuccess = onSaved) },
                shape = KumeaButtonShape,
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
                    Text(stringResource(R.string.save))
                }
            }
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.occurredAtMillis,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let(viewModel::onDateSelected)
                    showDatePicker = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

/**
 * One picklist card: Clay icon, Swahili-first label, Clay Wash + ✓ + weight
 * when selected — never color alone.
 */
@Composable
private fun PurchaseItemCard(
    item: PurchaseItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PaperCard(
        onClick = onClick,
        containerColor = if (selected) ClayWash else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(item.iconRes()),
                contentDescription = null,
                tint = Clay,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(item.labelRes()),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Text("✓", style = MaterialTheme.typography.labelLarge, color = Clay)
            }
        }
    }
}

private fun PurchaseItem.labelRes(): Int = when (this) {
    PurchaseItem.KUMEA_N_SACHET -> R.string.item_kumea_n
    PurchaseItem.SEED -> R.string.item_seed
    PurchaseItem.FERTILISER -> R.string.item_fertiliser
    PurchaseItem.HERBICIDE -> R.string.item_herbicide
    PurchaseItem.LABOUR -> R.string.item_labour
    PurchaseItem.TRANSPORT -> R.string.item_transport
    PurchaseItem.OTHER -> R.string.item_other
}

private fun PurchaseItem.iconRes(): Int = when (this) {
    PurchaseItem.KUMEA_N_SACHET -> R.drawable.ic_sachet
    PurchaseItem.SEED -> R.drawable.ic_seed_bag
    PurchaseItem.FERTILISER -> R.drawable.ic_fertiliser
    PurchaseItem.HERBICIDE -> R.drawable.ic_jerrycan
    PurchaseItem.LABOUR -> R.drawable.ic_hoe
    PurchaseItem.TRANSPORT -> R.drawable.ic_matatu
    PurchaseItem.OTHER -> R.drawable.ic_ellipsis
}

@Composable
private fun typeLabel(type: NoteType): String = when (type) {
    NoteType.ACTIVITY -> stringResource(R.string.activity)
    NoteType.PURCHASE -> stringResource(R.string.purchase)
    NoteType.SALE -> stringResource(R.string.sale)
}
