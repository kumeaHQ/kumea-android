package co.ke.kumea.domain.model

/**
 * The crop catalogue, in one place so there is one list to change.
 *
 * TWO DIFFERENT QUESTIONS, and only the second is answered here (11 Aug
 * walkthrough):
 *
 *  | "What do you grow?"   | registration | a STANDING PROFILE — slow-changing, multi-crop |
 *  | "What did you plant?" | planting     | a SEASONAL FACT — already `Field.plantedAt` + harvest |
 *
 * The standing profile is the interesting one — Marcus's point is that we learn
 * what farmers grow from what they record after harvest, not from asking at
 * registration — and it wants a grouped multi-select with a third
 * "interested in growing" state, which is a sales signal nothing else in the
 * system captures. That is deliberately NOT built here: it needs a column that
 * can hold a set, and `fields.crop_type` is a single string. Deferred to Batch A
 * (settled 12 Aug) so it cannot block this season's registrations.
 *
 * What step 4 captures is the narrower, honest thing the single column can
 * hold: the PRIMARY LEGUME this registration is about. [GROUPS] already carries
 * the category structure the multi-select will need, so growing into it is
 * additive rather than a rewrite.
 */
data class Crop(
    /** Wire value. Goes to `fields.crop_type` verbatim — lowercase, stable, never localised. */
    val key: String,
    val label: String,
)

data class CropGroup(val name: String, val crops: List<Crop>)

object Crops {
    /**
     * LEGUMES ONLY on the registration path, and that is the point rather than
     * an omission. Kumea N is a rhizobia inoculant: it does something for a
     * legume and nothing at all for maize. The farmer-facing chip row still
     * offers maize because a farmer's own shamba record is about their farming,
     * not about our product — but a KWAP register entry that says "maize" is a
     * row the research cannot use.
     */
    val LEGUMES = listOf(
        Crop("beans", "Beans"),
        Crop("soybean", "Soybean"),
        Crop("green_gram", "Green gram"),
        Crop("cowpea", "Cowpea"),
        Crop("groundnut", "Groundnut"),
        Crop("pigeon_pea", "Pigeon pea"),
        Crop("chickpea", "Chickpea"),
        Crop("lablab", "Lablab"),
    )

    val CEREALS = listOf(
        Crop("maize", "Maize"),
        Crop("sorghum", "Sorghum"),
        Crop("millet", "Millet"),
    )

    /**
     * Forage dosing differs from grain legumes and each rhizobia strain has its
     * own packaging — the soybean pack is not a relabelled bean pack (11 Aug).
     * Kept separate so the catalogue can express strain × pack size when the
     * SKU work lands, rather than flattening to "three sizes of one product".
     */
    val FORAGE = listOf(
        Crop("lucerne", "Lucerne"),
        Crop("desmodium", "Desmodium"),
    )

    /** Grouped, for the multi-select checklist when it is built. */
    val GROUPS = listOf(
        CropGroup("Legumes", LEGUMES),
        CropGroup("Cereals", CEREALS),
        CropGroup("Forage", FORAGE),
    )

    private val byKey = GROUPS.flatMap { it.crops }.associateBy { it.key }

    /** Display label for a stored key; falls back to the key so an unknown value still reads. */
    fun label(key: String?): String? = key?.let { byKey[it]?.label ?: it }
}

/**
 * What one farm grows, and what it would like to (KWAP-03 §4.2) — the "column
 * that can hold a set" the comment above has been waiting for.
 *
 * TWO SETS, NOT ONE LIST WITH A FLAG, because they answer different questions
 * and the second one is the commercially interesting answer: `interested` is a
 * sales signal nothing else in the system records. A farmer growing maize who
 * would try soybean is a lead; today that farmer is indistinguishable from one
 * who will never plant a legume.
 *
 * The sets are disjoint by construction — you cannot be interested in growing
 * what you already grow, and a crop in both would produce two `farm_crops` rows
 * with the same primary key and lose one silently.
 */
data class CropSelection(
    val growing: Set<String> = emptySet(),
    val interested: Set<String> = emptySet(),
) {
    /** Interest in something already grown is not interest; growing wins. */
    val interestedOnly: Set<String> get() = interested - growing

    val isEmpty: Boolean get() = growing.isEmpty() && interestedOnly.isEmpty()

    /**
     * The denormalised primary crop for `farms.cropType`, which the farm-list
     * card reads without a join. Alphabetical rather than arbitrary: a set has
     * no insertion order, and a stable wrong-ish answer beats one that changes
     * between reads.
     */
    val primaryGrowing: String? get() = growing.minOrNull()
}
