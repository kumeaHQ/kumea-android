package co.ke.kumea.ui.screen.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinSetupScreen(
    onAuthSuccess: () -> Unit,
    viewModel: PinSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pin by rememberSaveable { mutableStateOf("") }
    var confirmPin by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state.navigateToFarms) {
        if (state.navigateToFarms) {
            onAuthSuccess()
            viewModel.onNavigated()
        }
    }
    LaunchedEffect(state.clearFields) {
        if (state.clearFields) {
            pin = ""
            confirmPin = ""
            viewModel.onFieldsCleared()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Create your PIN") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Choose a 4-6 digit PIN. You'll use it to sign in.",
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text("Create PIN") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                isError = state.error != null,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = confirmPin,
                onValueChange = { confirmPin = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text("Confirm PIN") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                isError = state.error != null,
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = { viewModel.onConfirm(pin, confirmPin) },
                enabled = !state.isLoading && pin.isNotBlank() && confirmPin.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Create account")
                }
            }
        }
    }
}
