package com.easy.linemanprint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream

// OCR ของ Grab ทำงานบนมือถือทั้งหมด ภาพหน้าจอและข้อความไม่ถูกส่งออกจากเครื่อง
object GrabScreenOcr {

    private const val DATA_DIR = "panda_tesseract"
    private const val ASSET_DATA = "tessdata/tha.traineddata"
    private const val MODEL_VERSION = "tha-best-v3"

    fun makeDebugOrder(context: Context, grabCode: String, bitmap: Bitmap): Order {
        val output = try {
            // อ่านแยกสอง block เพื่อไม่ให้รหัสจอง/สถานะ/ข้อความส่วนหัวรบกวนชื่อเมนู
            val menuLines = readBlock(
                context, bitmap, topFraction = 0.47f, bottomFraction = 0.79f,
                pageMode = TessBaseAPI.PageSegMode.PSM_AUTO
            )
            val amountLines = readBlock(
                context, bitmap, topFraction = 0.76f, bottomFraction = 0.94f,
                pageMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
            )

            buildList {
                add("[เมนู / ตัวเลือก]")
                addAll(menuLines.ifEmpty { listOf("ไม่พบข้อความ") })
                add("[ยอดเงิน]")
                addAll(amountLines.ifEmpty { listOf("ไม่พบข้อความ") })
            }
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
            items = output.take(24).mapIndexed { index, line ->
                OrderItem(index + 1, line.take(90), "")
            },
            note = "*** GRAB OCR V3: เมนูและยอดเงินแยกกันอ่าน ***",
            payment = "",
            subtotal = "",
            discount = "",
            net = "",
            parsedOk = true,
            platform = "GRAB-OCR"
        )
    }

    private fun readBlock(
        context: Context,
        source: Bitmap,
        topFraction: Float,
        bottomFraction: Float,
        pageMode: TessBaseAPI.PageSegMode
    ): List<String> {
        val prepared = prepareBlock(source, topFraction, bottomFraction)
        val text = try {
            recognize(context, prepared, pageMode)
        } finally {
            prepared.recycle()
        }
        return text.lineSequence()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() }
            .toList()
    }

    private fun recognize(
        context: Context,
        bitmap: Bitmap,
        pageMode: TessBaseAPI.PageSegMode
    ): String {
        val tess = TessBaseAPI()
        try {
            check(tess.init(ensureThaiData(context), "tha")) {
                "เปิดโมเดล OCR ภาษาไทยไม่สำเร็จ"
            }
            tess.setPageSegMode(pageMode)
            tess.setImage(bitmap)
            return tess.getUTF8Text()
        } finally {
            tess.recycle()
        }
    }

    // ใช้ภาพเทา contrast สูงแทนการ threshold ขาว/ดำทั้งภาพ เพราะวรรณยุกต์ไทย
    // และเส้นบางของ ก/ค สูญหายได้ง่ายเมื่อบีบเป็นขาวดำเร็วเกินไป
    private fun prepareBlock(source: Bitmap, topFraction: Float, bottomFraction: Float): Bitmap {
        val top = (source.height * topFraction).toInt().coerceIn(0, source.height - 1)
        val bottom = (source.height * bottomFraction).toInt().coerceIn(top + 1, source.height)
        val crop = Bitmap.createBitmap(source, 0, top, source.width, bottom - top)
        val scaled = Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, true)
        crop.recycle()

        val width = scaled.width
        val height = scaled.height
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val color = pixels[i]
            val luminance = (
                Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114
            ) / 1000
            // เพิ่ม contrast แต่ยังคงระดับเทาเพื่อรักษาวรรณยุกต์และเส้นตัวอักษร
            val contrasted = ((luminance - 128) * 1.45f + 128).toInt().coerceIn(0, 255)
            pixels[i] = Color.rgb(contrasted, contrasted, contrasted)
        }

        val prepared = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        prepared.setPixels(pixels, 0, width, 0, 0, width, height)
        scaled.recycle()
        return prepared
    }

    // Tesseract ต้องอ่าน model จาก storage ภายในของแอป ไม่สามารถอ่านตรงจาก APK ได้
    // version marker บังคับอัปเกรดจาก model fast รุ่นก่อนเป็น model best รุ่นนี้
    private fun ensureThaiData(context: Context): String {
        val root = File(context.filesDir, DATA_DIR)
        val tessData = File(root, "tessdata")
        val target = File(tessData, "tha.traineddata")
        val marker = File(root, "model_version.txt")
        val mustCopy = !target.exists() || !marker.exists() || marker.readText() != MODEL_VERSION
        if (mustCopy) {
            check(tessData.exists() || tessData.mkdirs()) { "สร้างโฟลเดอร์ OCR ไม่สำเร็จ" }
            context.assets.open(ASSET_DATA).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            marker.writeText(MODEL_VERSION)
        }
        return root.absolutePath
    }
}
