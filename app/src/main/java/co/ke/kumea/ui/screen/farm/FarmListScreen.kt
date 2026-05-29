package co.ke.kumea.ui.screen.farm

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.ke.kumea.data.local.FarmEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FarmListScreen(
    onAddFarm: () -> Unit,
    viewModel: FarmListViewModel = hiltViewModel()
) {
    val farms by viewModel.farms.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Farms") }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddFarm) {
                Icon(Icons.Default.Add, contentDescription = "Add Farm")
            }
        }
    ) { padding ->
        if (farms.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("No farms found. Tap + to add one.")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(farms, key = { it.id }) { farm ->
                    FarmItem(farm = farm)
                }
            }
        }
    }
}

@Composable
fun FarmItem(farm: FarmEntity) {
    Card(
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
