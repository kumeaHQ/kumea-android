package co.ke.kumea.ui.screen.officer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import co.ke.kumea.data.local.FarmEntity
import co.ke.kumea.domain.model.Crops
import co.ke.kumea.ui.common.PullToRefresh
import co.ke.kumea.ui.common.SyncBadge

/**
 * "Farmers I registered" (KWAP-01 step 4).
 *
 * The list is `GET /farms?registeredBy=me` cached into Room — NOT "farms I own"
 * and NOT ward-scoped. Registering a farmer confers no ownership, so those rows
 * never appear in the ordinary farm list; and a ward-wide view needs a ward
 * column that was deliberately deferred (KWAP-STEP2-DECISIONS §4).
 *
 * No money anywhere: no price, no order, no earnings. This is a register.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FarmerDirectoryScreen(
    onBack: () -> Unit,
    onRegisterFarmer: () -> Unit,
    viewModel: FarmerDirectoryViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

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
                    Column {
                        Text("Farmers I registered")
                        if (!ui.ward.isNullOrBlank()) {
                            Text(
                                ui.ward!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onRegisterFarmer,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Register a farmer") },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        PullToRefresh(
            isRefreshing = refreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (ui.farmers.isEmpty()) {
                    item { EmptyState(ui) }
                } else {
                    item {
                        Text(
                            "${ui.farmers.size} registered",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(ui.farmers, key = { it.id }) { farm -> FarmerRow(farm) }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(ui: FarmerDirectoryUiState) {
    val text = when {
        !ui.identityLoaded -> "Loading your profile…"
        // Distinguishing this from "none yet" matters: one is a normal empty
        // register, the other is an account that can never fill one.
        !ui.hasAgentProfile ->
            "This account has no agent profile, so it can't register farmers. Ask Kumea to set one up."
        else -> "No farmers yet. Tap “Register a farmer” to add the first one."
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FarmerRow(farm: FarmEntity) {
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
                    // The person first — this is a register, not a farm list.
                    // Rows created before v12 carry no farmerName; falling back
                    // to the shamba's name is honest for those, and they are the
                    // only ones that will ever hit it.
                    text = farm.farmerName ?: farm.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                farm.farmerPhone?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                subtitle(farm)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    farm.createdAt.take(10),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Normal,
                )
                SyncBadge(pending = farm.pendingSync)
            }
        }
    }
}

/** Crop · acres · shamba, whichever of them this registration actually has. */
private fun subtitle(farm: FarmEntity): String? {
    val parts = buildList {
        Crops.label(farm.cropType)?.let { add(it) }
        farm.acres?.let { add(if (it == 1.0) "1 acre" else "$it acres") }
        // Only worth showing when it is a real place-name rather than the
        // farmer's name echoed into a NOT NULL column.
        if (farm.farmerName != null && farm.name != farm.farmerName) add(farm.name)
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
