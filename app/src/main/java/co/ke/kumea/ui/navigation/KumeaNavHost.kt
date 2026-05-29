package co.ke.kumea.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import co.ke.kumea.ui.screen.farm.FarmCreateScreen
import co.ke.kumea.ui.screen.farm.FarmListScreen
import co.ke.kumea.ui.screen.ping.PingScreen

object Routes {
    const val PING = "ping"
    const val FARM_LIST = "farms"
    const val FARM_CREATE = "farms/create"
}

@Composable
fun KumeaNavHost(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = Routes.FARM_LIST,
    ) {
        composable(Routes.PING) {
            PingScreen()
        }
        composable(Routes.FARM_LIST) {
            FarmListScreen(onAddFarm = { navController.navigate(Routes.FARM_CREATE) })
        }
        composable(Routes.FARM_CREATE) {
            FarmCreateScreen(onBack = { navController.popBackStack() })
        }
    }
}
