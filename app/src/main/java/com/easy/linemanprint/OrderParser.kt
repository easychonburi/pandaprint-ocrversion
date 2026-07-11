package com.easy.linemanprint

import kotlin.math.abs

// node ข้อความ 1 ชิ้นที่อ่านได้จากหน้าจอ พร้อมตำแหน่ง
data class NodeText(val text: String, val left: Int, val top: Int, val cls: String = "")

object OrderParser {

    // คำที่บอกว่า "จบส่วนรายการอาหารแล้ว"
    private val STOP_WORDS = listOf("วิธีชำระเงิน", "รวมเป็นเงิน", "ส่วนลด", "ยอดรวม", "ค่าจัดส่ง")

    private val itemRegex = Regex("^(\\d+)\\s*[xX×]\\s*(.+)$")
    private val priceRegex = Regex("(-?\\d{1,3}(?:,\\d{3})*\\.\\d{2})\\s*$")
    private val lmfRegex = Regex("LMF-\\d{6}-\\d+")
    private val orderNoRegex = Regex("#(\\d+)")

    // รวม node ที่อยู่บรรทัดเดียวกัน (top ใกล้กัน) ให้เป็น 1 บรรทัด เรียงซ้าย->ขวา
    private fun buildLines(nodes: List<NodeText>): List<String> {
        if (nodes.isEmpty()) return emptyList()
        val sorted = nodes.sortedWith(compareBy({ it.top }, { it.left }))
        val rows = ArrayList<ArrayList<NodeText>>()
        for (n in sorted) {
            val last = rows.lastOrNull()
            if (last != null && abs(last.first().top - n.top) <= 24) {
                last.add(n)
            } else {
                rows.add(arrayListOf(n))
            }
        }
        return rows.map { row ->
            row.sortedBy { it.left }.joinToString(" ") { it.text.trim() }.trim()
        }.filter { it.isNotBlank() }
    }

    private fun trailingPrice(s: String): Pair<String, String> {
        val m = priceRegex.find(s) ?: return Pair(s.trim(), "")
        val price = m.groupValues[1]
        val name = s.removeRange(m.range).trim()
        return Pair(name, price)
    }

    fun parse(nodes: List<NodeText>): Order {
        val lines = buildLines(nodes)
        val all = lines.joinToString("\n")

        val orderNo = orderNoRegex.find(all)?.groupValues?.get(1) ?: ""
        val lmf = lmfRegex.find(all)?.value ?: ""

        // สาขา + วันเวลา จากบรรทัด "ลูกค้าสั่งออเดอร์: ... (สาขา)"
        var branch = ""
        var dateTime = ""
        val orderLine = lines.firstOrNull { it.contains("ลูกค้าสั่งออเดอร์") }
        if (orderLine != null) {
            branch = Regex("\\(([^)]+)\\)").find(orderLine)?.groupValues?.get(1)?.trim() ?: ""
            dateTime = orderLine.substringAfter(":", "")
                .substringBefore("(").replace("ลูกค้าสั่งออเดอร์", "").trim().trimEnd(',').trim()
        }
        if (branch.isEmpty()) {
            branch = lines.firstNotNullOfOrNull {
                Regex("\\(([^)]+)\\)").find(it)?.groupValues?.get(1)
            }?.trim() ?: ""
        }

        // ลูกค้า + ลูกค้าใหม่/เก่า
        val custLine = lines.firstOrNull { it.contains("ลูกค้าใหม่") || it.contains("ลูกค้าเก่า") } ?: ""
        val isNew = custLine.contains("ลูกค้าใหม่")
        val customer = custLine
            .replace("ลูกค้าใหม่", "").replace("ลูกค้าเก่า", "")
            .replace(Regex("[#\\d]"), "").trim()

        // โน้ตระดับออเดอร์
        val note = lines.firstOrNull {
            it.contains("ไม่รับช้อน") || it.contains("ช้อนส้อม") || it.contains("หมายเหตุ")
        }?.trim() ?: ""

        // รายการอาหาร: ตั้งแต่ "รายการสั่งซื้อ" จนเจอ STOP_WORDS
        val items = ArrayList<OrderItem>()
        val startIdx = lines.indexOfFirst { it.contains("รายการสั่งซื้อ") }
        if (startIdx >= 0) {
            var i = startIdx + 1
            while (i < lines.size) {
                val line = lines[i]
                if (STOP_WORDS.any { line.contains(it) }) break
                val m = itemRegex.find(line)
                if (m != null) {
                    val qty = m.groupValues[1].toIntOrNull() ?: 1
                    val (name, price) = trailingPrice(m.groupValues[2])
                    items.add(OrderItem(qty, name, price))
                } else if (items.isNotEmpty()) {
                    // บรรทัดที่ไม่ใช่ header -> เป็นตัวเลือก/ขนาดของเมนูล่าสุด
                    if (!line.contains("ราคา") && !line.contains("รายการสั่งซื้อ") &&
                        !line.contains("แก้ไขรายการ") && !line.contains("ช้อนส้อม") &&
                        !line.contains("ไม่รับช้อน") && line.length <= 60) {
                        items.last().options.add(line)
                    }
                }
                i++
            }
        }

        // ยอดเงิน
        fun amountFrom(keyword: String): String {
            val l = lines.firstOrNull { it.contains(keyword) } ?: return ""
            return priceRegex.find(l)?.groupValues?.get(1) ?: ""
        }
        val subtotal = amountFrom("รวมเป็นเงิน")
        val discount = amountFrom("ส่วนลดร้านค้า")
        var payment = ""
        lines.firstOrNull { it.contains("วิธีชำระเงิน") }?.let {
            payment = it.substringAfter("วิธีชำระเงิน").trim().ifEmpty { "" }
        }
        if (payment.isEmpty()) {
            payment = when {
                all.contains("E-Payment", true) -> "E-Payment"
                all.contains("เงินสด") -> "เงินสด"
                all.contains("โอน") -> "โอนเงิน"
                else -> ""
            }
        }

        // สุทธิ = รวม + ส่วนลด (ส่วนลดติดลบอยู่แล้ว)
        val net = computeNet(subtotal, discount)

        val ok = orderNo.isNotEmpty() && items.isNotEmpty()

        return Order(
            orderNo = orderNo,
            lmfCode = lmf,
            branch = branch,
            dateTime = dateTime,
            customer = customer,
            isNewCustomer = isNew,
            items = items,
            note = note,
            payment = payment,
            subtotal = subtotal,
            discount = discount,
            net = net,
            parsedOk = ok
        )
    }

    private fun toDouble(s: String): Double? =
        s.replace(",", "").toDoubleOrNull()

    private fun computeNet(subtotal: String, discount: String): String {
        val sub = toDouble(subtotal) ?: return ""
        val dis = toDouble(discount) ?: 0.0
        val net = sub + dis // discount ปกติเป็นค่าลบ
        return String.format("%,.2f", net)
    }
}
