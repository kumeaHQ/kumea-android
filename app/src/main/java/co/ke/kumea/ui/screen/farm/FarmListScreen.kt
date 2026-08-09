package co.ke.kumea.ui.screen.farm

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.ke.kumea.R
import co.ke.kumea.data.local.FarmEntity
import co.ke.kumea.ui.common.PaperCard
import co.ke.kumea.ui.common.PullToRefresh
import co.ke.kumea.ui.common.SyncBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FarmListScreen(
    onAddFarm: () -> Unit,
    onOpenFarm: (String) -> Unit,
    onLoggedOut: () -> Unit,
    viewModel: FarmListViewModel = hiltViewModel(),
) {
    val farms by viewModel.farms.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val loggedOut by viewModel.loggedOut.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    var menuExpanded by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(loggedOut) {
        if (loggedOut) {
            onLoggedOut()
            viewModel.onLoggedOutHandled()
        }
    }
    LaunchedEffect(errorMessage) {
        errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Long)
            viewModel.onErrorShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.my_farms)) },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.menu))
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.log_out)) },
                            onClick = {
                                menuExpanded = false
                                viewModel.logout()
                            },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddFarm) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_farm))
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        PullToRefresh(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (farms.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    stringResource(R.string.add_first_farm),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    items(farms, key = { it.id }) { farm ->
                        FarmCard(farm = farm, onClick = { onOpenFarm(farm.id) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FarmCard(farm: FarmEntity, onClick: () -> Unit) {
    PaperCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = farm.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                val parts = mutableListOf<String>()
                farm.cropType?.let { parts.add(cropLabel(it)) }
                farm.acres?.let { parts.add(stringResource(R.string.acres_fmt, it)) }
                farm.waterSource?.let { parts.add(waterLabel(it)) }
                Text(
                    text = parts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Felt-state badge (Build-3 §8): a completed local write is not a
            // pending spinner — "on phone" until the sync lands, then teal ✓.
            SyncBadge(pending = farm.pendingSync)
        }
    }
}

@Composable
private fun cropLabel(type: String): String = when (type) {
    "beans" -> stringResource(R.string.crop_beans)
    "maize" -> stringResource(R.string.crop_maize)
    "soya" -> stringResource(R.string.crop_soya)
    else -> type.replaceFirstChar { it.uppercase() }
}

@Composable
private fun waterLabel(source: String): String = when (source) {
    "dam" -> stringResource(R.string.water_dam)
    "rain" -> stringResource(R.string.water_rain)
    "borehole" -> stringResource(R.string.water_borehole)
    else -> source.replaceFirstChar { it.uppercase() }
}
