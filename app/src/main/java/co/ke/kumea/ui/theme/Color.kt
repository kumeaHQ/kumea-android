package co.ke.kumea.ui.theme

import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────────────
// Soil Paper palette — VISUAL-SPEC-11Jul2026 §1, reconciled by Build-3 v2.
// This file is the ONLY place a color literal may appear in the app.
// ─────────────────────────────────────────────────────────────────────────────

// Surfaces — cards lift by warmth, not shadow.
val SoilPaper = Color(0xFFF7F3EA) // app background
val CardPaper = Color(0xFFFDFBF6) // card surface

// Text.
val Ink = Color(0xFF2A2620) // primary text
val InkMuted = Color(0xFF6B645A) // secondary text

// Greens.
val DeepLeaf = Color(0xFF23492F) // headers, app-bar text, emphasis
val LeafGreen = Color(0xFF2F6B3C) // primary actions, money-in
val LeafWash = Color(0xFFDCE8DC) // selected fills, tonal buttons (Deep Leaf text)

// Gold — harvest/proof surfaces ONLY: proof-card top rule + one icon, wizard
// progress dots, selected unit wash. Never buttons; GoldInk if gold must speak.
val HarvestGold = Color(0xFFC79A2A) // decorative only, never text
val GoldInk = Color(0xFF8A6A14) // gold-toned TEXT
val GoldWash = Color(0xFFFBF4E3) // harvest selected/review surfaces

// Clay — purchase/input semantics, NOT a general accent.
val Clay = Color(0xFF8C5A3C) // purchase/input icons + accents
val ClayWash = Color(0xFFF3E9E2) // purchase surfaces

// Teal — ALL sync/honesty states (stamps, synced chips, as-of labels).
val Teal = Color(0xFF3E7B74)

// Loss Red — closed-season loss verdict + true errors. Nothing else.
val LossRed = Color(0xFFA2352C)

// Borders/dividers.
val ClayLine = Color(0xFFE2D9CB)

// ─────────────────────────────────────────────────────────────────────────────
// Logo palette — locked in kumea-logo-spec.md; used only by the lockups
// (splash, Welcome header) and the mark drawables. Not part of the UI system.
// ─────────────────────────────────────────────────────────────────────────────
val LogoForest = Color(0xFF1F5C3D) // tree & wordmark
val LogoCharcoal = Color(0xFF4A4A4A) // tagline
