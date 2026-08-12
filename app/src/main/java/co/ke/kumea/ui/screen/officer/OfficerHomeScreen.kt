package co.ke.kumea.ui.screen.officer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.ke.kumea.data.local.AgentEntity

/**
 * Extension_officer home (P1-T7): endorsement + ward outcomes. ZERO commercial
 * surface — there is no earnings/commission/price/margin construct anywhere in
 * this screen or its ViewModel. The earnings composable is in a different route
 * (the village_agent home) the officer never navigates to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfficerHomeScreen(
    onRegisterFarmer: () -> Unit,
    onOpenFarmerDirectory: () -> Unit,
    onLoggedOut: () -> Unit,
    viewModel: OfficerHomeViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val loggedOut by viewModel.loggedOut.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(loggedOut) {
        if (loggedOut) {
            onLoggedOut()
            viewModel.onLoggedOutHandled()
        }
    }
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onMessageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (ui.ward.isNullOrBlank()) "Ward dashboard" else "Ward · ${ui.ward}")
                },
                actions = {
                    TextButton(onClick = { viewModel.refresh() }) { Text("Refresh") }
                    TextButton(onClick = { viewModel.logout() }) { Text("Log out") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onRegisterFarmer,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Register a farmer") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { WardOutcomesCard(ui, onOpenFarmerDirectory) }

            item {
                Text(
                    "Endorse agents in your ward",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (ui.endorseable.isEmpty()) {
                item {
                    Text(
                        if (ui.ward.isNullOrBlank()) {
                            "No ward set on your profile yet."
                        } else {
                            "No agents in your ward yet. Agents you endorse will appear here."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(ui.endorseable, key = { it.id }) { agent ->
                    EndorseableRow(
                        agent = agent,
                        endorsedByMe = agent.endorsedById != null && agent.endorsedById == ui.myAgentId,
                        onEndorse = { viewModel.endorse(agent.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun WardOutcomesCard(ui: OfficerUiState, onOpenFarmerDirectory: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Ward outcomes",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutcomeRow("Active agents in ward", ui.activeAgentsInWard.toString())
            Spacer(Modifier.height(6.dp))
            OutcomeRow("Agents you've endorsed", ui.endorsedByMeCount.toString())
            Spacer(Modifier.height(6.dp))
            // Real, as of KWAP-01 step 4: GET /farms?registeredBy=me, cached in
            // Room. Counts rows still pending push, because a registration saved
            // offline is a registration.
            OutcomeRow("Farmers you've registered", ui.farmersRegisteredByMe.toString())
            Spacer(Modifier.height(12.dp))
            TextButton(
                onClick = onOpenFarmerDirectory,
                contentPadding = PaddingValues(0.dp),
            ) {
                Text("See the farmers you registered →")
            }
            Spacer(Modifier.height(4.dp))
            // Narrowed, not deleted. Registration numbers are now real; ward
            // SALES totals genuinely still need a server ward report, and they
            // are money — which this surface will never show. Saying so beats a
            // silent absence, which is what made the old note worth keeping.
            Text(
                "This counts farmers you registered, not everyone in the ward — a ward-wide view needs a report the server doesn't have yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OutcomeRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EndorseableRow(
    agent: AgentEntity,
    endorsedByMe: Boolean,
    onEndorse: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = agent.agentCode.ifBlank { roleLabel(agent.role) },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = roleLabel(agent.role) + (agent.pendingSync.let { if (it) " · PENDING" else "" }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (endorsedByMe) {
                Text(
                    "Endorsed ✓",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (agent.endorsedById != null) {
                // Endorsed by another officer — not re-endorsable here.
                OutlinedButton(onClick = onEndorse, enabled = false) { Text("Endorsed") }
            } else {
                Button(onClick = onEndorse) { Text("Endorse") }
            }
        }
    }
}

private fun roleLabel(role: String): String =
    role.split('_').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
