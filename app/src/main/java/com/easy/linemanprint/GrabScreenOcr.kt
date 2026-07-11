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
    private const val MODEL_VERSION = "tha-best-v4"

    private data class OcrLine(val text: String, val top: Int, val bottom: Int)

    fun makeDebugOrder(context: Context, grabCode: String, bitmap: Bitmap): Order {
        val output = try {
            // รอบแรกหา "จุดอ้างอิง" และพิกัดจริงบนหน้าจอ
            val body = prepareBody(bitmap)
            try {
                val lines = scanLines(context, body)
                val itemStart = lines.indexOfFirst { isItemLine(it.text) }
                val foodTotal = lines.indexOfFirst { it.text.contains("ค่าอาหาร") }
                val grandTotal = lines.indexOfFirst { it.text.contains("รวมทั้งหมด") }

                if (itemStart < 0 || foodTotal <= itemStart) {
                    listOf(
                        "[หาเส้นเมนู/ค่าอาหารไม่เจอ]",
                        "items=$itemStart food=$foodTotal total=$grandTotal"
                    )
                } else {
                    // รอบสองอ่านเฉพาะกรอบระหว่างรายการแรกและค่าอาหาร
                    val menu = crop(body, lines[itemStart].top - 12, lines[foodTotal].top - 12)
                    val amounts = crop(
                        body,
                        lines[foodTotal].top - 12,
                        if (grandTotal > foodTotal) lines[grandTotal].bottom + 20 else lines[foodTotal].bottom + 220
                    )
                    try {
                        buildList {
                            add("[เมนู / ตัวเลือก]")
                            addAll(readPrepared(context, menu, TessBaseAPI.PageSegMode.PSM_AUTO).ifEmpty { listOf("ไม่พบข้อความ") })
                            add("[ยอดเงิน]")
                            addAll(readPrepared(context, amounts, TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT).ifEmpty { listOf("ไม่พบข้อความ") })
                            add("[anchors: item=$itemStart food=$foodTotal total=$grandTotal]")
                        }
                    } finally {
                        menu.recycle()
                        amounts.recycle()
                    }
                }
            } finally {
                body.recycle()
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
            note = "*** GRAB OCR V4: หา anchor ก่อนตัดภาพ ***",
            payment = "",
            subtotal = "",
            discount = "",
            net = "",
            parsedOk = true,
            platform = "GRAB-OCR V4"
        )
    }

    // หา text line และ bounding box จาก OCR เพื่อให้รองรับหน้าจอที่มี/ไม่มีแบนเนอร์
    private fun scanLines(context: Context, bitmap: Bitmap): List<OcrLine> {
        val tess = TessBaseAPI()
        try {
            check(tess.init(ensureThaiData(context), "tha")) { "เปิดโมเดล OCR ภาษาไทยไม่สำเร็จ" }
            tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            tess.setImage(bitmap)
            tess.getUTF8Text() // สั่งให้ engine สร้าง result iterator

            val iterator = tess.getResultIterator() ?: return emptyList()
            try {
                val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
                iterator.begin()
                while (!iterator.isAtBeginningOf(level)) {
                    if (!iterator.next(level)) return emptyList()
                }

                val result = ArrayList<OcrLine>()
                while (true) {
                    val text = iterator.getUTF8Text(level)?.trim().orEmpty()
                    val box = iterator.getBoundingBox(level)
                    if (text.isNotEmpty() && box.size >= 4) {
                        result.add(OcrLine(text, box[1], box[3]))
                    }
                    if (!iterator.next(level)) break
                }
                return result
            } finally {
                iterator.delete()
            }
        } finally {
            tess.recycle()
        }
    }

    private fun isItemLine(text: String): Boolean =
        Regex("^\\s*\\d+\\s*[xX×]\\s*.+").containsMatchIn(text)

    private fun readPrepared(context: Context, bitmap: Bitmap, pageMode: Int): List<String> {
        val text = recognize(context, bitmap, pageMode)
        return text.lineSequence()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() }
            .toList()
    }

    private fun recognize(context: Context, bitmap: Bitmap, pageMode: Int): String {
        val tess = TessBaseAPI()
        try {
            check(tess.init(ensureThaiData(context), "tha")) { "เปิดโมเดล OCR ภาษาไทยไม่สำเร็จ" }
            tess.setPageSegMode(pageMode)
            tess.setImage(bitmap)
            return tess.getUTF8Text().orEmpty()
        } finally {
            tess.recycle()
        }
    }

    // อ่าน body กว้างพอที่จะรองรับหน้า Grab ที่มีแบนเนอร์ แต่ตัด status bar ด้านบนออก
    private fun prepareBody(source: Bitmap): Bitmap {
        val top = (source.height * 0.28f).toInt().coerceIn(0, source.height - 1)
        val bottom = (source.height * 0.98f).toInt().coerceIn(top + 1, source.height)
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
            val contrasted = ((luminance - 128) * 1.30f + 128).toInt().coerceIn(0, 255)
            pixels[i] = Color.rgb(contrasted, contrasted, contrasted)
        }
        val prepared = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        prepared.setPixels(pixels, 0, width, 0, 0, width, height)
        scaled.recycle()
        return prepared
    }

    private fun crop(source: Bitmap, requestedTop: Int, requestedBottom: Int): Bitmap {
        val top = requestedTop.coerceIn(0, source.height - 1)
        val bottom = requestedBottom.coerceIn(top + 1, source.height)
        return Bitmap.createBitmap(source, 0, top, source.width, bottom - top)
    }

    // Tesseract ต้องอ่าน model จาก storage ภายในของแอป ไม่สามารถอ่านตรงจาก APK ได้
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
