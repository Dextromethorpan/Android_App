package com.luciano.chordseq

import android.graphics.Color

// ═════════════════════════════════════════════════════════════════════════════
//  AppColors.kt — Shared colour palette for ChordsPro
//  Used by both MainActivity and MainActivity2
// ═════════════════════════════════════════════════════════════════════════════
object C {
    val BG_SCREEN   = Color.parseColor("#0E0E14")
    val BG_WRAP     = Color.parseColor("#1A1A24")
    val BG_SECTION  = Color.parseColor("#111120")
    val BG_CARD     = Color.parseColor("#161628")
    val BG_PREDICT  = Color.parseColor("#0A0A12")
    val BORDER      = Color.parseColor("#2A2A3E")

    val TXT_PRIMARY = Color.parseColor("#E8E8F2")
    val TXT_MUTED   = Color.parseColor("#555570")
    val TXT_HINT    = Color.parseColor("#444466")

    val PURPLE      = Color.parseColor("#534AB7")
    val PURPLE_MID  = Color.parseColor("#7F77DD")
    val PURPLE_DARK = Color.parseColor("#3C3489")
    val PURPLE_LITE = Color.parseColor("#CECBF6")
    val ORANGE      = Color.parseColor("#FF6A4A")

    val SLOTS = arrayOf(
        intArrayOf(Color.parseColor("#18153A"), Color.parseColor("#CCC8FF"),
            Color.parseColor("#7F77DD"), Color.parseColor("#7F77DD")),
        intArrayOf(Color.parseColor("#0D1E18"), Color.parseColor("#9FE1CB"),
            Color.parseColor("#1D9E75"), Color.parseColor("#1D9E75")),
        intArrayOf(Color.parseColor("#1E1508"), Color.parseColor("#FAC775"),
            Color.parseColor("#BA7517"), Color.parseColor("#BA7517")),
        intArrayOf(Color.parseColor("#1E0D14"), Color.parseColor("#F4C0D1"),
            Color.parseColor("#D4537E"), Color.parseColor("#D4537E"))
    )
    val NOTE_COLORS = intArrayOf(
        Color.parseColor("#7F77DD"), Color.parseColor("#1D9E75"),
        Color.parseColor("#BA7517"), Color.parseColor("#D4537E")
    )
}