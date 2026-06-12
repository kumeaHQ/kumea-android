package co.ke.kumea.ui.screen.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.ke.kumea.data.local.OrderEntity
import co.ke.kumea.data.repository.EarningsSurface
import co.ke.kumea.ui.common.PullToRefresh
import co.ke.kumea.util.Money

/**
 * Village_agent home (P1-T7): two sections — Earnings (gated/inert) and Sales
 * (own attributed orders). This composable, and ONLY this one, instantiates the
 * earnings surface. It is reached solely via the village_agent route; an
 * extension_officer never navigates here, so the earnings construct is absent
 * from the officer's view hierarchy entirely.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VillageAgentHomeScreen(
    onRecordSale: () -> Unit,
    onOpenDistributionDemo: () -> Unit,
    onLoggedOut: () -> Unit,
    viewModel: VillageAgentHomeViewModel = hiltViewModel(),
) {
    val sales by viewModel.sales.collectAsStateWithLifecycle()
    val earnings by viewModel.earnings.collectAsStateWithLifecycle()
    val loggedOut by viewModel.loggedOut.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(loggedOut) {
        if (loggedOut) {
            onLoggedOut()
            viewModel.onLoggedOutHandled()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sales & Earnings") },
                actions = {
                    TextButton(onClick = onOpenDistributionDemo) { Text("Demo") }
                    TextButton(onClick = { viewModel.logout() }) { Text("Log out") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onRecordSale) {
                Icon(Icons.Default.Add, contentDescription = "Record sale")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        PullToRefresh(
            isRefreshing = earnings.loading,
            onRefresh = { viewModel.loadEarnings() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { EarningsCard(earnings) }

                item {
                    Text(
                        "Your sales",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                if (sales.isEmpty()) {
                    item {
                        Text(
                            "No sales yet. Tap + to record your first sale.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(sales, key = { it.id }) { order -> SaleRow(order) }
                }
            }
        }
    }
}

@Composable
private fun EarningsCard(state: EarningsUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Earnings this period",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            when {
                state.loading && state.surface == null -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                }
                state.error != null && state.surface == null -> {
                    Text(
                        state.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                state.surface != null -> EarningsBody(state.surface)
                else -> {
                    // 200 with no earnings construct — unexpected for an agent, but honest.
                    Text(
                        "No earnings to show yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EarningsBody(surface: EarningsSurface) {
    Text(
        text = Money.formatCents(surface.amountAccruedCents),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(4.dp))
    if (!surface.ratesActive) {
        // The gate: a correct zero, not a bug. Make "not yet active" explicit.
        Text(
            "Commission rates aren't active yet — your sales are still being counted.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }
    Text(
        "${surface.sachetsAttributed} ${if (surface.sachetsAttributed == 1) "sachet" else "sachets"} counted · ${surface.period}",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun SaleRow(order: OrderEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${order.sku} ×${order.qty}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "${channelLabel(order.channel)} · ${order.date.take(10)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    // Line total = qty × unitPrice, the one overflow-checked Long path.
                    text = Money.formatCents(Money.lineTotalCents(order.qty, order.unitPrice)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (order.pendingSync) {
                    Text(
                        text = "PENDING",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}

private fun channelLabel(channel: String): String =
    channel.replaceFirstChar { it.uppercase() }
