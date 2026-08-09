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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.ke.kumea.R
import co.ke.kumea.data.local.CostCategory
import co.ke.kumea.data.repository.CostCategoryLine
import co.ke.kumea.data.repository.FarmLedger
import co.ke.kumea.data.repository.FieldLedgerLine
import co.ke.kumea.ui.common.PaperCard
import co.ke.kumea.ui.common.PullToRefresh
import co.ke.kumea.ui.theme.Clay
import co.ke.kumea.ui.theme.DeepLeaf
import co.ke.kumea.ui.theme.KumeaButtonShape
import co.ke.kumea.ui.theme.LeafGreen
import co.ke.kumea.ui.theme.Teal
import co.ke.kumea.util.Money

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
                title = { Text(stringResource(R.string.money), color = DeepLeaf) },
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
            // Farm hasn't reached the server yet — calm expected state, not an
            // error (field feedback 11 Jul: a 404 here damaged trust).
            ledger == null && state.notSyncedYet -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_cloud_check),
                        contentDescription = null,
                        tint = Teal,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.ledger_not_synced),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { viewModel.load() }, shape = KumeaButtonShape) {
                        Text(stringResource(R.string.retry))
                    }
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
                        stringResource(R.string.ledger_load_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { viewModel.load() }, shape = KumeaButtonShape) {
                        Text(stringResource(R.string.retry))
                    }
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
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { HeadlineCard(ledger) }

        item { HonestyStamp(asOfLabel = asOfLabel, isStale = isStale) }

        if (ledger.byField.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.ledger_by_field),
                    style = MaterialTheme.typography.titleSmall,
                    color = DeepLeaf,
                    modifier = Modifier.padding(top = 12.dp),
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
                    stringResource(R.string.ledger_by_category),
                    style = MaterialTheme.typography.titleSmall,
                    color = DeepLeaf,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            item { CostCategoryCard(ledger.byCostCategory) }
        }
    }
}

@Composable
private fun CostCategoryCard(lines: List<CostCategoryLine>) {
    PaperCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Buckets are inputs, not losses — Clay figures, never red.
            lines.forEach { line ->
                TotalRow(
                    label = categoryLabel(line.category),
                    amount = Money.formatCents(line.costCents),
                    amountColor = Clay,
                )
            }
        }
    }
}

/** Display label for a cost category; null is the uncategorised bucket. */
@Composable
private fun categoryLabel(category: CostCategory?): String = when (category) {
    CostCategory.BIOFIX -> stringResource(R.string.item_biofix)
    CostCategory.SEED -> stringResource(R.string.item_seed)
    CostCategory.FERTILISER -> stringResource(R.string.item_fertiliser)
    CostCategory.SPRAY -> stringResource(R.string.category_spray)
    CostCategory.LABOUR -> stringResource(R.string.item_labour)
    CostCategory.TRANSPORT -> stringResource(R.string.item_transport)
    CostCategory.OTHER -> stringResource(R.string.item_other)
    null -> stringResource(R.string.category_uncategorised)
}

@Composable
private fun HeadlineCard(ledger: FarmLedger) {
    PaperCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            // The single colored verdict: Leaf profit, Loss Red loss.
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
                fontWeight = FontWeight.SemiBold,
                color = netColor(ledger.netCents),
            )
            Spacer(Modifier.height(16.dp))
            TotalRow(
                stringResource(R.string.ledger_revenue),
                Money.formatCents(ledger.revenueCents),
                LeafGreen,
            )
            Spacer(Modifier.height(4.dp))
            TotalRow(
                stringResource(R.string.ledger_costs),
                Money.formatCents(ledger.costCents),
                Clay,
            )
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

/** As-of honesty stamp — teal, like every sync/honesty state. Not an error. */
@Composable
private fun HonestyStamp(asOfLabel: String?, isStale: Boolean) {
    val text = when {
        isStale && asOfLabel != null -> stringResource(R.string.ledger_stale_as_of, asOfLabel)
        isStale -> stringResource(R.string.ledger_stale)
        asOfLabel != null -> stringResource(R.string.ledger_as_of, asOfLabel)
        else -> stringResource(R.string.ledger_synced_only)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(R.drawable.ic_cloud_check),
            contentDescription = null,
            tint = Teal,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = Teal,
        )
    }
}

@Composable
private fun FieldLine(line: FieldLedgerLine) {
    PaperCard(modifier = Modifier.fillMaxWidth()) {
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

@Composable
private fun profitLossLabel(netCents: Long): String = when {
    netCents > 0 -> stringResource(R.string.profit)
    netCents < 0 -> stringResource(R.string.loss)
    else -> stringResource(R.string.break_even)
}

@Composable
private fun netColor(netCents: Long): Color = when {
    netCents > 0 -> LeafGreen
    netCents < 0 -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
}
