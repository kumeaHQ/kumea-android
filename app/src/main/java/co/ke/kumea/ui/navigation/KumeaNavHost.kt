package co.ke.kumea.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import co.ke.kumea.ui.screen.auth.OtpEntryScreen
import co.ke.kumea.ui.screen.auth.PhoneEntryScreen
import co.ke.kumea.ui.screen.auth.PinEntryScreen
import co.ke.kumea.ui.screen.auth.PinSetupScreen
import co.ke.kumea.ui.screen.farm.FarmCreateScreen
import co.ke.kumea.ui.screen.farm.FarmListScreen

object Routes {
    const val PHONE_ENTRY = "phone_entry"
    const val OTP_ENTRY = "otp_entry/{phone}"
    const val PIN_SETUP = "pin_setup/{registrationToken}"
    const val PIN_ENTRY = "pin_entry/{phone}"
    const val FARM_LIST = "farms"
    const val FARM_CREATE = "farms/create"

    fun otpEntry(phone: String) = "otp_entry/${Uri.encode(phone)}"
    fun pinSetup(registrationToken: String) = "pin_setup/${Uri.encode(registrationToken)}"
    fun pinEntry(phone: String) = "pin_entry/${Uri.encode(phone)}"
}

@Composable
fun KumeaNavHost(
    startDestination: String = Routes.PHONE_ENTRY,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(Routes.PHONE_ENTRY) {
            PhoneEntryScreen(
                onOtpSent = { phone -> navController.navigate(Routes.otpEntry(phone)) },
            )
        }
        composable(
            Routes.OTP_ENTRY,
            arguments = listOf(navArgument("phone") { type = NavType.StringType }),
        ) {
            OtpEntryScreen(
                onPinSetup = { token -> navController.navigate(Routes.pinSetup(token)) },
                onPinEntry = { phone -> navController.navigate(Routes.pinEntry(phone)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.PIN_SETUP,
            arguments = listOf(navArgument("registrationToken") { type = NavType.StringType }),
        ) {
            PinSetupScreen(
                onAuthSuccess = {
                    navController.navigate(Routes.FARM_LIST) {
                        // Clear the whole auth stack so back from FarmList exits the app.
                        popUpTo(Routes.PHONE_ENTRY) { inclusive = true }
                    }
                },
            )
        }
        composable(
            Routes.PIN_ENTRY,
            arguments = listOf(navArgument("phone") { type = NavType.StringType }),
        ) {
            PinEntryScreen(
                onAuthSuccess = {
                    navController.navigate(Routes.FARM_LIST) {
                        popUpTo(Routes.PHONE_ENTRY) { inclusive = true }
                    }
                },
                // Back to OtpEntry so the user can re-verify (auto-login if returning).
                onUseOtp = { navController.popBackStack() },
            )
        }
        composable(Routes.FARM_LIST) {
            FarmListScreen(
                onAddFarm = { navController.navigate(Routes.FARM_CREATE) },
                onLoggedOut = {
                    navController.navigate(Routes.PHONE_ENTRY) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.FARM_CREATE) {
            FarmCreateScreen(onBack = { navController.popBackStack() })
        }
    }
}
