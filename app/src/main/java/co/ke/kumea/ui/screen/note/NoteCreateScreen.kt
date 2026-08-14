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
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.graphics.Color
import co.ke.kumea.ui.theme.Clay
import co.ke.kumea.ui.theme.ClayWash
import co.ke.kumea.ui.theme.InkMuted
import co.ke.kumea.ui.theme.KumeaButtonShape
import co.ke.kumea.ui.theme.LeafGreen
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
    var showDatePicker by remember { mutableStateOf(false) }
    val purchaseFallbackBody = state.purchaseItem?.let { stringResource(it.labelRes()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.mode == NoteMode.OBSERVATION) R.string.note_observation_title
                            else R.string.verb_add_record,
                        ),
                    )
                },
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
            // ── Type selector — TWO LEDGERS ONLY (§2.6) ────────────────────
            //
            // Was `NoteType.entries`, which put Activity in the money picker and
            // made "is this a purchase, a sale, or an activity?" the first
            // question a farmer had to answer. ACTIVITY has its own entry point
            // now, and this row never shows it.
            if (state.mode == NoteMode.MONEY) {
                Text(stringResource(R.string.note_type), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.moneyTypes.forEach { type ->
                        FilterChip(
                            selected = state.type == type,
                            onClick = { viewModel.onTypeChange(type) },
                            label = {
                                // 🔴 THE SIGN IS NOT DECORATION. Around 8% of men
                                // have red–green colour deficiency and this app
                                // serves a mostly-male farming population, so
                                // red-vs-green cannot be the only thing telling
                                // money-out from money-in. The −/+ carries the
                                // meaning; the colour reinforces it.
                                Text("${type.sign()} ${typeLabel(type)}")
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = type.tint().copy(alpha = 0.18f),
                                selectedLabelColor = type.tint(),
                            ),
                        )
                    }
                }
            }

            // ── Purchase picklist (Build-3 §5) — the input IS the pick ─────
            if (state.mode == NoteMode.MONEY && state.type == NoteType.PURCHASE) {
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

            // ── Amount — MONEY MODE ONLY ───────────────────────────────────
            //
            // §2.7 removes "Cost (optional, KES)" from the observation form
            // entirely rather than hiding or disabling it. An optional money
            // field on an observation is what made the activity log a third,
            // ambiguous ledger: a farmer could record a cost there and it would
            // never appear alongside their purchases.
            if (state.mode == NoteMode.MONEY) {
                val amountInvalid = state.amount.isNotBlank() && state.parsedPreview == null
                OutlinedTextField(
                    value = state.amount,
                    onValueChange = viewModel::onAmountChange,
                    label = { Text(stringResource(R.string.amount_kes)) },
                    // The sign again, in the field itself, so the direction is
                    // visible at the moment the number is typed.
                    prefix = {
                        Text(
                            state.type.sign(),
                            color = state.type.tint(),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = amountInvalid,
                    supportingText = {
                        if (state.amount.isNotBlank()) {
                            val preview = state.parsedPreview
                            if (preview != null) {
                                Text(
                                    "${state.type.sign()} ${Money.formatCents(preview)}",
                                    color = state.type.tint(),
                                )
                            } else {
                                Text(stringResource(R.string.amount_hint))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

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

            // ── THE FIELD PICKER IS GONE (§2.2) ────────────────────────────
            //
            // It only ever held one value: every farm has exactly one
            // auto-created Field, and NoteDetailViewModel already auto-selected
            // `options.firstOrNull()` — so this dropdown asked the farmer to
            // choose from a list of one, using a word ("Field") that §2.2
            // retires from their vocabulary. Pure removal, per VERIFY-4.
            //
            // NOTHING CHANGED UNDERNEATH. `NoteEntity.fieldId` is untouched, the
            // Field schema is untouched, and the ViewModel still resolves the
            // farm's single field (and still lazily creates one if a pulled farm
            // somehow has none). This removes a question, not a relationship.

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

/**
 * The direction prefix (§2.6). ACTIVITY has no sign because it has no amount.
 *
 * A true minus sign (U+2212), not a hyphen: at the small weights this renders
 * at, a hyphen reads as a dash rather than as arithmetic.
 */
internal fun NoteType.sign(): String = when (this) {
    NoteType.PURCHASE -> "−"
    NoteType.SALE -> "+"
    NoteType.ACTIVITY -> ""
}

/**
 * The colour that accompanies the sign.
 *
 * CLAY, NOT RED, for a purchase. §2.6's table says red, but this codebase made
 * a deliberate and better-reasoned call first — LedgerScreen: "Buckets are
 * inputs, not losses — Clay figures, never red." Seed and fertiliser are
 * investments a farmer chose to make, and colouring them as errors misreads the
 * farm's own accounts back at them.
 *
 * The ticket's actual requirement is unaffected and is met: colour is not the
 * only signal. [sign] carries the direction, and Clay-vs-LeafGreen is a
 * brown/green contrast rather than the red/green pair that ~8% of men cannot
 * separate — so this is strictly safer than what §2.6 specified.
 */
internal fun NoteType.tint(): Color = when (this) {
    NoteType.PURCHASE -> Clay
    NoteType.SALE -> LeafGreen
    NoteType.ACTIVITY -> InkMuted
}
