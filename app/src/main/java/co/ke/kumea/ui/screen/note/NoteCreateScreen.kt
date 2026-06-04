package co.ke.kumea.ui.screen.note

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
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
import co.ke.kumea.data.local.CostCategory
import co.ke.kumea.data.local.NoteType
import co.ke.kumea.util.Money
import kotlinx.datetime.Instant

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NoteCreateScreen(
    onBack: () -> Unit,
    viewModel: NoteDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var fieldMenuExpanded by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Note") },
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
            // ── Type selector ──────────────────────────────────────────────
            Text("Type", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NoteType.entries.forEach { type ->
                    FilterChip(
                        selected = state.type == type,
                        onClick = { viewModel.onTypeChange(type) },
                        label = { Text(type.label()) },
                    )
                }
            }

            // ── Field picker (a Note attaches to a Field) ──────────────────
            val selectedFieldName =
                state.fields.firstOrNull { it.id == state.selectedFieldId }?.name
                    ?: "No fields — pull to refresh"
            ExposedDropdownMenuBox(
                expanded = fieldMenuExpanded,
                onExpandedChange = { fieldMenuExpanded = it },
            ) {
                OutlinedTextField(
                    value = selectedFieldName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Field") },
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

            // ── Note text ──────────────────────────────────────────────────
            OutlinedTextField(
                value = state.body,
                onValueChange = viewModel::onBodyChange,
                label = { Text("Note") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )

            // ── Amount (integer cents under the hood; KES at the edge) ─────
            val amountInvalid = state.amount.isNotBlank() && state.parsedPreview == null
            OutlinedTextField(
                value = state.amount,
                onValueChange = viewModel::onAmountChange,
                label = {
                    Text(if (state.amountRequired) "Amount (KES)" else "Cost (optional, KES)")
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
                            Text("Digits only, up to 2 decimals")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            // ── Cost category (optional, costs only) ───────────────────────
            // Hidden for a SALE (revenue has no cost category). Tapping the
            // selected chip clears it — the label is entirely optional.
            if (state.showCategory) {
                Text("Cost category (optional)", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CostCategory.entries.forEach { category ->
                        FilterChip(
                            selected = state.costCategory == category,
                            onClick = { viewModel.onCategoryChange(category) },
                            label = { Text(category.label()) },
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
                    label = { Text("Date") },
                    trailingIcon = { Icon(Icons.Default.DateRange, contentDescription = "Pick date") },
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
                onClick = { viewModel.saveNote(onSuccess = onBack) },
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
                    Text("Save Note")
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
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}
