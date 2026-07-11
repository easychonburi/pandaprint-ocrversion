package com.easy.linemanprint

// โครงข้อมูลออเดอร์ที่อ่านได้จากหน้าแอปเดลิเวอรี
data class OrderItem(
    val qty: Int,
    val name: String,
    val price: String,
    val options: MutableList<String> = mutableListOf()
)

data class Order(
    val orderNo: String,     // เช่น 4561 / 360
    val lmfCode: String,     // LMF-xxx (LINE MAN) หรือ GF-xxx (Grab) ใช้กันพิมพ์ซ้ำ
    val branch: String,
    val dateTime: String,
    val customer: String,
    val isNewCustomer: Boolean,
    val items: List<OrderItem>,
    val note: String,
    val payment: String,
    val subtotal: String,
    val discount: String,
    val net: String,
    val parsedOk: Boolean,
    val platform: String = "LINE MAN"   // แอปต้นทาง (LINE MAN / GRAB) - ของเดิมไม่ใส่ = LINE MAN
)

// เก็บออเดอร์ล่าสุดไว้สำหรับปุ่ม "พิมพ์ซ้ำ"
object LastOrder {
    @Volatile var value: Order? = null
}
