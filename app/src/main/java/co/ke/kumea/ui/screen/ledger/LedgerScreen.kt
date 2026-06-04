package co.ke.kumea.ui.screen.ledger

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.ke.kumea.data.local.CostCategory
import co.ke.kumea.data.repository.CostCategoryLine
import co.ke.kumea.data.repository.FarmLedger
import co.ke.kumea.data.repository.FieldLedgerLine
import co.ke.kumea.ui.common.PullToRefresh
import co.ke.kumea.util.Money

private val ProfitGreen = Color(0xFF2E7D32)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerScreen(
    onBack: () -> Unit,
    viewModel: LedgerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Long)
            viewModel.onErrorShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profit & Loss") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val ledger = state.ledger
        when {
            // First load with nothing to show yet.
            ledger == null && state.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            // First load failed and we have no last-known data.
            ledger == null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "Couldn't load your P&L. Check your connection and try again.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { viewModel.load() }) { Text("Retry") }
                }
            }
            else -> {
                PullToRefresh(
                    isRefreshing = state.isLoading,
                    onRefresh = { viewModel.load() },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    LedgerContent(ledger = ledger, asOfLabel = state.asOfLabel, isStale = state.isStale)
                }
            }
        }
    }
}

@Composable
private fun LedgerContent(
    ledger: FarmLedger,
    asOfLabel: String?,
    isStale: Boolean,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { HeadlineCard(ledger) }

        item { HonestyStamp(asOfLabel = asOfLabel, isStale = isStale) }

        if (ledger.byField.isNotEmpty()) {
            item {
                Text(
                    "By field",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(ledger.byField, key = { it.fieldId }) { line ->
                FieldLine(line)
            }
        }

        // Cost breakdown by category (Ticket 2.1). Server-derived and ordered;
        // the buckets sum to the Costs total above. Only shown when there are costs.
        if (ledger.byCostCategory.isNotEmpty()) {
            item {
                Text(
                    "Costs by category",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            item { CostCategoryCard(ledger.byCostCategory) }
        }
    }
}

@Composable
private fun CostCategoryCard(lines: List<CostCategoryLine>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            lines.forEach { line ->
                TotalRow(
                    label = categoryLabel(line.category),
                    amount = Money.formatCents(line.costCents),
                    amountColor = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** Display label for a cost category; null is the uncategorised bucket. */
private fun categoryLabel(category: CostCategory?): String =
    category?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Uncategorised"

@Composable
private fun HeadlineCard(ledger: FarmLedger) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = profitLossLabel(ledger.netCents),
                style = MaterialTheme.typography.labelLarge,
                color = netColor(ledger.netCents),
            )
            Text(
                // netCents is already signed (server-derived). The formatter
                // renders negatives correctly, e.g. "KES -1,200.00".
                text = Money.formatCents(ledger.netCents),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = netColor(ledger.netCents),
            )
            Spacer(Modifier.height(16.dp))
            TotalRow("Revenue", Money.formatCents(ledger.revenueCents), ProfitGreen)
            Spacer(Modifier.height(4.dp))
            TotalRow("Costs", Money.formatCents(ledger.costCents), MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun TotalRow(label: String, amount: String, amountColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            amount,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = amountColor,
        )
    }
}

@Composable
private fun HonestyStamp(asOfLabel: String?, isStale: Boolean) {
    val text = when {
        isStale && asOfLabel != null ->
            "Offline — showing last known P&L from $asOfLabel. May be outdated."
        isStale ->
            "Offline — showing last known P&L. May be outdated."
        asOfLabel != null ->
            "Reflects synced notes as of $asOfLabel. Pull to refresh your notes first for the latest."
        else ->
            "Reflects synced notes only. Pull to refresh your notes first for the latest."
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isStale) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FieldLine(line: FieldLedgerLine) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = line.fieldName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = Money.formatCents(line.netCents),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = netColor(line.netCents),
            )
        }
    }
}

private fun profitLossLabel(netCents: Long): String = when {
    netCents > 0 -> "Profit"
    netCents < 0 -> "Loss"
    else -> "Break-even"
}

@Composable
private fun netColor(netCents: Long): Color = when {
    netCents > 0 -> ProfitGreen
    netCents < 0 -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
}
