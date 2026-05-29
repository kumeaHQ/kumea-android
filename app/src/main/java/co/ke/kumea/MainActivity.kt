package co.ke.kumea

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import co.ke.kumea.ui.navigation.KumeaNavHost
import co.ke.kumea.ui.theme.KumeaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KumeaTheme {
                KumeaNavHost()
            }
        }
    }
}
