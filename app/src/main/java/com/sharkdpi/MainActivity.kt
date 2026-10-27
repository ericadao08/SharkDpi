package com.sharkdpi

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

/** Montagem dos pacotes do mouse (baseado no PROTOCOL_EN.md do OpenSharkX11). */
object Protocol {
    // Se o mouse não confirmar, a primeira coisa a testar é trocar este valor para 0x20.
    private const val STAGE_MASK_HIGH = 0x00

    // Tabela do sensor PAW3311: índice = (DPI - 50) / 50. Vai até 5000 DPI.
    private val TABLE = intArrayOf(
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0e, 0x0f, 0x10, 0x11, 0x12,
        0x13, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20, 0x22, 0x23, 0x24, 0x25,
        0x26, 0x27, 0x29, 0x2a, 0x2b, 0x2c, 0x2d, 0x2f, 0x30, 0x31, 0x32, 0x33, 0x34, 0x36, 0x37, 0x38,
        0x39, 0x3a, 0x3b, 0x3d, 0x3e, 0x3f, 0x40, 0x41, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x4a, 0x4b,
        0x4c, 0x4d, 0x4e, 0x4f, 0x51, 0x52, 0x53, 0x54, 0x55, 0x57, 0x58, 0x59, 0x5a, 0x5b, 0x5c, 0x5e,
        0x5f, 0x60, 0x61, 0x62, 0x63, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6b, 0x6c, 0x6d, 0x6e, 0x6f, 0x70,
        0x72, 0x73, 0x74, 0x75
    )

    // Cores padrão dos 6 níveis + 2 extras (bytes 25 a 48).
    private val COLORS = intArrayOf(
        0xff, 0x00, 0x00,  0x00, 0xff, 0x00,  0x00, 0x00, 0xff,
        0xff, 0xff, 0x00,  0x00, 0xff, 0xff,  0xff, 0x00, 0xff,
        0xff, 0x40, 0x00,  0xff, 0xff, 0xff
    )

    /** Relatório 0x04 (DPI), 52 bytes (versão com cabo). */
    fun buildDpi(dpis: IntArray, active: Int): ByteArray {
        val a = IntArray(52)
        a[0] = 0x04; a[1] = 0x38; a[2] = 0x01
        a[3] = 0x00 // angle snap desligado
        a[4] = 0x01 // ripple ligado
        a[5] = 0x3f
        a[6] = STAGE_MASK_HIGH
        a[7] = 0x20
        for (i in 0 until 6) a[8 + i] = TABLE[(dpis[i] - 50) / 50]
        a[24] = active
        for (i in COLORS.indices) a[25 + i] = COLORS[i]
        a[49] = 0x01 // animação de confirmação: pisca 3 vezes
        var sum = 0
        for (i in 3..49) sum += a[i]
        a[50] = (sum shr 8) and 0xff
        a[51] = sum and 0xff
        return ByteArray(52) { a[it].toByte() }
    }

    /** Relatório 0x05 (luz contínua), experimental. */
    fun buildLight(): ByteArray {
        val a = IntArray(13)
        a[0] = 0x05; a[1] = 0x0f; a[2] = 0x01
        a[3] = 0x50 // StaticDpi: luz com a cor do nível ativo
        a[4] = 3    // velocidade
        a[5] = 0xa8
        a[6] = 0xff; a[7] = 0xff; a[8] = 0xff
        a[9] = 0x01
        a[10] = 8   // debounce em ms
        a[11] = 3
        var s = 0
        for (i in 3..10) s += a[i]
        a[12] = s and 0xff
        return ByteArray(13) { a[it].toByte() }
    }
}

class MainActivity : Activity() {

    private val actionPerm = "com.sharkdpi.USB_PERMISSION"
    private val defaults = intArrayOf(800, 1600, 2400, 3200, 4000, 5000)
    private val defaultActive = 1 // posição 1 = nível 2

    private lateinit var usb: UsbManager
    private var device: UsbDevice? = null
    private val prefs by lazy { getSharedPreferences("shark", MODE_PRIVATE) }

    private lateinit var logView: TextView
    private lateinit var stageSpinner: Spinner
    private lateinit var lightCheck: CheckBox
    private lateinit var saveBtn: Button
    private val dpiInputs = ArrayList<EditText>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == actionPerm) {
                val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                log(if (granted) "Permissão concedida. Pode salvar." else "Permissão negada.")
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager

        val filter = IntentFilter(actionPerm)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        fun text(t: String, size: Float = 16f): TextView {
            val v = TextView(this)
            v.text = t
            v.textSize = size
            return v
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(40), dp(16), dp(24))

        root.addView(text("Shark DPI", 24f))
        root.addView(text("Attack Shark X11, pelo cabo USB-C. DPI de 50 a 5000 (passos de 50)."))

        val connectBtn = Button(this)
        connectBtn.text = "1. Conectar ao mouse"
        connectBtn.setOnClickListener { connect() }
        root.addView(connectBtn)

        for (i in 0 until 6) {
            root.addView(text("Nível ${i + 1}"))
            val e = EditText(this)
            e.inputType = InputType.TYPE_CLASS_NUMBER
            e.setText(prefs.getInt("dpi$i", defaults[i]).toString())
            dpiInputs.add(e)
            root.addView(e)
        }

        root.addView(text("Nível ativo"))
        stageSpinner = Spinner(this)
        stageSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("1", "2", "3", "4", "5", "6")
        )
        stageSpinner.setSelection(prefs.getInt("active", defaultActive))
        root.addView(stageSpinner)

        val resetBtn = Button(this)
        resetBtn.text = "Preencher com os padrões"
        resetBtn.setOnClickListener { fillDefaults() }
        root.addView(resetBtn)

        lightCheck = CheckBox(this)
        lightCheck.text = "Reacender a luz depois (experimental)"
        lightCheck.isChecked = prefs.getBoolean("light", false)
        root.addView(lightCheck)

        saveBtn = Button(this)
        saveBtn.text = "2. Salvar no mouse"
        saveBtn.setOnClickListener { save() }
        root.addView(saveBtn)

        logView = TextView(this)
        logView.textSize = 12f
        logView.typeface = Typeface.MONOSPACE
        logView.setTextIsSelectable(true)
        logView.text = prefs.getString("log", "") ?: ""
        root.addView(logView)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    private fun log(msg: String) {
        runOnUiThread {
            logView.append(msg + "\n")
            prefs.edit().putString("log", logView.text.toString().takeLast(4000)).apply()
        }
    }

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it) }

    /** Só preenche os campos. Para valer no mouse, toque em "Salvar no mouse". */
    private fun fillDefaults() {
        for (i in 0 until 6) dpiInputs[i].setText(defaults[i].toString())
        stageSpinner.setSelection(defaultActive)
        log("Campos preenchidos com os padrões. Toque em \"Salvar no mouse\" para aplicar.")
    }

    private fun connect() {
        val all = usb.deviceList.values
        if (all.isEmpty()) {
            log("Nenhum aparelho USB visto. Use um cabo USB-C nas duas pontas, direto no celular.")
            return
        }
        log("USB visto: " + all.joinToString { "%04x:%04x".format(it.vendorId, it.productId) })
        val d = all.firstOrNull { it.vendorId == 0x1d57 }
        if (d == null) {
            log("Nenhum aparelho com ID 1d57 (Attack Shark).")
            return
        }
        if (d.productId != 0xfa55) {
            log("Este mouse aparece como 1d57:%04x e eu só estudei o fa55. Não vou enviar nada. Me mande esse número.".format(d.productId))
            return
        }
        device = d
        if (usb.hasPermission(d)) {
            log("Mouse encontrado e já autorizado. Pode salvar.")
        } else {
            val intent = Intent(actionPerm).setPackage(packageName)
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            usb.requestPermission(d, PendingIntent.getBroadcast(this, 0, intent, flags))
            log("Mouse encontrado. Aceite o pedido de permissão na tela.")
        }
    }

    private fun save() {
        val d = device
        if (d == null || !usb.hasPermission(d)) {
            log("Primeiro toque em \"Conectar ao mouse\".")
            return
        }
        val dpis = IntArray(6)
        for (i in 0 until 6) {
            val v = dpiInputs[i].text.toString().trim().toIntOrNull()
            if (v == null || v < 50 || v > 5000) {
                log("Nível ${i + 1}: use um número de 50 a 5000.")
                return
            }
            dpis[i] = ((v + 25) / 50) * 50
            dpiInputs[i].setText(dpis[i].toString())
        }
        val active = stageSpinner.selectedItemPosition + 1

        // O "bilhete": guarda no celular o que foi salvo.
        val ed = prefs.edit()
        for (i in 0 until 6) ed.putInt("dpi$i", dpis[i])
        ed.putInt("active", stageSpinner.selectedItemPosition)
        ed.putBoolean("light", lightCheck.isChecked)
        ed.apply()

        val report = Protocol.buildDpi(dpis, active)
        val light = if (lightCheck.isChecked) Protocol.buildLight() else null

        saveBtn.isEnabled = false
        Thread {
            try {
                sendAll(d, report, light)
            } catch (e: Exception) {
                log("Erro: ${e.message}")
            }
            runOnUiThread { saveBtn.isEnabled = true }
        }.start()
    }

    private fun sendAll(d: UsbDevice, report: ByteArray, light: ByteArray?) {
        var iface: UsbInterface? = null
        for (i in 0 until d.interfaceCount) {
            if (d.getInterface(i).id == 2) iface = d.getInterface(i)
        }
        if (iface == null) {
            log("Interface 2 não encontrada no mouse.")
            return
        }
        val conn = usb.openDevice(d)
        if (conn == null) {
            log("Não consegui abrir o mouse.")
            return
        }
        try {
            if (!conn.claimInterface(iface, true)) {
                log("Não consegui assumir a interface 2.")
                return
            }
            log("Enviando DPI (${report.size} bytes): ${hex(report)}")
            val r = conn.controlTransfer(0x21, 0x09, 0x0304, 2, report, report.size, 3000)
            if (r != report.size) {
                log("Envio falhou (código $r).")
                return
            }
            log("Envio OK.")
            readAck(conn, iface)

            if (light != null) {
                Thread.sleep(300)
                log("Enviando luz (${light.size} bytes): ${hex(light)}")
                val r2 = conn.controlTransfer(0x21, 0x09, 0x0305, 2, light, light.size, 3000)
                log(if (r2 == light.size) "Luz enviada." else "Luz falhou (código $r2).")
            }
        } finally {
            conn.releaseInterface(iface)
            conn.close()
        }
    }

    private fun readAck(conn: UsbDeviceConnection, iface: UsbInterface) {
        var ep: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val e = iface.getEndpoint(i)
            if (e.address == 0x83) ep = e
        }
        if (ep == null) {
            log("Sem canal de resposta (0x83).")
            return
        }
        val buf = ByteArray(maxOf(ep.maxPacketSize, 8))
        var ok = false
        for (k in 0 until 6) {
            val n = conn.bulkTransfer(ep, buf, buf.size, 700)
            if (n <= 0) continue
            log("  resposta: " + hex(buf.copyOf(n)))
            if (n >= 3 && buf[1] == 0x55.toByte() && buf[2] == 0x50.toByte()) {
                ok = true
                break
            }
        }
        log(if (ok) "O mouse confirmou o recebimento." else "Sem confirmação do mouse (isso não prova que falhou).")
    }
}
