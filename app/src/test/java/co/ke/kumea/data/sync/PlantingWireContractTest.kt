package co.ke.kumea.data.sync

import co.ke.kumea.data.local.TrialRole
import co.ke.kumea.data.remote.dto.PlantingCreateRequest
import co.ke.kumea.data.remote.dto.PlantingUpdateRequest
import co.ke.kumea.util.Area
import co.ke.kumea.util.Quantity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The planting wire contract — pinned the day the binding was armed.
 *
 * ── WHY THIS FILE EXISTS ─────────────────────────────────────────────────────
 *
 * `PlantingRepository` was written on 14 Aug and deliberately NOT bound into
 * `Set<SyncableRepository>` for four days, because `/plantings` did not exist.
 * It is bound now (`kumea-api` `7cb03d2`, deployed 18 Aug), and the thing that
 * made that safe was diffing `PlantingCreateRequest` against the real
 * `CreatePlantingDto` by hand — every key AND every numeric type.
 *
 * A hand diff is a one-off. This file is what stops the fifth recurrence of the
 * bug that has already shipped four times: `cropType`/`acres`/`useGps`,
 * `CostCategory.BIOFIX`, `kept`/`sold`, and `notes.sourceType`/`sourceId`. Each
 * was a key the server would not accept; each became a 400; and until 14 Aug a
 * 400 was retried for ever.
 *
 * ── THE SCALE IS PART OF THE CONTRACT, NOT A FORMATTING DETAIL ───────────────
 *
 * The other half is the round trip, and it is the half with a live casualty.
 * `Quantity.parseToCenti` accepts AT MOST TWO decimal places and
 * `PlantingRepository.pullSince` drops a row it cannot parse. `plantings` is
 * therefore `Decimal(10,2)` serialised `.toFixed(2)` server-side — unlike
 * `fields.acres` and `harvests.quantity`, which are 4 dp.
 *
 * `harvests` shows what happens otherwise: the server sends `"5.0000"`,
 * `HarvestRepository.pullSince` returns null and drops the row, and every
 * harvest the server holds is silently discarded on pull. The last two tests
 * here are that failure, pinned in the shape that would catch it.
 */
class PlantingWireContractTest {

    private val json = Json { encodeDefaults = false }

    /**
     * Every key the deployed `CreatePlantingDto` whitelists, and nothing else.
     * Read off `kumea-api/src/plantings/dto/create-planting.dto.ts` at `7cb03d2`.
     */
    private val createWhitelist = setOf(
        "id", "farmId", "plantedOn", "crop", "seedVariety",
        "seedKg", "plantedArea", "seedCostCents", "trialRole",
    )

    /** `UpdatePlantingDto` — the same minus the ids, plus the conflict token. */
    private val updateWhitelist = setOf(
        "plantedOn", "crop", "seedVariety",
        "seedKg", "plantedArea", "seedCostCents", "trialRole", "updatedAt",
    )

    private fun createRequest(
        seedKg: String = "12.5",
        plantedArea: String = "1.6",
        seedCostCents: String? = "250000",
        seedVariety: String? = "SB19",
    ) = PlantingCreateRequest(
        id = "11111111-1111-4111-8111-111111111111",
        farmId = "22222222-2222-4222-8222-222222222222",
        plantedOn = "2026-08-14",
        crop = "soybean",
        seedVariety = seedVariety,
        seedKg = seedKg,
        plantedArea = plantedArea,
        seedCostCents = seedCostCents,
        trialRole = TrialRole.TREATED,
    )

    private fun keysOf(request: PlantingCreateRequest) =
        json.encodeToJsonElement(PlantingCreateRequest.serializer(), request).jsonObject.keys

    // ── the key set ───────────────────────────────────────────────────────

    @Test
    fun `the create body carries no key the server would reject`() {
        val unknown = keysOf(createRequest()) - createWhitelist
        assertTrue(
            "these keys are a 400, and a 400 is terminal — the row is rejected, " +
                "recorded and surfaced rather than sent: $unknown",
            unknown.isEmpty(),
        )
    }

    @Test
    fun `the update body carries no key the server would reject`() {
        val keys = json.encodeToJsonElement(
            PlantingUpdateRequest.serializer(),
            PlantingUpdateRequest(
                plantedOn = "2026-08-14",
                crop = "soybean",
                seedVariety = "SB19",
                seedKg = "12.5",
                plantedArea = "1.6",
                seedCostCents = "250000",
                trialRole = TrialRole.CONTROL,
                updatedAt = "2026-08-14T10:00:00Z",
            ),
        ).jsonObject.keys

        assertTrue("unknown update keys: ${keys - updateWhitelist}", (keys - updateWhitelist).isEmpty())
    }

    @Test
    fun `a body with every optional omitted still carries the required keys`() {
        // The trap in ① and ③: kotlinx.serialization omits a property still
        // holding its default, so the minimal body syncs and the full one does
        // not. Both shapes have to be checked, and both have to be complete.
        val minimal = keysOf(createRequest(seedCostCents = null, seedVariety = null))

        assertTrue((minimal - createWhitelist).isEmpty())
        for (required in listOf("id", "farmId", "plantedOn", "crop", "seedKg", "plantedArea", "trialRole")) {
            assertTrue("$required must always be sent", required in minimal)
        }
    }

    // ── the numeric types ─────────────────────────────────────────────────

    @Test
    fun `quantities are decimal strings, never JSON numbers`() {
        val body = json.encodeToJsonElement(PlantingCreateRequest.serializer(), createRequest()).jsonObject

        for (key in listOf("seedKg", "plantedArea", "seedCostCents")) {
            val value = body[key] as JsonPrimitive
            assertTrue("$key must be a JSON string — the server rejects a number", value.isString)
        }
    }

    @Test
    fun `money is an integer string of cents, and null is not zero`() {
        val withCost = json.encodeToJsonElement(
            PlantingCreateRequest.serializer(), createRequest(seedCostCents = "250000"),
        ).jsonObject
        assertEquals("250000", (withCost["seedCostCents"] as JsonPrimitive).content)

        // Null (never asked) and "0" (stated free) are different facts, and the
        // server keeps them apart. Omitting the key is how null travels.
        val skipped = json.encodeToJsonElement(
            PlantingCreateRequest.serializer(), createRequest(seedCostCents = null),
        ).jsonObject
        assertNull(skipped["seedCostCents"])

        val free = json.encodeToJsonElement(
            PlantingCreateRequest.serializer(), createRequest(seedCostCents = "0"),
        ).jsonObject
        assertEquals("0", (free["seedCostCents"] as JsonPrimitive).content)
    }

    @Test
    fun `plantedOn is a bare calendar date`() {
        // The server validates /^\d{4}-\d{2}-\d{2}$/ and stores TEXT. A datetime
        // is a 400 — deliberately, so the two representations cannot drift.
        val body = json.encodeToJsonElement(
            PlantingCreateRequest.serializer(), createRequest(),
        ).jsonObject

        assertTrue(
            "plantedOn must be YYYY-MM-DD, not a datetime",
            Regex("""^\d{4}-\d{2}-\d{2}$""").matches((body["plantedOn"] as JsonPrimitive).content),
        )
    }

    // ── the round trip, which is where harvests are broken today ──────────

    @Test
    fun `what the server sends back at two decimals parses to the same centi`() {
        // The server's serializePlanting does Prisma.Decimal.toFixed(2), so a
        // planted area of 160 centi goes out "1.6" and comes back "1.60".
        assertEquals("1.6", Area.formatCenti(160L))
        assertEquals(160L, Area.parseToCenti("1.60"))
        assertEquals(160L, Area.parseToCenti("1.6"))

        assertEquals("12.5", Quantity.formatCenti(1250L))
        assertEquals(1250L, Quantity.parseToCenti("12.50"))

        // Whole numbers and zero survive both spellings too — the backfill and a
        // skipped question both send 0.
        assertEquals(0L, Area.parseToCenti("0.00"))
        assertEquals(300L, Quantity.parseToCenti("3.00"))
    }

    @Test
    fun `four decimal places do not parse, which is why the column is two`() {
        // THIS IS NOT HYPOTHETICAL. HarvestsService serialises quantity with
        // .toFixed(4), so the server sends "5.0000"; this returns null; and
        // HarvestRepository.pullSince maps null to a dropped row. Every harvest
        // the server holds is discarded on pull today.
        //
        // plantings.seed_kg / planted_area are Decimal(10,2) precisely so this
        // cannot happen to them. If someone widens that column, this test is
        // what says why they must not.
        assertNull("a 4-dp quantity is unparseable — the row is DROPPED", Quantity.parseToCenti("5.0000"))
        assertNull(Area.parseToCenti("1.6000"))
        assertNull(Quantity.parseToCenti("12.505"))

        // And the two-place forms the planting contract actually uses do parse.
        assertNotNull(Quantity.parseToCenti("5.00"))
        assertNotNull(Area.parseToCenti("1.60"))
    }
}
