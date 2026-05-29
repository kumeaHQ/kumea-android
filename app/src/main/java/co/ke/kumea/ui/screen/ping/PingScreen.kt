package co.ke.kumea.ui.screen.ping

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun PingScreen(
    viewModel: PingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(modifier = Modifier.fillMaxSize().padding(16.dp)) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Button(onClick = { viewModel.ping() }) {
                Text("Ping API")
            }
            // Temporarily removed demo button to get a clean build

            Spacer(modifier = Modifier.height(32.dp))

            // ... (keep the rest of your state display code here)
        }
    }
}