package co.ke.kumea.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import co.ke.kumea.BuildConfig
import co.ke.kumea.ui.screen.auth.OtpEntryScreen
import co.ke.kumea.ui.screen.auth.PhoneEntryScreen
import co.ke.kumea.ui.screen.auth.PinEntryScreen
import co.ke.kumea.ui.screen.auth.PinSetupScreen
import co.ke.kumea.ui.screen.agent.VillageAgentHomeScreen
import co.ke.kumea.ui.screen.distribution.DistributionDemoScreen
import co.ke.kumea.ui.screen.farm.FarmCreateScreen
import co.ke.kumea.ui.screen.farm.FarmHomeScreen
import co.ke.kumea.ui.screen.farm.FarmListScreen
import co.ke.kumea.ui.screen.field.HarvestWizardScreen
import co.ke.kumea.ui.screen.field.PlantingDateScreen
import co.ke.kumea.ui.screen.home.LandingScreen
import co.ke.kumea.ui.screen.ledger.LedgerScreen
import co.ke.kumea.ui.screen.note.NoteCreateScreen
import co.ke.kumea.ui.screen.officer.OfficerHomeScreen
import co.ke.kumea.ui.screen.order.OrderCreateScreen

/** Nav-result key: a capture flow saved something; FarmHome plays the beat. */
private const val SAVE_BEAT_KEY = "save_beat"

object Routes {
    const val PHONE_ENTRY = "phone_entry"
    const val OTP_ENTRY = "otp_entry/{phone}"
    const val PIN_SETUP = "pin_setup/{registrationToken}"
    const val PIN_ENTRY = "pin_entry/{phone}"
    const val LANDING = "landing"
    const val FARM_LIST = "farms"
    const val AGENT_HOME = "agent/home"
    const val OFFICER_HOME = "officer/home"
    const val FARM_CREATE = "farms/create"
    const val FARM_HOME = "farms/{farmId}"
    const val DISTRIBUTION_DEMO = "distribution/demo"
    const val NOTE_CREATE = "farms/{farmId}/notes/create"
    const val LEDGER = "farms/{farmId}/ledger"
    const val ORDER_CREATE = "orders/create?farmId={farmId}"
    const val PLANTING_DATE = "fields/{fieldId}/planting-date"
    const val HARVEST = "fields/{fieldId}/harvest"

    fun otpEntry(phone: String) = "otp_entry/${Uri.encode(phone)}"
    fun pinSetup(registrationToken: String) = "pin_setup/${Uri.encode(registrationToken)}"
    fun pinEntry(phone: String) = "pin_entry/${Uri.encode(phone)}"
    fun farmHome(farmId: String) = "farms/${Uri.encode(farmId)}"
    fun noteCreate(farmId: String) = "farms/${Uri.encode(farmId)}/notes/create"
    fun ledger(farmId: String) = "farms/${Uri.encode(farmId)}/ledger"
    fun orderCreate(farmId: String?) =
        if (farmId != null) "orders/create?farmId=${Uri.encode(farmId)}" else "orders/create"
    fun plantingDate(fieldId: String) = "fields/${Uri.encode(fieldId)}/planting-date"
    fun harvest(fieldId: String) = "fields/${Uri.encode(fieldId)}/harvest"
}

@Composable
fun KumeaNavHost(
    startDestination: String = Routes.PHONE_ENTRY,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = startDestination) {
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
                    navController.navigate(Routes.LANDING) {
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
                    navController.navigate(Routes.LANDING) {
                        popUpTo(Routes.PHONE_ENTRY) { inclusive = true }
                    }
                },
                onUseOtp = { navController.popBackStack() },
            )
        }
        composable(Routes.LANDING) {
            fun goHome(route: String) = navController.navigate(route) {
                popUpTo(Routes.LANDING) { inclusive = true }
            }
            LandingScreen(
                onFarmer = { goHome(Routes.FARM_LIST) },
                onVillageAgent = { goHome(Routes.AGENT_HOME) },
                onOfficer = { goHome(Routes.OFFICER_HOME) },
            )
        }
        composable(Routes.FARM_LIST) {
            FarmListScreen(
                onAddFarm = { navController.navigate(Routes.FARM_CREATE) },
                onOpenFarm = { farmId -> navController.navigate(Routes.farmHome(farmId)) },
                onLoggedOut = {
                    navController.navigate(Routes.PHONE_ENTRY) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
        composable(
            Routes.FARM_HOME,
            arguments = listOf(navArgument("farmId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val farmId = backStackEntry.arguments?.getString("farmId") ?: return@composable
            // Save-beat felt state (Build-3 §8): capture flows set this result
            // before popping back so FarmHome can play the 3s confirmation chip.
            val showSaveBeat by backStackEntry.savedStateHandle
                .getStateFlow(SAVE_BEAT_KEY, false)
                .collectAsState()
            FarmHomeScreen(
                farmId = farmId,
                onBack = { navController.popBackStack() },
                onAddNote = { navController.navigate(Routes.noteCreate(farmId)) },
                onOpenLedger = { navController.navigate(Routes.ledger(farmId)) },
                onAddPlantingDate = { fieldId -> navController.navigate(Routes.plantingDate(fieldId)) },
                onRecordHarvest = { fieldId -> navController.navigate(Routes.harvest(fieldId)) },
                showSaveBeat = showSaveBeat,
                onSaveBeatConsumed = { backStackEntry.savedStateHandle[SAVE_BEAT_KEY] = false },
            )
        }
        composable(
            Routes.PLANTING_DATE,
            arguments = listOf(navArgument("fieldId") { type = NavType.StringType }),
        ) {
            PlantingDateScreen(
                onDone = {
                    navController.previousBackStackEntry?.savedStateHandle?.set(SAVE_BEAT_KEY, true)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.HARVEST,
            arguments = listOf(navArgument("fieldId") { type = NavType.StringType }),
        ) {
            HarvestWizardScreen(
                onDone = {
                    navController.previousBackStackEntry?.savedStateHandle?.set(SAVE_BEAT_KEY, true)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.AGENT_HOME) {
            VillageAgentHomeScreen(
                onRecordSale = { navController.navigate(Routes.orderCreate(null)) },
                // INTERIM: reuse the proven FarmCreate screen for farmer registration
                // until AS-1 lands (farmer-as-farm is the Phase-1 model).
                onRegisterFarmer = { navController.navigate(Routes.FARM_CREATE) },
                onOpenDistributionDemo = { navController.navigate(Routes.DISTRIBUTION_DEMO) },
                onLoggedOut = {
                    navController.navigate(Routes.PHONE_ENTRY) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.OFFICER_HOME) {
            OfficerHomeScreen(
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
        // Developer harness — registered in debug builds only; a release build
        // has no such destination (handoff §2: not reachable by real users).
        if (BuildConfig.DEBUG) {
            composable(Routes.DISTRIBUTION_DEMO) {
                DistributionDemoScreen(onBack = { navController.popBackStack() })
            }
        }
        composable(
            Routes.ORDER_CREATE,
            arguments = listOf(navArgument("farmId") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) {
            OrderCreateScreen(onBack = { navController.popBackStack() })
        }
        composable(
            Routes.NOTE_CREATE,
            arguments = listOf(navArgument("farmId") { type = NavType.StringType }),
        ) {
            NoteCreateScreen(
                onBack = { navController.popBackStack() },
                onSaved = {
                    navController.previousBackStackEntry?.savedStateHandle?.set(SAVE_BEAT_KEY, true)
                    navController.popBackStack()
                },
            )
        }
        composable(
            Routes.LEDGER,
            arguments = listOf(navArgument("farmId") { type = NavType.StringType }),
        ) {
            LedgerScreen(onBack = { navController.popBackStack() })
        }
    }
}
