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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.ke.kumea.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinSetupScreen(
    onAuthSuccess: () -> Unit,
    viewModel: PinSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
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
        topBar = { TopAppBar(title = { Text(stringResource(R.string.create_account_title)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Build-1: the person before the credential — who are you?
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.your_name)) },
                placeholder = { Text(stringResource(R.string.your_name_hint)) },
                singleLine = true,
                isError = state.error != null && name.isBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.pin_instructions),
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text(stringResource(R.string.create_pin)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                isError = state.error != null,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = confirmPin,
                onValueChange = { confirmPin = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text(stringResource(R.string.confirm_pin)) },
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
                onClick = { viewModel.onConfirm(name, pin, confirmPin) },
                enabled = !state.isLoading && name.isNotBlank() && pin.isNotBlank() && confirmPin.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.create_account))
                }
            }
        }
    }
}
