package com.livecaption.app

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val NAME = "caption_prefs"

    const val SOURCE = "source"     // 0 = 폰 소리, 1 = 마이크
    const val FONT = "font"         // 글자 크기 (sp)
    const val POS = "pos"           // 0 = 위, 1 = 가운데, 2 = 아래
    const val BG = "bg"             // 배경 진하기 0..100
    const val LINES = "lines"       // 보여줄 줄 수 1..4

    const val SOURCE_PHONE = 0
    const val SOURCE_MIC = 1

    fun get(c: Context): SharedPreferences = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun source(p: SharedPreferences) = p.getInt(SOURCE, SOURCE_PHONE)
    fun font(p: SharedPreferences) = p.getInt(FONT, 24)
    fun pos(p: SharedPreferences) = p.getInt(POS, 2)
    fun bg(p: SharedPreferences) = p.getInt(BG, 55)
    fun lines(p: SharedPreferences) = p.getInt(LINES, 2)
}
