package co.ke.kumea.ui.screen.note

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.ke.kumea.data.local.NoteEntity
import co.ke.kumea.data.local.NoteType
import co.ke.kumea.ui.common.PullToRefresh
import co.ke.kumea.util.Money

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteListScreen(
    onBack: () -> Unit,
    onAddNote: () -> Unit,
    viewModel: NoteListViewModel = hiltViewModel(),
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Long)
            viewModel.onErrorShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddNote) {
                Icon(Icons.Default.Add, contentDescription = "Add Note")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        PullToRefresh(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (notes.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 96.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("No notes yet. Tap + to record an activity, purchase, or sale.")
                        }
                    }
                } else {
                    items(notes, key = { it.id }) { note ->
                        NoteItem(note = note)
                    }
                }
            }
        }
    }
}

@Composable
fun NoteItem(note: NoteEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = note.body, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${note.type.name.lowercase().replaceFirstChar { it.uppercase() }} · ${note.occurredAt.take(10)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (note.pendingSync) {
                    Text(
                        text = "PENDING",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }

            note.amountCents?.let { cents ->
                Text(
                    text = signedAmount(note.type, cents),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = amountColor(note.type),
                )
            }
        }
    }
}

/**
 * The type carries the sign (the money contract): SALE is income (+), PURCHASE
 * and ACTIVITY-with-cost are expenses (−). Formatting happens only here, at the
 * display edge — the stored value is always an unsigned magnitude of cents.
 */
private fun signedAmount(type: NoteType, cents: Long): String {
    val sign = if (type == NoteType.SALE) "+" else "−"
    return "$sign${Money.formatCents(cents)}"
}

private val IncomeGreen = Color(0xFF2E7D32)

@Composable
private fun amountColor(type: NoteType): Color =
    if (type == NoteType.SALE) IncomeGreen else MaterialTheme.colorScheme.error
