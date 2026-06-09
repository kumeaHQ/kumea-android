package co.ke.kumea.ui.screen.distribution

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Phase 1a · T5-slice device-demo screen. Drives the attribution slice by hand
 * so the Phase-1a gate is runnable on a real device against Railway. Debug-only;
 * the persona UI is T7.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DistributionDemoScreen(
    onBack: () -> Unit,
    viewModel: DistributionDemoViewModel = hiltViewModel(),
) {
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val officers by viewModel.officers.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    var region by remember { mutableStateOf("Nandi") }
    var farmerName by remember { mutableStateOf("Demo Farmer") }

    // The most-recently onboarded officer endorses the next village_agent; the
    // most-recent agent overall is the farmer's referrer.
    val endorser = officers.firstOrNull()
    val referrer = agents.firstOrNull()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Distribution (demo)") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = region,
                onValueChange = { region = it },
                label = { Text("Region") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Button(
                onClick = { viewModel.onboardOfficer(region) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("1 · Onboard officer (endorser)") }

            Button(
                onClick = { viewModel.onboardVillageAgent(region, endorser?.id) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (endorser != null) "2 · Onboard village_agent (endorsed by ${endorser.agentCode.ifBlank { "officer ${endorser.id.take(6)}" }})"
                    else "2 · Onboard village_agent (no officer yet)",
                )
            }

            OutlinedTextField(
                value = farmerName,
                onValueChange = { farmerName = it },
                label = { Text("Farmer name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Button(
                onClick = { viewModel.registerFarmerWithReferrer(farmerName, referrer?.id) },
                modifier = Modifier.fillMaxWidth(),
                enabled = referrer != null,
            ) {
                Text(
                    if (referrer != null) "3 · Register farmer (referrer: ${referrer.agentCode.ifBlank { referrer.role }})"
                    else "3 · Register farmer (onboard an agent first)",
                )
            }

            Button(
                onClick = { viewModel.syncNow() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("4 · Sync now (agent → farm)")
                }
            }

            HorizontalDivider()
            Text("Agents on device: ${agents.size}", style = MaterialTheme.typography.titleSmall)
            agents.forEach { a ->
                Text(
                    "${a.role} ${a.agentCode.ifBlank { "(code pending)" }} · ${a.region}" +
                        if (a.pendingSync) " · PENDING" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            HorizontalDivider()
            Text(
                text = log,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
