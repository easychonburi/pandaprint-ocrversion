package com.easy.linemanprint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

// วาดใบเสร็จเป็น "รูปภาพ" (กว้าง 384px = 58 มม.) ออกแบบให้คนหน้าครัวอ่านเร็ว
object ReceiptRenderer {

    private const val WIDTH = 384
    private const val PAD = 4

    // ===== ค่าเริ่มต้น (กลางๆ ไม่ผูกกับร้านใด) — ลูกค้าจะตั้งทับเองในหน้าตั้งค่าบิลภายหลัง =====
    private const val DEFAULT_SHOP = "Panda Printer"
    private const val CTA_LINE1 = "ขอบคุณที่ใช้บริการ"
    private const val CTA_LINE2 = ""

    private fun p(size: Float, bold: Boolean = false, center: Boolean = false) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = size
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            if (center) textAlign = Paint.Align.CENTER
        }

    private val pHeader = p(48f, bold = true, center = true)   // ชื่อร้าน
    private val pSub = p(23f, center = true)                   // แพลตฟอร์ม / สาขา / เวลา
    private val pHuge = p(110f, bold = true, center = true)    // เลขออเดอร์ ใหญ่สุด
    private val pItem = p(36f, bold = true)                    // ★ เมนู + จำนวน เด่นสุด
    private val pOptVal = p(29f, bold = true)                  // ★ ท็อปปิ้ง/ขนาด/โน้ต ที่เลือก
    private val pOptLabel = p(25f)                             // หัวข้อตัวเลือก
    private val pNote = p(22f)                                 // โน้ตเล็กๆ (ช้อนส้อม ฯลฯ)
    private val pSmall = p(21f)                                // รหัส / ยอดเงิน
    private val pNet = p(26f, bold = true)                     // ยอดสุทธิ
    private val pCta = p(26f, bold = true, center = true)
    private val pImg = Paint().apply { isAntiAlias = false; isFilterBitmap = false }

    private sealed class El
    private data class TextEl(val text: String, val paint: Paint, val center: Boolean = false) : El()
    private data class ImgEl(val bmp: Bitmap) : El()
    private data class Gap(val px: Int) : El()

    private fun wrap(text: String, paint: Paint, maxW: Int): List<String> {
        if (text.isBlank()) return listOf("")
        val words = text.split(" ")
        val out = ArrayList<String>()
        var cur = StringBuilder()
        for (w in words) {
            val test = if (cur.isEmpty()) w else "$cur $w"
            if (paint.measureText(test) <= maxW) cur = StringBuilder(test)
            else {
                if (cur.isNotEmpty()) out.add(cur.toString())
                if (paint.measureText(w) > maxW) {
                    var chunk = StringBuilder()
                    for (ch in w) {
                        if (paint.measureText(chunk.toString() + ch) > maxW) {
                            out.add(chunk.toString()); chunk = StringBuilder()
                        }
                        chunk.append(ch)
                    }
                    cur = chunk
                } else cur = StringBuilder(w)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun line(ch: String) = TextEl(ch.repeat(32), pSmall)
    private fun fit(text: String, base: Paint, maxW: Int): Paint {
        if (base.measureText(text) <= maxW) return base
        val q = Paint(base)
        while (q.textSize > 30f && q.measureText(text) > maxW) q.textSize -= 4f
        return q
    }

    fun render(context: Context, order: Order): Bitmap =
        if (order.parsedOk) renderFull(context, order) else renderFallback(order)

    // ค่าเริ่มต้น: ไม่โหลด QR ใดๆ (กันไม่ให้ QR ร้านเดิมหลุดออกใบ)
    // ภายหลังเมื่อทำหน้าตั้งค่าบิล ค่อยให้โหลด QR ที่ลูกค้าอัปโหลดเองจาก Prefs
    private fun loadQr(context: Context): Bitmap? = null

    private fun renderFull(context: Context, o: Order): Bitmap {
        val maxW = WIDTH - 2 * PAD
        val els = ArrayList<El>()

        // --- หัวใบ ---
        els.add(TextEl(DEFAULT_SHOP, pHeader, true))
        val sub = buildString {
            append(o.platform)                                   // โชว์ LINE MAN หรือ GRAB ตามต้นทาง
            if (o.branch.isNotEmpty()) append("  ·  สาขา ${o.branch}")
        }
        els.add(TextEl(sub, pSub, true))
        els.add(line("="))

        // --- เลขออเดอร์ ใหญ่สุด ---
        val tag = "#${o.orderNo}"
        els.add(TextEl(tag, fit(tag, pHuge, maxW), true))
        if (o.dateTime.isNotEmpty()) els.add(TextEl(o.dateTime, pSub, true))
        els.add(line("="))

        // --- ★ รายการอาหาร เด่นสุด ---
        for ((i, it) in o.items.withIndex()) {
            if (i > 0) { els.add(Gap(6)); els.add(TextEl("- - - - - - - - - -", pSmall, true)); els.add(Gap(6)) }
            for (w in wrap("${it.qty} x ${it.name}", pItem, maxW)) els.add(TextEl(w, pItem))
            for (op in it.options) {
                val t = op.trim()
                val isValue = t.startsWith("•") || t.startsWith("-")
                val clean = t.trimStart('•', '-', ' ')
                if (isValue) for (w in wrap("    ▸ $clean", pOptVal, maxW)) els.add(TextEl(w, pOptVal))
                else for (w in wrap("  $clean", pOptLabel, maxW)) els.add(TextEl(w, pOptLabel))
            }
        }
        els.add(line("="))

        // --- โน้ตเล็กๆ + ยอดเงิน (ข้อมูลรอง) ---
        if (o.note.isNotEmpty()) els.add(TextEl(o.note, pNote))
        if (o.subtotal.isNotEmpty()) els.add(TextEl("รวม          ${o.subtotal}", pSmall))
        if (o.discount.isNotEmpty()) els.add(TextEl("ส่วนลดร้าน   ${o.discount}", pSmall))
        if (o.net.isNotEmpty()) els.add(TextEl("สุทธิ  ${o.net}", pNet))
        if (o.payment.isNotEmpty()) els.add(TextEl("ชำระ: ${o.payment}", pSmall))
        if (o.lmfCode.isNotEmpty()) els.add(TextEl(o.lmfCode, pSmall))

        // --- ท้ายใบ ---
        els.add(line("-"))
        loadQr(context)?.let { els.add(ImgEl(it)) }
        if (CTA_LINE1.isNotEmpty()) els.add(TextEl(CTA_LINE1, pCta, true))
        if (CTA_LINE2.isNotEmpty()) els.add(TextEl(CTA_LINE2, pCta, true))
        return draw(els)
    }

    private fun renderFallback(o: Order): Bitmap {
        val maxW = WIDTH - 2 * PAD
        val els = ArrayList<El>()
        els.add(TextEl(DEFAULT_SHOP, pHeader, true))
        els.add(TextEl("*** ออเดอร์ใหม่ ***", pCta, true))
        els.add(line("="))
        val tag = if (o.orderNo.isNotEmpty()) "#${o.orderNo}" else "#????"
        els.add(TextEl(tag, fit(tag, pHuge, maxW), true))
        els.add(line("="))
        if (o.branch.isNotEmpty()) els.add(TextEl("สาขา ${o.branch}", pItem))
        els.add(Gap(8))
        els.add(TextEl("อ่านรายละเอียดไม่ครบ", pItem))
        els.add(TextEl(">> เปิดแอพดูออเดอร์ <<", pItem))
        if (o.lmfCode.isNotEmpty()) { els.add(Gap(8)); els.add(TextEl(o.lmfCode, pSmall)) }
        els.add(line("-"))
        return draw(els)
    }

    private fun draw(els: List<El>): Bitmap {
        var h = PAD
        for (e in els) h += when (e) {
            is TextEl -> { val fm = e.paint.fontMetrics; (fm.descent - fm.ascent + 6).toInt() }
            is ImgEl -> e.bmp.height + 12
            is Gap -> e.px
        }
        h += PAD + 60 // เว้นล่างในรูป

        val bmp = Bitmap.createBitmap(WIDTH, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        var y = PAD.toFloat()
        for (e in els) when (e) {
            is TextEl -> {
                val fm = e.paint.fontMetrics
                y += -fm.ascent
                if (e.center) {
                    if (e.paint.textAlign == Paint.Align.CENTER) c.drawText(e.text, WIDTH / 2f, y, e.paint)
                    else { val w = e.paint.measureText(e.text); c.drawText(e.text, (WIDTH - w) / 2f, y, e.paint) }
                } else c.drawText(e.text, PAD.toFloat(), y, e.paint)
                y += fm.descent + 6
            }
            is ImgEl -> { c.drawBitmap(e.bmp, (WIDTH - e.bmp.width) / 2f, y + 6f, pImg); y += e.bmp.height + 12 }
            is Gap -> y += e.px
        }
        return bmp
    }
}
