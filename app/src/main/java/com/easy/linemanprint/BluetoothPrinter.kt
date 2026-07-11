package com.easy.linemanprint

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.util.UUID

// ส่งรูปใบเสร็จไปเครื่องพิมพ์บลูทูธ ESC/POS
object BluetoothPrinter {

    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val lock = Any()

    // แปลง Bitmap ขาวดำ -> คำสั่ง raster ESC/POS (GS v 0)
    private fun rasterize(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val bytesPerRow = (w + 7) / 8
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00))
        out.write(bytesPerRow and 0xFF)
        out.write((bytesPerRow shr 8) and 0xFF)
        out.write(h and 0xFF)
        out.write((h shr 8) and 0xFF)

        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            for (xb in 0 until bytesPerRow) {
                var b = 0
                for (bit in 0 until 8) {
                    val x = xb * 8 + bit
                    if (x < w) {
                        val p = pixels[y * w + x]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val bl = p and 0xFF
                        val lum = (r * 299 + g * 587 + bl * 114) / 1000
                        if (lum < 160) b = b or (0x80 shr bit)  // เข้ม = จุดดำ
                    }
                }
                out.write(b)
            }
        }
        return out.toByteArray()
    }

    @SuppressLint("MissingPermission")
    fun printBitmap(context: Context, bmp: Bitmap) {
        val mac = Prefs.getPrinterMac(context)
            ?: throw IllegalStateException("ยังไม่ได้เลือกเครื่องพิมพ์")
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: throw IllegalStateException("เครื่องไม่มีบลูทูธ")
        val device = adapter.getRemoteDevice(mac)

        synchronized(lock) {
            val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            try {
                socket.connect()
                val os = socket.outputStream
                os.write(byteArrayOf(0x1B, 0x40))            // ESC @ init
                os.write(byteArrayOf(0x0A))                  // << เพิ่มบรรทัดนี้เพื่อเว้นด้านบนสุด 1 บรรทัด
                os.write(rasterize(bmp))                     // รูปใบเสร็จ
                // เว้นพื้นที่ด้านล่างให้กระดาษยื่นออกมา (เครื่องไม่ตัดก็ฉีกได้พอดี)
                os.write(byteArrayOf(0x0A))
                os.write(byteArrayOf(0x1D, 0x56, 0x41, 0x18)) // GS V 65 24 = ป้อนกระดาษแล้วตัดเต็ม
                os.flush()
                // ปรับสมการใหม่: ตีซะว่าเครื่องพิมพ์ทำงานช้าๆ ที่ 200 พิกเซล/วินาที เผื่อเวลาไว้เลย
// ถอดการจำกัดเวลาสูงสุดออก เปลี่ยนเป็นรอขั้นต่ำ 2 วินาทีแทน (coerceAtLeast)
                val waitMs = (bmp.height / 200.0 * 1000).toLong().coerceAtLeast(2000L)
Thread.sleep(waitMs)
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }
}
