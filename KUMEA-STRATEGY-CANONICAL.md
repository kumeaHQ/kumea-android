# KUMEA-STRATEGY-CANONICAL.md
> Updated: 12 July 2026 09:00 EAT
> Canonical strategy document for the Kumea Phase-1 distribution layer.

---

## §1 — The Product

**Biofix** is a biological nitrogen-fixing inoculant for legumes. It replaces synthetic nitrogen fertiliser at a fraction of the cost and weight.

| Spec | Value |
|---|---|
| Retail price (150g) | KES 1,500/sachet (locked 14 Jun 2026, 2 seasons minimum) |
| Retail price (50g) | KES 500/sachet (locked 12 Jul 2026, 2 seasons minimum) |
| Coverage | 1 sachet treats ~23kg seed, covers ~1 acre |
| Pack sizes | 150g (standard, 1 acre), 50g (smallholder / institutional). 100g RETIRED. |
| Competitor baseline | KES 6,500/acre for synthetic fertiliser |
| SKU codes | 150g sachet (standard), 50g sachet (demo) |
| C (cost) | ~KES 250/sachet conservative (pending China packaging) |

---

## §2 — Master Gate: KEPHIS Transfer

Biofix registration being transferred from MEA Ltd to Kumea Ltd.

| Status | Detail |
|---|---|
| **Path** | MEA former MD sends email to director@kephis.org → Alex Muvea (0723862632) follows up internally |
| **Meeting** | ✅ Met Sheila + Alex Muvea Mon 22 Jun. Path clear. |
| **Cost** | KES 20,000 |
| **Current** | MEA email in motion — former MD contacted. Waiting for email to be sent. |

---

## §3 — Cash Position

| Source | Amount | Status |
|---|---|---|
| Grandfather transfer | ~KES 2M | ✅ Received. In NCBA + SC USD accounts. |
| Spending | Under Kumea name | Flowing directly — materials, inverter, cables. |

---

## §4 — Agent Commission Model (LOCKED)

### Village Agent Tiers

| Band | Farmers served (lifetime) | Rate per sachet | Locked |
|---|---|---|---|
| 1 | 1–19 | KES 300 | 2 seasons |
| 2 | 20–49 | KES 350 | 2 seasons |
| 3 | 50+ | KES 400 | 2 seasons |

- Tier is cumulative lifetime farmers served — monotonic, never downgrades.
- Paid monthly by 5th (Phase 4 — settlement, not yet built).
- Commission engine activated **26 Jun 2026** on Railway production.
- 6 village_agents currently linked.

### 150g Dealer Pricing

| Volume | Wholesale price/sachet |
|---|---|
| 50+ | KES 1,200 |
| 200+ | KES 1,100 |
| 500+ | KES 1,000 |

- Dealer margin = spread between wholesale and retail (KES 1,500).
- No separate commission rule — price is the margin.
- Farmer-access logic: discounts exist to make the product affordable for smallholders.

### 50g Institutional Pricing (LOCKED 12 Jul 2026)

| Volume | Wholesale price/sachet | Margin |
|---|---|---|
| Retail (farmer) | 500 | 250 |
| 50–199 | 450 | 200 |
| 200–499 | 420 | 170 |
| 500+ (institutional floor) | 400 | 150 |

- **50g discount logic is procurement negotiation, not farmer access.** Institutions have budgets and are trained to negotiate — they are not price-sensitive smallholders.
- Tiers are flatter than 150g (max 20% off vs 33% off). The 150g can absorb deep discounts; the 50g cannot.
- Production cost is flat ~KES 250 across both SKUs — but 50g revenue is KES 500 vs 1,500, so every discount point is 5× more painful on margin.
- KES 400 floor preserves KES 150/sachet gross margin at any volume. Below 400 the unit economics break.
- "You're already getting 20% off the farmer price" is the counter when institutions push lower.

### Officer Exclusion
Extension officers (SCAOs/WAOs) NEVER accrue commission. Structural exclusion at database CHECK and application layer. They may register farmers (referrer = non-commercial) but never sell. This is immutable.

---

## §5 — Distribution Model

### Three Personas

| Persona | Role | Commission | App surface |
|---|---|---|---|
| **Farmer** | End user. Registers, receives product. | None | Farm management, order history |
| **Village Agent** | Recruited by officers. Sells Biofix to farmers. | Tiered (300/350/400) | Order recording, earnings dashboard, farmer registration |
| **Extension Officer** | SCAO/WAO. Registers farmers and agents. | Zero (structural exclusion) | Farmer/agent directory, referral tracking, NO commercial surface |

### Agent Recruitment (C16)
- Recruit from 43 extension officers + 15 farmers + Nandi network.
- "You register farmers on the Kumea app, they buy Biofix through you, you earn KES [tier] per sachet. Paid monthly by 5th. Want in?"
- Gated on KEPHIS email being sent.

---

## §6 — Distribution Channels

### Ward-Level SMS (C12 — LOCKED 26 Jun)

| Detail | Value |
|---|---|
| **Provider** | Agri Venta / Sila Serem (0724337760) |
| **Platform** | Celcom Africa (SMS + WhatsApp + Email) |
| **Rate** | KES 2.40/msg |
| **Wards** | Nandi Hills (9,286 farmers) + Chesumei (7,166 farmers) |
| **Total reach** | 16,452 farmers |
| **Language** | Swahili only |
| **SMS text** | "Panda maharagwe msimu huu? Biofix inaongeza mavuno, bei nafuu — KSh 1,500 kwa ekari. Uliza afisa wako wa kilimo leo." |
| **Handoff** | SMS → farmer asks WAO → WAO directs to village agent → agent registers and sells |
| **WAO briefing** | Marcus to brief WAOs in both wards before blast |

### S2 BWAP Strategy (LOCKED 24 Jun)
- Formal CDA letter → SCAO invitation → demo day at onset of rains.
- 4 scouts (Mulu + 3 new), per-county demo plots with caretakers paid per photo.
- County provides seed, Kumea provides 50g Biofix sachets + paperwork + app onboarding.
- SCAO WhatsApp groups for competition/collaboration.

---

## §7 — Technology Stack

### Three Repos

| Repo | Stack | Status |
|---|---|---|
| **kumea-android** | Kotlin, Jetpack Compose, Room, Hilt, WorkManager | Sprint 0 complete. Sprint 1 (Notes→Field→Ledger) pending. |
| **kumea-api** | NestJS, Prisma, PostgreSQL (Railway) | Sprint 0 + commission engine complete. Deployed. |
| **kumea-web** | Next.js | Landing page only. Dashboard pending. |

### Commission Engine (ACTIVATED 26 Jun)
- Railway production DB: CommissionRule + CommissionRuleTier seeded.
- 6 village_agents linked to tiered rule.
- Accrual is live — orders create real commission in the ledger.
- Settlement (Phase 4) not yet built.

---

## §8 — Current Priorities (26 Jun 2026)

1. **App UI polish** — farmer / village_agent / officer surfaces need real-user readiness.
2. **WAO briefing** — brief Nandi Hills + Chesumei WAOs before SMS blast.
3. **FCI4Africa grant (€50K)** — 4 days to hard deadline (30 Jun 17:00 CEST).
4. **CGT bank letter** — get signed for grandfather's estate.
5. **Autoclave housing** — fundis on site, design session needed.
6. **Sprint 1 build** — Notes → Field → Ledger (after UI assessment).
7. **BFX-001 invoice number** — Day 40. Blocking all invoicing.

---

## §9 — Nandi is Priority County #1

| Ward | Farmers | Status |
|---|---|---|
| Nandi Hills | 9,286 | SMS blast ready. WAO: Erick Kipkorir. Proven S1 farmer. |
| Chesumei | 7,166 | SMS blast ready. SCAO: Clara Jepletting. 2 farmers planted S1. |
| Mosop/Chepterwai | 14,875 | Sila Serem's home ward. Organic coverage via WAO. |

---

## §10 — Locked Rules (Non-Negotiable)

1. **Money = BigInt cents on wire, Long on device.** Never Float, never Double.
2. **Officers never see commercial surface.** Structural, not flag-hidden.
3. **Commission tier is monotonic lifetime farmers-served.** Never downgrades.
4. **Offline-first.** Room → WorkManager → Railway. Never a spinner for local writes.
5. **No silent catches.** Surface errors to the user. Never swallow.
6. **Real device against Railway is the close gate.** Not green CI.
7. **Retail prices: KES 1,500 (150g), KES 500 (50g).** Locked 2 seasons.
