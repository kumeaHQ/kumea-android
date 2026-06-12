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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { WardOutcomesCard(ui) }

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
private fun WardOutcomesCard(ui: OfficerUiState) {
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
            Spacer(Modifier.height(12.dp))
            // Honest: farmer-registration and ward sales totals need a server ward
            // report (farms/orders are user-scoped, not on this device). No fake
            // numbers — flagged plainly rather than left to look broken.
            Text(
                "Farmers registered and ward sales totals are coming — they need a ward report from the server.",
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
