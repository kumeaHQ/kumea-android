package co.ke.kumea.ui.screen.farm

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.ke.kumea.data.local.FarmEntity
import co.ke.kumea.ui.common.PullToRefresh

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FarmListScreen(
    onAddFarm: () -> Unit,
    onOpenFarm: (String) -> Unit,
    onLoggedOut: () -> Unit,
    viewModel: FarmListViewModel = hiltViewModel()
) {
    val farms by viewModel.farms.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val loggedOut by viewModel.loggedOut.collectAsStateWithLifecycle()

    LaunchedEffect(loggedOut) {
        if (loggedOut) {
            onLoggedOut()
            viewModel.onLoggedOutHandled()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Farms") },
                actions = {
                    TextButton(onClick = { viewModel.logout() }) {
                        Text("Log out")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddFarm) {
                Icon(Icons.Default.Add, contentDescription = "Add Farm")
            }
        }
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
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (farms.isEmpty()) {
                    // A single item keeps the list scrollable so the pull-to-refresh
                    // gesture still works on the empty state.
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 96.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No farms found. Tap + to add one.")
                        }
                    }
                } else {
                    items(farms, key = { it.id }) { farm ->
                        FarmItem(farm = farm, onClick = { onOpenFarm(farm.id) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FarmItem(farm: FarmEntity, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = farm.name,
                    style = MaterialTheme.typography.titleMedium
                )
                farm.waterSource?.let {
                    Text(
                        text = "Water: $it",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (farm.pendingSync) {
                Text(
                    text = "PENDING",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}
