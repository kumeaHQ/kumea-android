package co.ke.kumea.ui.screen.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtpEntryScreen(
    onPinSetup: (String) -> Unit,
    onPinEntry: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: OtpEntryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var code by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state.nav) {
        when (val nav = state.nav) {
            is OtpNav.ToPinSetup -> {
                onPinSetup(nav.registrationToken)
                viewModel.onNavigated()
            }
            is OtpNav.ToPinEntry -> {
                onPinEntry(nav.phone)
                viewModel.onNavigated()
            }
            null -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Verify") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Enter the 6-digit code we just sent you.",
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedTextField(
                value = code,
                onValueChange = { new ->
                    val digits = new.filter { it.isDigit() }.take(6)
                    code = digits
                    // Auto-submit once all 6 digits are entered.
                    if (digits.length == 6) viewModel.onVerify(digits)
                },
                label = { Text("Verification code") },
                singleLine = true,
                isError = state.error != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
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
                onClick = { viewModel.onVerify(code) },
                enabled = !state.isLoading && code.length == 6,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Verify")
                }
            }
            TextButton(
                onClick = { viewModel.onResend() },
                enabled = state.resendSecondsRemaining == 0 && !state.isLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (state.resendSecondsRemaining > 0) {
                        "Resend code in ${state.resendSecondsRemaining}s"
                    } else {
                        "Resend code"
                    }
                )
            }
        }
    }
}
