package com.easy.linemanprint

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

object Prefs {
    private const val FILE = "easy_print_prefs"
    private const val KEY_MAC = "printer_mac"
    private const val KEY_NAME = "printer_name"
    private const val KEY_PRINTED = "printed_codes"   // รูปแบบ: "yyyyMMdd|code1|code2|..."

    // สวิตช์เปิด-ปิดการพิมพ์รายแอป
    private const val KEY_EN_LINEMAN = "enable_lineman"
    private const val KEY_EN_GRAB = "enable_grab"
    private const val KEY_EN_SHOPEE = "enable_shopee"

    // ด่านแรกในหน่วยความจำ (เร็ว + กันซ้ำแม้ดิสก์มีปัญหา)
    private val memDay = arrayOf("")
    private val memSet = Collections.synchronizedSet(HashSet<String>())

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun today() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    fun setPrinter(c: Context, mac: String, name: String) {
        sp(c).edit().putString(KEY_MAC, mac).putString(KEY_NAME, name).apply()
    }
    fun getPrinterMac(c: Context): String? = sp(c).getString(KEY_MAC, null)
    fun getPrinterName(c: Context): String? = sp(c).getString(KEY_NAME, null)

    // ---------- สวิตช์รายแอป (LINE MAN เปิดเป็นค่าเริ่มต้น, ที่เหลือปิด) ----------
    fun isLinemanOn(c: Context) = sp(c).getBoolean(KEY_EN_LINEMAN, true)
    fun isGrabOn(c: Context) = sp(c).getBoolean(KEY_EN_GRAB, false)
    fun isShopeeOn(c: Context) = sp(c).getBoolean(KEY_EN_SHOPEE, false)

    fun setLineman(c: Context, on: Boolean) = sp(c).edit().putBoolean(KEY_EN_LINEMAN, on).apply()
    fun setGrab(c: Context, on: Boolean) = sp(c).edit().putBoolean(KEY_EN_GRAB, on).apply()
    fun setShopee(c: Context, on: Boolean) = sp(c).edit().putBoolean(KEY_EN_SHOPEE, on).apply()

    @Synchronized
    fun isPrinted(c: Context, code: String): Boolean {
        val t = today()
        if (memDay[0] == t && memSet.contains(code)) return true     // เช็คหน่วยความจำก่อน
        val raw = sp(c).getString(KEY_PRINTED, "") ?: ""             // แล้วเช็คดิสก์
        val parts = raw.split("|")
        if (parts.isEmpty() || parts[0] != t) return false
        return parts.drop(1).contains(code)
    }

    @Synchronized
    fun markPrinted(c: Context, code: String) {
        val t = today()
        if (memDay[0] != t) { memDay[0] = t; memSet.clear() }        // ขึ้นวันใหม่ = ล้าง
        memSet.add(code)
        val raw = sp(c).getString(KEY_PRINTED, "") ?: ""
        val parts = raw.split("|")
        val newRaw = if (parts.isNotEmpty() && parts[0] == t) {
            if (parts.drop(1).contains(code)) raw else "$raw|$code"
        } else {
            "$t|$code"
        }
        sp(c).edit().putString(KEY_PRINTED, newRaw).commit()         // commit = เขียนทันที กันซ้ำชัวร์
    }
}
