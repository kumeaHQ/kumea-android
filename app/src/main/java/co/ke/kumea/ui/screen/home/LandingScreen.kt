package co.ke.kumea.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.ke.kumea.domain.model.Persona

/**
 * The persona gate (P1-T7). Shows a spinner while resolving, then routes ONCE to
 * the farmer / village_agent / officer home. On a hard failure it offers retry —
 * it never silently falls through to a default persona.
 *
 * Routing is the only thing that happens here; each home is a distinct route, so
 * the officer never even constructs the agent earnings view (it is not in the
 * officer route at all — structurally unreachable, not hidden by a flag).
 */
@Composable
fun LandingScreen(
    onFarmer: () -> Unit,
    onVillageAgent: () -> Unit,
    onOfficer: () -> Unit,
    viewModel: LandingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        val ready = state as? LandingState.Ready ?: return@LaunchedEffect
        when (ready.persona) {
            Persona.FARMER -> onFarmer()
            Persona.VILLAGE_AGENT -> onVillageAgent()
            Persona.EXTENSION_OFFICER -> onOfficer()
        }
    }

    when (val s = state) {
        is LandingState.Error -> ErrorRetry(message = s.message, onRetry = viewModel::resolve)
        // Loading and the brief moment after Ready (before navigation) show the spinner.
        else -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ErrorRetry(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onRetry,
            modifier = Modifier.padding(top = 16.dp),
        ) { Text("Retry") }
    }
}
