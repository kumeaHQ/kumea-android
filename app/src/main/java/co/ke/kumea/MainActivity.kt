package co.ke.kumea

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.ke.kumea.ui.navigation.KumeaNavHost
import co.ke.kumea.ui.navigation.StartupState
import co.ke.kumea.ui.navigation.StartupViewModel
import co.ke.kumea.ui.theme.KumeaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KumeaTheme {
                RequestNotificationPermissionOnce()
                val startupViewModel: StartupViewModel = hiltViewModel()
                val state by startupViewModel.state.collectAsStateWithLifecycle()
                when (val s = state) {
                    StartupState.Loading -> SplashLoading()
                    is StartupState.Ready -> KumeaNavHost(startDestination = s.startDestination)
                }
            }
        }
    }
}

/**
 * Ask for POST_NOTIFICATIONS once on Android 13+ so background-sync notifications
 * (Ticket 2.3) can show. Fire-and-forget: the result is irrelevant here — if the
 * user denies, SyncNotifier silently no-ops and sync still runs. Sync is never
 * blocked on this permission.
 */
@Composable
private fun RequestNotificationPermissionOnce() {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* granted or not, sync proceeds either way */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun SplashLoading() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}
