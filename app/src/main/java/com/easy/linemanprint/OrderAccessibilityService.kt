package com.easy.linemanprint

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.concurrent.Executors

class OrderAccessibilityService : AccessibilityService() {

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastSig = ""

    // Grab ซ่อนรายการอาหารจาก Accessibility จึงอ่านจากภาพหน้าจอแทน
    private val grabScreenshotDelayMs = 900L
    @Volatile private var pendingGrabCode = ""
    @Volatile private var grabScreenshotScheduled = false

    private val lmfMarker = Regex("LMF-\\d{6}")
    private val grabMarker = Regex("GF-\\d{3,}")

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: ""
        if (pkg == packageName) return   // ข้ามแอพตัวเอง

        val nodes = ArrayList<NodeText>()
        try { collect(root, nodes) } catch (_: Exception) { return }

        val joined = nodes.joinToString("|") { it.text }
        // เลือกตัวอ่านตามแอปที่เจอบนหน้าจอ + เช็คสวิตช์ว่าเปิดแอปนั้นไว้ไหม
        when {
            // LINE MAN (WMA) — ทำงานเฉพาะเมื่อเปิดสวิตช์ไว้
            Prefs.isLinemanOn(this) && (
                joined.contains("รหัสใบสั่งซื้อ") ||
                joined.contains("รายการสั่งซื้อ") ||
                lmfMarker.containsMatchIn(joined)
            ) -> processOrder(OrderParser.parse(nodes))

            // Grab: รายการอาหารไม่อยู่ใน Accessibility tree จึงใช้ OCR จากภาพหน้าจอ
            Prefs.isGrabOn(this) &&
            grabMarker.containsMatchIn(joined) &&
            joined.contains("รวมทั้งหมด") -> {
                val grabCode = grabMarker.find(joined)?.value ?: return
                scheduleGrabOcr(grabCode)
            }

            else -> return // ไม่ใช่หน้าออเดอร์ที่เรารู้จัก / หรือปิดสวิตช์ไว้
        }
    }

    // จอง OCR หนึ่งครั้งต่อออเดอร์ เพื่อไม่ให้ content-change event ยิงซ้ำหลายใบ
    private fun scheduleGrabOcr(grabCode: String) {
        if (grabCode == pendingGrabCode && grabScreenshotScheduled) return

        pendingGrabCode = grabCode
        grabScreenshotScheduled = true
        main.postDelayed({ takeGrabScreenshot(grabCode) }, grabScreenshotDelayMs)
    }

    private fun takeGrabScreenshot(expectedCode: String) {
        if (expectedCode != pendingGrabCode) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            finishGrabOcr(expectedCode)
            toast("Grab OCR ต้องใช้ Android 11 ขึ้นไป")
            return
        }

        takeScreenshot(Display.DEFAULT_DISPLAY, worker, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val buffer = screenshot.hardwareBuffer
                val bitmap = try {
                    Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                        ?: throw IllegalStateException("แปลงภาพหน้าจอไม่สำเร็จ")
                } catch (e: Exception) {
                    finishGrabOcr(expectedCode)
                    toast("Grab OCR: ${e.message}")
                    return
                } finally {
                    buffer.close()
                }

                val order = GrabScreenOcr.makeDebugOrder(applicationContext, expectedCode, bitmap)
                bitmap.recycle()
                processOrder(order)
                // processOrder ตั้ง lastSig แล้วก่อนถึงบรรทัดนี้ จึงปล่อย event รอบถัดไปได้
                finishGrabOcr(expectedCode)
            }

            override fun onFailure(errorCode: Int) {
                finishGrabOcr(expectedCode)
                toast("Grab OCR ถ่ายภาพไม่สำเร็จ (code $errorCode)")
            }
        })
    }

    private fun finishGrabOcr(grabCode: String) {
        if (pendingGrabCode == grabCode) {
            pendingGrabCode = ""
            grabScreenshotScheduled = false
        }
    }

    private fun processOrder(order: Order) {
        val sig = order.lmfCode.ifEmpty { order.orderNo }
        if (sig.isEmpty()) return
        if (sig == lastSig) return                       // กันยิงซ้ำรัวๆ
        if (Prefs.isPrinted(this, sig)) { lastSig = sig; return }
        lastSig = sig
        LastOrder.value = order

        toast("พบออเดอร์ #${order.orderNo} กำลังพิมพ์...")  // ตัวบอกสถานะ

        worker.execute {
            try {
                val bmp = ReceiptRenderer.render(applicationContext, order)
                BluetoothPrinter.printBitmap(applicationContext, bmp)
                Prefs.markPrinted(applicationContext, sig)
                beep(order.parsedOk)
            } catch (e: Exception) {
                lastSig = ""                              // พิมพ์ไม่ออก -> ลองใหม่ได้
                beep(false)
                toast("พิมพ์ไม่ออก: ${e.message}")
            }
        }
    }

   private fun collect(node: AccessibilityNodeInfo?, out: ArrayList<NodeText>) {
        if (node == null) return
        val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
        val r = Rect()
        node.getBoundsInScreen(r)

        val t = node.text?.toString()?.trim()
        val d = node.contentDescription?.toString()?.trim()

        // ดึงทั้ง text และ contentDescription แยกกัน (เผื่อคนละค่า)
        if (!t.isNullOrEmpty()) out.add(NodeText("T:$t", r.left, r.top, cls))
        if (!d.isNullOrEmpty() && d != t) out.add(NodeText("D:$d", r.left, r.top, cls))

        // มาร์ก scroll container (รายการอาหารมักอยู่ในนี้) แม้ไม่มีตัวหนังสือ
        if (cls.contains("Recycler", true) || cls.contains("ScrollView", true) ||
            cls.contains("ListView", true)) {
            out.add(NodeText("[[${cls} childs=${node.childCount}]]", r.left, r.top, cls))
        }
        for (i in 0 until node.childCount) collect(node.getChild(i), out)
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun beep(success: Boolean) {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            if (success) tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            else tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 800)
            worker.execute { Thread.sleep(1200); tg.release() }
        } catch (_: Exception) {}
    }

    override fun onInterrupt() {}
}
