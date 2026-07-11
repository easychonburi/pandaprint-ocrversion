package com.easy.linemanprint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
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
            // ส่วนหัวของ Grab มีเวลา/สถานะ/รหัสจองเยอะและทำให้ OCR สับสน
            // เราจึงอ่านเฉพาะช่วงกลางถึงล่างที่มีรายการอาหารและยอดเงิน
            val prepared = prepareOrderArea(bitmap)
            val text = try {
                recognize(context, prepared)
            } finally {
                prepared.recycle()
            }
            val lines = text.lineSequence()
                .map { it.trim().replace(Regex("\\s+"), " ") }
                .filter { it.isNotEmpty() }
                .take(24)
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
            note = "*** GRAB OCR V2: เฉพาะโซนรายการ/ยอดเงิน ***",
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

    // Grab ใช้หน้าจอแนวตั้งคงที่พอสมควร: ตัดแถบสถานะ/ส่วนหัวด้านบนออก
    // แล้วขยาย + ทำขาวดำ เพื่อลด noise ก่อนส่งให้ OCR ภาษาไทย
    private fun prepareOrderArea(source: Bitmap): Bitmap {
        val top = (source.height * 0.30f).toInt()
        val bottom = (source.height * 0.95f).toInt()
        val crop = Bitmap.createBitmap(source, 0, top, source.width, bottom - top)
        val scaled = Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, true)
        crop.recycle()

        val width = scaled.width
        val height = scaled.height
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)

        // ตัวอักษรของ Grab เป็นดำ/เทา/ฟ้าอ่อนบนพื้นเกือบขาว
        // threshold ทำให้ Tesseract แยกตัวอักษรไทยจากพื้นหลังได้ง่ายขึ้น
        for (i in pixels.indices) {
            val color = pixels[i]
            val luminance = (
                Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114
            ) / 1000
            pixels[i] = if (luminance < 205) Color.BLACK else Color.WHITE
        }

        val prepared = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        prepared.setPixels(pixels, 0, width, 0, 0, width, height)
        scaled.recycle()
        return prepared
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
