package co.ke.kumea.data.sync

import co.ke.kumea.data.local.ConversionSource
import co.ke.kumea.data.local.HarvestConversions
import co.ke.kumea.data.local.HarvestUnits
import co.ke.kumea.data.remote.dto.HarvestCreateRequest
import co.ke.kumea.util.YieldConversion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The harvest wire contract, and the canonical-kilogram arithmetic.
 *
 * ── WHY THIS FILE EXISTS ─────────────────────────────────────────────────────
 *
 * Three times now this codebase has shipped a client field the server does not
 * accept, and each time the shape was identical: `forbidNonWhitelisted` turns
 * the unknown key into a 400, `pushPending()` treats 400 as retryable, and the
 * row sits at the head of the offline queue being re-sent for ever.
 *
 *   ① `cropType` / `acres` / `useGps` on FarmCreateRequest  (fixed KWAP-01 §4)
 *   ② `CostCategory.BIOFIX`, an enum value the server never had (fixed v12)
 *   ③ `kept` / `sold`, where the server whitelists `keptQuantity` /
 *      `soldQuantity` — found and fixed in KWAP-03
 *
 * ③ was live in shipped code. It hid the same way ① did: kotlinx.serialization
 * omits a property still holding its default, so a harvest recorded without a
 * kept/sold split serialised without those keys and synced perfectly. Only a
 * farmer who filled in the split — which the wizard's SPLIT step invites — would
 * poison their own queue, and nothing on their screen would say so.
 *
 * `FarmerRegistrationTest` pins the farm request's key set for the same reason.
 * A comment cannot catch ④.
 */
class HarvestWireContractTest {

    private val json = Json { encodeDefaults = false }

    /** Every key the server's CreateHarvestDto whitelists, and nothing else. */
    private val serverWhitelist = setOf(
        "id", "fieldId", "quantity", "unit",
        "keptQuantity", "soldQuantity",
        "replantIntent", "replantMonth", "harvestDate",
    )

    @Test
    fun `the create body carries no key the server would reject`() {
        val request = HarvestCreateRequest(
            id = "11111111-1111-4111-8111-111111111111",
            fieldId = "22222222-2222-4222-8222-222222222222",
            harvestDate = "2026-12-01T09:00:00Z",
            quantity = "5",
            unit = HarvestUnits.BAGS,
            keptQuantity = "2",
            soldQuantity = "3",
            replantIntent = "yes",
            replantMonth = "2027-03",
        )

        val keys = json.encodeToJsonElement(HarvestCreateRequest.serializer(), request)
            .jsonObject.keys

        val unknown = keys - serverWhitelist
        assertTrue(
            "these keys are a 400, and a 400 is retried for ever: $unknown",
            unknown.isEmpty(),
        )
    }

    @Test
    fun `the split is sent under the names the server actually whitelists`() {
        val request = HarvestCreateRequest(
            id = "11111111-1111-4111-8111-111111111111",
            fieldId = "22222222-2222-4222-8222-222222222222",
            harvestDate = "2026-12-01T09:00:00Z",
            quantity = "5",
            unit = HarvestUnits.BAGS,
            keptQuantity = "2",
            soldQuantity = "3",
            replantIntent = "no",
        )

        val keys = json.encodeToJsonElement(HarvestCreateRequest.serializer(), request)
            .jsonObject.keys

        // The exact regression: `kept` and `sold` are NOT the server's names.
        assertTrue("keptQuantity, not kept", "keptQuantity" in keys)
        assertTrue("soldQuantity, not sold", "soldQuantity" in keys)
        assertTrue("kept" !in keys)
        assertTrue("sold" !in keys)
    }

    @Test
    fun `an unsplit harvest omits the split keys entirely`() {
        val request = HarvestCreateRequest(
            id = "11111111-1111-4111-8111-111111111111",
            fieldId = "22222222-2222-4222-8222-222222222222",
            harvestDate = "2026-12-01T09:00:00Z",
            quantity = "5",
            unit = HarvestUnits.KG,
            replantIntent = "unknown",
        )

        val keys = json.encodeToJsonElement(HarvestCreateRequest.serializer(), request)
            .jsonObject.keys

        // This is exactly why the bug stayed invisible: with no split, the
        // request is clean and syncs. Pinned so the asymmetry is on the record.
        assertTrue("keptQuantity" !in keys)
        assertTrue("soldQuantity" !in keys)
    }

    // ── canonical kilograms (§4.4) ─────────────────────────────────────────

    @Test
    fun `five bags at ninety kilos is four hundred and fifty kilos`() {
        // §12's worked example, verbatim.
        val qtyKgCenti = YieldConversion.toKgCenti(
            quantityCenti = 500,
            factorCenti = HarvestConversions.BAG_90KG_CENTI,
        )
        assertEquals(45_000L, qtyKgCenti)
    }

    @Test
    fun `the same five bags at fifty kilos is a different harvest entirely`() {
        // 250 kg vs 450 kg from identical farmer input. This 80% gap is the
        // whole argument for asking the bag size at entry instead of defaulting.
        assertEquals(
            25_000L,
            YieldConversion.toKgCenti(500, HarvestConversions.BAG_50KG_CENTI),
        )
    }

    @Test
    fun `bags have no default factor, so a script cannot invent one later`() {
        assertEquals(null, YieldConversion.defaultFactorCenti(HarvestUnits.BAGS))
        assertEquals(HarvestConversions.KG_CENTI, YieldConversion.defaultFactorCenti(HarvestUnits.KG))
        assertEquals(HarvestConversions.GOROGORO_CENTI, YieldConversion.defaultFactorCenti(HarvestUnits.GOROGORO))
    }

    @Test
    fun `fractional gorogoro converts without float drift`() {
        // 2.5 tins × 2 kg = 5 kg exactly. Integer math the whole way, because
        // these get summed across ~395 farms into one headline number.
        assertEquals(500L, YieldConversion.toKgCenti(250, HarvestConversions.GOROGORO_CENTI))
    }

    @Test
    fun `the conversion source distinguishes a stated size from an assumed one`() {
        // The distinction is the point of storing the factor at all: a default
        // can be corrected in bulk later, a stated figure must never be.
        assertEquals("user_stated", ConversionSource.USER_STATED)
        assertEquals("default_table", ConversionSource.DEFAULT_TABLE)
        assertEquals("unknown", ConversionSource.UNKNOWN)
    }
}
