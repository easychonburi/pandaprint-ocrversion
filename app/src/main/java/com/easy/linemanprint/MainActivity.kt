package com.easy.linemanprint

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var spinner: Spinner
    private var devices: List<Pair<String, String>> = emptyList() // name to mac

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
        }

        status = TextView(this).apply { textSize = 16f }
        root.addView(status)
        root.addView(space(24))

        root.addView(button("1) เปิดสิทธิ์ Accessibility") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(space(12))

        root.addView(TextView(this).apply { text = "2) เลือกเครื่องพิมพ์บลูทูธ (จับคู่ในตั้งค่าก่อน)"; textSize = 15f })
        spinner = Spinner(this)
        root.addView(spinner)
        root.addView(button("บันทึกเครื่องพิมพ์") { savePrinter() })
        root.addView(space(12))

        // --- 3) เลือกแอปที่จะให้พิมพ์ ---
        root.addView(TextView(this).apply {
            text = "3) เลือกแอปที่จะให้พิมพ์"; textSize = 15f
            setPadding(0, 12, 0, 4)
        })
        root.addView(switchRow("พิมพ์ออเดอร์ LINE MAN", Prefs.isLinemanOn(this)) {
            Prefs.setLineman(this, it)
        })
        root.addView(switchRow("พิมพ์ออเดอร์ Grab", Prefs.isGrabOn(this)) {
            Prefs.setGrab(this, it)
        })
        root.addView(switchRow("พิมพ์ออเดอร์ Shopee (เร็วๆ นี้)", Prefs.isShopeeOn(this)) {
            Prefs.setShopee(this, it)
        })
        root.addView(space(16))

        root.addView(button("ทดสอบพิมพ์") { testPrint() })
        root.addView(space(8))
        root.addView(button("พิมพ์ออเดอร์ล่าสุดซ้ำ") { reprint() })

        setContentView(ScrollView(this).apply { addView(root) })

        requestBtPermission()
    }

    override fun onResume() {
        super.onResume()
        loadDevices()
        refreshStatus()
    }

    private fun refreshStatus() {
        val accOn = isAccessibilityOn()
        val printer = Prefs.getPrinterName(this) ?: "ยังไม่ได้เลือก"
        status.text = "สถานะ\n• Accessibility: ${if (accOn) "เปิดอยู่ ✓" else "ปิดอยู่ ✗"}\n• เครื่องพิมพ์: $printer"
    }

    private fun isAccessibilityOn(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = "$packageName/$packageName.OrderAccessibilityService"
        val split = TextUtils.SimpleStringSplitter(':')
        split.setString(flat)
        while (split.hasNext()) if (split.next().equals(me, true)) return true
        return false
    }

    @SuppressLint("MissingPermission")
    private fun loadDevices() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) { toast("เครื่องนี้ไม่มีบลูทูธ"); return }
        if (!hasBtPermission()) return
        devices = try {
            adapter.bondedDevices.map { (it.name ?: it.address) to it.address }
        } catch (e: Exception) { emptyList() }
        val labels = if (devices.isEmpty()) listOf("— ไม่พบเครื่องที่จับคู่ —")
        else devices.map { it.first }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        Prefs.getPrinterMac(this)?.let { saved ->
            val idx = devices.indexOfFirst { it.second == saved }
            if (idx >= 0) spinner.setSelection(idx)
        }
    }

    private fun savePrinter() {
        if (devices.isEmpty()) { toast("ยังไม่มีเครื่องที่จับคู่"); return }
        val pos = spinner.selectedItemPosition
        if (pos < 0 || pos >= devices.size) return
        val (name, mac) = devices[pos]
        Prefs.setPrinter(this, mac, name)
        toast("บันทึกแล้ว: $name")
        refreshStatus()
    }

private fun testPrint() {
        val sample = Order(
            orderNo = "9999",
            lmfCode = "",
            branch = "",
            dateTime = "",
            customer = "ลูกค้าตัวอย่าง",
            isNewCustomer = true,
            items = listOf(
                OrderItem(1, "เมนูตัวอย่าง A", "100.00",
                    mutableListOf("ขนาด: กลาง")),
                OrderItem(2, "เมนูตัวอย่าง B", "50.00")
            ),
            note = "ทดสอบพิมพ์",
            payment = "",
            subtotal = "200.00",
            discount = "",
            net = "200.00",
            parsedOk = true
        )
        printInBg(sample)
    }
    
    private fun reprint() {
        val o = LastOrder.value
        if (o == null) { toast("ยังไม่มีออเดอร์ล่าสุด"); return }
        printInBg(o)
    }

    private fun printInBg(order: Order) {
        if (Prefs.getPrinterMac(this) == null) { toast("เลือกเครื่องพิมพ์ก่อน"); return }
        toast("กำลังพิมพ์...")
        Thread {
            try {
                val bmp = ReceiptRenderer.render(applicationContext, order)
                BluetoothPrinter.printBitmap(applicationContext, bmp)
                runOnUiThread { toast("พิมพ์เสร็จ") }
            } catch (e: Exception) {
                runOnUiThread { toast("พิมพ์ไม่ออก: ${e.message}") }
            }
        }.start()
    }

    // ---------- helpers ----------
    private fun hasBtPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun requestBtPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBtPermission()) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        loadDevices()
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun switchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        Switch(this).apply {
            text = label
            textSize = 15f
            isChecked = checked
            setPadding(0, 20, 0, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnCheckedChangeListener { _, isOn -> onChange(isOn) }
        }

    private fun space(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
