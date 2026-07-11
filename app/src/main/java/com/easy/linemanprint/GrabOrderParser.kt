package com.easy.linemanprint

import kotlin.math.abs

object GrabOrderParser {

    // *** โหมดสอดแนม: เปิดชั่วคราวเพื่อดูว่าแอปอ่านหน้า Grab เห็นอะไรบ้าง ***
    // พอพี่คิมได้ข้อมูลแล้วจะสั่งปิด (เปลี่ยนเป็น false) ในรอบถัดไป
    private const val DEBUG = true

    private val itemRegex = Regex("^(\\d+)\\s*[xX×]\\s*(.+)$")
    private val priceRegex = Regex("(-?\\d{1,3}(?:,\\d{3})*\\.\\d{2})\\s*$")
    private val gfRegex = Regex("GF-\\d+")

    private val STOP_WORDS = listOf(
        "ค่าอาหาร", "รวมภาษี", "รวมทั้งหมด", "คนขับ",
        "แก้ไขคำสั่งซื้อ", "รหัสการจอง", "ยกเลิกคำสั่งซื้อ", "รายงานปัญหา"
    )
    private val JUNK = listOf("หากไม่มี", "ให้ติดต่อลูกค้า", "สินค้าทดแทน", "โฆษณา")

    private fun buildLines(nodes: List<NodeText>): List<String> {
        if (nodes.isEmpty()) return emptyList()
        val sorted = nodes.sortedWith(compareBy({ it.top }, { it.left }))
        val rows = ArrayList<ArrayList<NodeText>>()
        for (n in sorted) {
            val last = rows.lastOrNull()
            if (last != null && abs(last.first().top - n.top) <= 24) last.add(n)
            else rows.add(arrayListOf(n))
        }
        return rows.map { row ->
            row.sortedBy { it.left }.joinToString(" ") { it.text.trim() }.trim()
        }.filter { it.isNotBlank() }
    }

    private fun trailingPrice(s: String): Pair<String, String> {
        val m = priceRegex.find(s) ?: return Pair(s.trim(), "")
        val name = s.removeRange(m.range).trim()
        return Pair(name, m.groupValues[1])
    }

    private fun amountFrom(lines: List<String>, keyword: String): String {
        val l = lines.firstOrNull { it.contains(keyword) } ?: return ""
        return priceRegex.find(l)?.groupValues?.get(1) ?: ""
    }

    fun parse(nodes: List<NodeText>): Order {
        val lines = buildLines(nodes)
        val all = lines.joinToString("\n")

        val gf = gfRegex.find(all)?.value ?: ""
        val orderNo = gf.removePrefix("GF-")

        // ===== โหมดสอดแนม: พิมพ์บรรทัดที่อ่านเห็นออกมาเป็นใบ (จำกัด 35 บรรทัด กันใบยาวเกิน) =====
      if (DEBUG) {
            val sorted = nodes.sortedWith(compareBy({ it.top }, { it.left }))
            val hasWeb = nodes.any { it.cls.contains("Web", true) }
            val hasFlutter = nodes.any {
                it.cls.contains("Flutter", true) || it.cls.contains("Compose", true) ||
                it.cls.contains("Surface", true)
            }
            val dbg = ArrayList<OrderItem>()
            for ((i, n) in sorted.withIndex()) {
                if (i >= 28) break
                val tag = if (n.cls.isNotEmpty()) "[${n.cls}]" else ""
                dbg.add(OrderItem(i + 1, "$tag ${n.text}".take(40), ""))
            }
            val verdict = when {
                hasWeb -> "พบ WebView (มีลุ้น!)"
                hasFlutter -> "พบ Flutter/Canvas (ยาก)"
                else -> "ไม่พบ container พิเศษ"
            }
            return Order(
                orderNo = orderNo.ifEmpty { "DBG" },
                lmfCode = gf, branch = "", dateTime = "", customer = "",
                isNewCustomer = false, items = dbg,
                note = "*** $verdict ***",
                payment = "", subtotal = "", discount = "", net = "",
                parsedOk = true, platform = "GRAB-DEBUG2"
            )
        }
        // ===== โหมดปกติ (ยังไม่ใช้รอบนี้) =====
        val items = ArrayList<OrderItem>()
        for (line in lines) {
            if (STOP_WORDS.any { line.contains(it) }) {
                if (items.isNotEmpty()) break else continue
            }
            if (JUNK.any { line.contains(it) }) continue
            val m = itemRegex.find(line)
            if (m != null) {
                val qty = m.groupValues[1].toIntOrNull() ?: 1
                val (name, price) = trailingPrice(m.groupValues[2])
                items.add(OrderItem(qty, name, price))
            } else if (items.isNotEmpty()) {
                if (line.contains("รายการสำหรับ") || line.contains("ลูกค้า") ||
                    line.contains(gf)) continue
                val (opt, _) = trailingPrice(line)
                if (opt.isNotEmpty() && opt.length <= 60) {
                    val trimmed = opt.trim().trim('\'', '"', ' ')
                    val isNote = opt.trim().startsWith("'") || opt.trim().startsWith("\"")
                    if (isNote && trimmed.isNotEmpty()) items.last().options.add("• $trimmed")
                    else items.last().options.add(opt)
                }
            }
        }

        val subtotal = amountFrom(lines, "ค่าอาหาร")
        val total = amountFrom(lines, "รวมทั้งหมด")
        val discount = amountFrom(lines, "ส่วนลด")
        val net = if (total.isNotEmpty()) total else subtotal
        val ok = orderNo.isNotEmpty() && items.isNotEmpty()

        return Order(
            orderNo = orderNo, lmfCode = gf, branch = "", dateTime = "",
            customer = "", isNewCustomer = all.contains("ลูกค้าใหม่"),
            items = items, note = "", payment = "",
            subtotal = subtotal, discount = discount, net = net,
            parsedOk = ok, platform = "GRAB"
        )
    }
}
