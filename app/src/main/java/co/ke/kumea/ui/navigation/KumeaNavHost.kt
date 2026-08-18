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
import co.ke.kumea.ui.screen.auth.OtpEntryScreen
import co.ke.kumea.ui.screen.auth.PhoneEntryScreen
import co.ke.kumea.ui.screen.auth.PinEntryScreen
import co.ke.kumea.ui.screen.auth.PinSetupScreen
import co.ke.kumea.ui.screen.agent.VillageAgentHomeScreen
import co.ke.kumea.ui.screen.farm.FarmCreateScreen
import co.ke.kumea.ui.screen.farm.FarmHomeScreen
import co.ke.kumea.ui.screen.farm.FarmListScreen
import co.ke.kumea.ui.screen.field.HarvestWizardScreen
import co.ke.kumea.ui.screen.field.PlantingScreen
import co.ke.kumea.ui.screen.home.LandingScreen
import co.ke.kumea.ui.screen.ledger.LedgerScreen
import co.ke.kumea.ui.screen.note.MODE_OBSERVATION
import co.ke.kumea.ui.screen.note.NoteCreateScreen
import co.ke.kumea.ui.screen.officer.FarmerDirectoryScreen
import co.ke.kumea.ui.screen.officer.OfficerHomeScreen
import co.ke.kumea.ui.screen.officer.RecordKumeaNScreen
import co.ke.kumea.ui.screen.officer.RegisterFarmerScreen
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
    /** KWAP-01 step 4 — the officer's register. Not a farm list; see FarmerDirectoryScreen. */
    const val FARMER_DIRECTORY = "officer/farmers"
    const val FARMER_REGISTER = "officer/farmers/register"
    /**
     * KWAP-03 §7 — recording a Kumea N handover. Officer/agent surface only:
     * it is reached from the register, which only an officer or agent can open.
     * Nothing on the farmer's own farm page navigates here — a farmer does not
     * record what they were given.
     */
    const val RECORD_KUMEA_N = "farms/{farmId}/kumea-n"
    /**
     * `mode` selects the form: absent/anything = the two money ledgers,
     * "observation" = the no-money activity form (KWAP-03-V2 §2.6/§2.7).
     */
    const val NOTE_CREATE = "farms/{farmId}/notes/create?mode={mode}"
    const val LEDGER = "farms/{farmId}/ledger"
    const val ORDER_CREATE = "orders/create?farmId={farmId}"
    /**
     * FARM-level from KWAP-03-V2 §2.3 — planting is an entity on Farm now, and
     * §2.2 removes Field from what the farmer sees. Was
     * `fields/{fieldId}/planting-date`.
     */
    const val PLANTING = "farms/{farmId}/planting"
    /**
     * `harvestId` optional: absent records a new harvest, present re-opens that
     * record for correction (KWAP-03-V2 §2.1). One route and one wizard, because
     * an edit screen would be the same five questions maintained twice.
     */
    const val HARVEST = "fields/{fieldId}/harvest?harvestId={harvestId}"

    fun otpEntry(phone: String) = "otp_entry/${Uri.encode(phone)}"
    fun pinSetup(registrationToken: String) = "pin_setup/${Uri.encode(registrationToken)}"
    fun pinEntry(phone: String) = "pin_entry/${Uri.encode(phone)}"
    fun farmHome(farmId: String) = "farms/${Uri.encode(farmId)}"
    fun noteCreate(farmId: String, observation: Boolean = false) =
        "farms/${Uri.encode(farmId)}/notes/create" +
            if (observation) "?mode=$MODE_OBSERVATION" else ""
    fun recordKumeaN(farmId: String) = "farms/${Uri.encode(farmId)}/kumea-n"
    fun ledger(farmId: String) = "farms/${Uri.encode(farmId)}/ledger"
    fun orderCreate(farmId: String?) =
        if (farmId != null) "orders/create?farmId=${Uri.encode(farmId)}" else "orders/create"
    fun planting(farmId: String) = "farms/${Uri.encode(farmId)}/planting"
    fun harvest(fieldId: String, harvestId: String? = null) =
        "fields/${Uri.encode(fieldId)}/harvest" +
            if (harvestId != null) "?harvestId=${Uri.encode(harvestId)}" else ""
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
                onAddObservation = {
                    navController.navigate(Routes.noteCreate(farmId, observation = true))
                },
                // onOpenLedger is gone: the money line-card that carried it off
                // the farmer's farm page is deleted (KWAP-03 §5.4).
                //
                // ⚠️ THAT WAS THE LEDGER'S ONLY ENTRY POINT. Routes.LEDGER and
                // LedgerScreen are deliberately left registered and intact — the
                // ticket says the commercial surface stays because it belongs to
                // the agent persona — but nothing navigates to it right now.
                // Giving the agent surface its own link is the agent-home work,
                // not this ticket's; flagged rather than quietly deleted.
                onAddPlanting = { navController.navigate(Routes.planting(farmId)) },
                onRecordHarvest = { fieldId, harvestId ->
                    navController.navigate(Routes.harvest(fieldId, harvestId))
                },
                showSaveBeat = showSaveBeat,
                onSaveBeatConsumed = { backStackEntry.savedStateHandle[SAVE_BEAT_KEY] = false },
            )
        }
        composable(
            Routes.PLANTING,
            arguments = listOf(navArgument("farmId") { type = NavType.StringType }),
        ) {
            PlantingScreen(
                onDone = {
                    navController.previousBackStackEntry?.savedStateHandle?.set(SAVE_BEAT_KEY, true)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.HARVEST,
            arguments = listOf(
                navArgument("fieldId") { type = NavType.StringType },
                navArgument("harvestId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
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
                // KWAP-03 follow-up: the ledger's entry point, restored on the
                // agent surface after §5.4 removed the farmer page's money card.
                // Per-farm, because the ledger is — a sale knows which farm it
                // was attributed to. The farmer surface stays money-free.
                onOpenLedger = { farmId -> navController.navigate(Routes.ledger(farmId)) },
                onRecordSale = { navController.navigate(Routes.orderCreate(null)) },
                // FIXED 18 Aug (KWAP-06 §3.4). This pointed at FARM_CREATE, the
                // SELF-registration screen, so a farmer an agent added became a
                // farm with no local ward and no local registeredByAgentId — it
                // never appeared in the register it was supposed to join, and
                // ward-grouping the ~395 research farms silently missed it.
                //
                // RegisterFarmerScreen is written for "the officer's and village
                // agent's" path, derives ward + provenance from the caller's own
                // agent record, and the server has permitted a village_agent
                // since KWAP-01 step 2. The deferral was waiting on the agent's
                // own roster (step 5) — but a roster is a VIEW, and withholding
                // the correct write until the view exists just means more rows to
                // repair later.
                onRegisterFarmer = { navController.navigate(Routes.FARMER_REGISTER) },
                onLoggedOut = {
                    navController.navigate(Routes.PHONE_ENTRY) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.OFFICER_HOME) {
            OfficerHomeScreen(
                onRegisterFarmer = { navController.navigate(Routes.FARMER_REGISTER) },
                onOpenFarmerDirectory = { navController.navigate(Routes.FARMER_DIRECTORY) },
                onLoggedOut = {
                    navController.navigate(Routes.PHONE_ENTRY) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.FARMER_DIRECTORY) {
            FarmerDirectoryScreen(
                onBack = { navController.popBackStack() },
                onRegisterFarmer = { navController.navigate(Routes.FARMER_REGISTER) },
                onRecordKumeaN = { farmId -> navController.navigate(Routes.recordKumeaN(farmId)) },
            )
        }
        composable(Routes.FARMER_REGISTER) {
            RegisterFarmerScreen(
                onBack = { navController.popBackStack() },
                // Pop straight back to whichever surface launched it. The
                // directory is a Room Flow, so the new row is already there.
                onSaved = { navController.popBackStack() },
            )
        }
        composable(
            Routes.RECORD_KUMEA_N,
            arguments = listOf(navArgument("farmId") { type = NavType.StringType }),
        ) {
            RecordKumeaNScreen(
                onBack = { navController.popBackStack() },
                // The directory is a Room Flow, so the handover shows on the
                // farmer's Zone 1 the moment we pop back.
                onSaved = { navController.popBackStack() },
            )
        }
        composable(Routes.FARM_CREATE) {
            FarmCreateScreen(onBack = { navController.popBackStack() })
        }
        composable(
            Routes.ORDER_CREATE,
            arguments = listOf(navArgument("farmId") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) {
            OrderCreateScreen(onBack = { navController.popBackStack() })
        }
        composable(
            Routes.NOTE_CREATE,
            arguments = listOf(
                navArgument("farmId") { type = NavType.StringType },
                navArgument("mode") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
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
