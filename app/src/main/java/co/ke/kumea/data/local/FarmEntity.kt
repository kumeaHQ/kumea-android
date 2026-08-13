package co.ke.kumea.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A farm is the farmer. Until v11 that identity was implicit — a farm belonged
 * to whoever held the JWT — which is why an officer tapping "add farmer" would
 * have attached the farm to their own account (KWAP-01 §2).
 *
 * THREE AGENT/USER REFERENCES, THREE DIFFERENT MEANINGS. Do not merge them:
 *
 *  - [farmerUserId]         who the farm is FOR.       The owner, as the server sees it.
 *  - [registeredByAgentId]  who TYPED IT IN.           Provenance. No commercial meaning.
 *  - [referrerAgentId]      who GETS PAID.             Commission attribution.
 *
 * The commission engine has been live since 26 June and accrues effective 1
 * June. Setting [referrerAgentId] where [registeredByAgentId] was meant makes
 * ~395 KWAP research farmers accrue commission against agents who did nothing —
 * wrong in the ledger, not merely on screen. When an officer registers a
 * farmer, [referrerAgentId] stays null.
 *
 * TWO NAMES, TWO SUBJECTS (v12, KWAP-01 step 4):
 *
 *  - [name]        what the PLACE is called.   "Sigona". Build-1 locked farmer ≠ farm-name.
 *  - [farmerName]  what the PERSON is called.  "Sila Serem". The register's subject.
 *
 * The person sits on the farm rather than on a User because KWAP farmers have
 * no User — creating one was deferred in KWAP-STEP2-DECISIONS.md §2 (unverified
 * phones, the OTP-collision question, ~395 people who will never open the app).
 * With [farmerUserId] unused this season, the farm row IS the farmer record,
 * which is the model KWAP-01 §2 describes anyway. When a real Farmer/User split
 * arrives with commercial, these two become a backfill source.
 *
 * WARD IS STAMPED, NOT TYPED (v13, KWAP-03 §4.1). [ward] is copied from the
 * registering agent's own `AgentEntity.ward` at create time and from nowhere
 * else — there is no ward input anywhere in the app and there must never be
 * one. An input can be wrong, stale or spoofed; a derivation cannot. KWAP-01
 * deferred this column precisely because a *second typed copy* could disagree
 * with its source; a stamped copy cannot, and the research needs to group ~395
 * farms by ward without walking the agent roster on every query.
 *
 * COUNTY IS ABSENT ON PURPOSE. KWAP-03 §4.1 asked for one alongside [ward], but
 * nothing on either side of the wire holds a county: `AgentEntity` has `region`
 * + `ward`, and `region` is free text whose slug becomes the agent code's middle
 * token ("Nandi County" → `EO-NANDI-041`), which is county-shaped while
 * KUMEA-REGIONS-CANONICAL.md locks "region" to the seven operational regions.
 * Stamping that into a column called county would be deriving from a source
 * that does not mean what the column says. Dropped 13 Aug rather than guessed;
 * it wants a real county field on Agent first.
 *
 * LOCATION IS A FACT WITH AN AGE (v13, KWAP-03 §4.1). [locationLat] != null IS
 * the truth of "we have a location" — see [useGps] below for what happens when
 * a boolean is allowed to claim it instead. The four metadata columns exist
 * because a coordinate on its own cannot be judged: [locationAccuracyM]
 * separates a 3 m GPS fix from a 20 m network one, [locationCapturedAt] gives
 * the fix an age (RB's 13 Aug sweep found a 4-day-old fix presented as current),
 * [locationSource] says which provider produced it, and [locationConfirmedAt] is
 * set ONLY when a human explicitly tapped "I am standing at this shamba now".
 * An officer registering ten farmers in a day is not standing on ten shambas,
 * so captured and confirmed are genuinely different facts and the difference is
 * what a later "farms needing location confirmed" worklist reads.
 *
 * BASELINE IS THE COUNTERFACTUAL (v13, KWAP-03 §4.1, decision 1). Last season's
 * yield, recalled at registration, is the only comparison most of these farms
 * will ever have — the split-plot control ([FieldEntity.trialRole]) covers a
 * subset. It is recall-based and confounded by rainfall, and it still cannot be
 * retrofitted in December, which is why an optional, skippable question ships
 * with registration this month. [baselineYieldKgCenti] is the canonical figure;
 * qty + unit are kept as stated so a wrong conversion stays re-derivable.
 *
 * [cropType], [acres] and [useGps] are DEVICE-ONLY — the server's Farm carries
 * none of them (crop and acreage live on the Field; useGps is pure UI state).
 * They are display denorms rebuilt from local input, and a pull leaves them
 * untouched rather than nulling them. Do not add them to [FarmCreateRequest]:
 * `forbidNonWhitelisted` is on server-side, so an unknown key is a 400, and the
 * client retries 400 for ever.
 */
@Entity(tableName = "farms")
data class FarmEntity(
    @PrimaryKey val id: String,
    val name: String,
    val cropType: String? = null,
    val acres: Double? = null,
    val locationLat: Double?,
    val locationLng: Double?,
    /**
     * RETIRED (v13, KWAP-03 §4.1). This is the column that lied: the create
     * screen wrote `useGps = 1` on a tap and captured nothing, so the database
     * asserted a location for farms that had none (`useGps=1, lat=null`) — and
     * the root cause was that the location permissions were never declared in
     * the manifest, so the app could not have captured a fix even if it tried.
     *
     * Nothing writes it and nothing reads it any more. It is still here because
     * dropping a column in Room means recreating the table, which is a real risk
     * for no benefit; it goes in a later consolidation migration. NOT NULL with
     * a false default, so it stays harmless while it waits.
     */
    @Deprecated(
        "Location truth is locationLat != null. Never write this; drop in a later consolidation.",
        level = DeprecationLevel.WARNING,
    )
    val useGps: Boolean = false,
    /** Metres, as the provider reported it. Null when there is no fix at all. */
    val locationAccuracyM: Float? = null,
    /** [LocationSource] value — which provider produced the fix. */
    val locationSource: String? = null,
    /** UTC ISO-8601. When the fix was taken; a fix has an age. */
    val locationCapturedAt: String? = null,
    /** UTC ISO-8601. Set ONLY on an explicit "I am standing at this shamba now". */
    val locationConfirmedAt: String? = null,
    /** Stamped from the registering agent's `AgentEntity.ward`. Never typed. */
    val ward: String? = null,
    /** Last season's yield AS STATED — hundredths of [baselineYieldUnit]. */
    val baselineYieldCenti: Long? = null,
    /** `bags` | `kg` | `gorogoro` — the harvest wizard's vocabulary, verbatim. */
    val baselineYieldUnit: String? = null,
    /** Canonical kilograms × 100. The only figure the impact report reads. */
    val baselineYieldKgCenti: Long? = null,
    /** What was grown last season — a [co.ke.kumea.domain.model.Crops] key. */
    val baselineCrop: String? = null,
    val waterSource: String?,
    val referrerAgentId: String? = null,
    val farmerUserId: String? = null,
    val registeredByAgentId: String? = null,
    val farmerName: String? = null,
    val farmerPhone: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String?,
    val pendingSync: Boolean,
    val syncAction: SyncAction,
)

enum class SyncAction {
    CREATE,
    UPDATE,
    DELETE,
}

/**
 * Which provider produced a fix (KWAP-03 §4.1). Plain string constants rather
 * than an enum, for the same reason `HarvestUnits` is: Room stores an enum as
 * its name, so a value the server has not agreed to becomes a row that throws on
 * read (the `BIOFIX` lesson, v12). These are device-only today and go on the
 * wire with the KWAP-03 server patch.
 */
object LocationSource {
    /** A real satellite fix. The only one worth confirming a shamba with. */
    const val GPS = "gps"

    /** Cell/wifi. Cheap, fast, and typically tens to hundreds of metres out. */
    const val NETWORK = "network"

    /** Entered by a human rather than measured. Nothing writes this yet. */
    const val MANUAL = "manual"
}
