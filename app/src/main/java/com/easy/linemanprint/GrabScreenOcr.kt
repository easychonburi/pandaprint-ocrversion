package com.easy.linemanprint

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream

// อ่านเฉพาะภาพหน้าจอ Grab บนเครื่อง เพื่อชดเชยส่วนรายการอาหารที่ Grab
// ไม่เปิดให้ Accessibility อ่านได้ ข้อมูลภาพและข้อความไม่ถูกส่งออกจากมือถือ
object GrabScreenOcr {

    private const val DATA_DIR = "panda_tesseract"
    private const val ASSET_DATA = "tessdata/tha.traineddata"

    fun makeDebugOrder(context: Context, grabCode: String, bitmap: Bitmap): Order {
        val result = try {
            val text = recognize(context, bitmap)
            val lines = text.lineSequence()
                .map { it.trim().replace(Regex("\\s+"), " ") }
                .filter { it.isNotEmpty() }
                .take(28)
                .toList()

            if (lines.isEmpty()) listOf("[OCR ไม่พบข้อความ]") else lines
        } catch (e: Exception) {
            listOf("[OCR error: ${e.message ?: e.javaClass.simpleName}]")
        }

        return Order(
            orderNo = grabCode.removePrefix("GF-"),
            lmfCode = grabCode,
            branch = "",
            dateTime = "",
            customer = "",
            isNewCustomer = false,
            items = result.mapIndexed { index, line ->
                OrderItem(index + 1, line.take(90), "")
            },
            note = "*** GRAB OCR DEBUG: ตรวจชื่อเมนู/จำนวน/ราคา ***",
            payment = "",
            subtotal = "",
            discount = "",
            net = "",
            parsedOk = true,
            platform = "GRAB-OCR"
        )
    }

    private fun recognize(context: Context, bitmap: Bitmap): String {
        val dataPath = ensureThaiData(context)
        val tess = TessBaseAPI()
        try {
            check(tess.init(dataPath, "tha")) { "เปิดโมเดล OCR ภาษาไทยไม่สำเร็จ" }
            tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            tess.setImage(bitmap)
            return tess.getUTF8Text()
        } finally {
            tess.recycle()
        }
    }

    // Tesseract ต้องอ่าน model จาก storage ภายในของแอป ไม่สามารถอ่านตรงจาก APK ได้
    private fun ensureThaiData(context: Context): String {
        val root = File(context.filesDir, DATA_DIR)
        val tessData = File(root, "tessdata")
        val target = File(tessData, "tha.traineddata")
        if (!target.exists()) {
            check(tessData.exists() || tessData.mkdirs()) { "สร้างโฟลเดอร์ OCR ไม่สำเร็จ" }
            context.assets.open(ASSET_DATA).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
        }
        return root.absolutePath
    }
}
