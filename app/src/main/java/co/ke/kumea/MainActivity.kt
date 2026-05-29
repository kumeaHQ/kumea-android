package co.ke.kumea

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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

@Composable
private fun SplashLoading() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}
