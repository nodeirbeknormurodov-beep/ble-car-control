package com.example.blecarcontrol

import android.content.Context

/**
 * Sozlamalarni saqlash uchun oddiy yordamchi.
 * "BLE kodi" - bu sizning ESP32 qurilmangizning reklama (advertising) nomi
 * bo'lib, ilova shu nom bo'yicha qurilmani qidirib topadi va ulanadi.
 * Xohlasangiz, buni ESP32 tarafida ham tasdiqlash (auth) kodi sifatida
 * ishlatish mumkin - BleForegroundService ulangandan keyin shu kodni
 * birinchi buyruq sifatida yuboradi.
 */
object Prefs {
    private const val PREFS_NAME = "ble_car_control_prefs"
    private const val KEY_BLE_CODE = "ble_code"

    fun getBleCode(context: Context): String {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getString(KEY_BLE_CODE, "") ?: ""
    }

    fun setBleCode(context: Context, code: String) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_BLE_CODE, code).apply()
    }
}
