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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    // Taxas: 125, 250, 500, 1000 Hz -> byte do mouse.
    val RATE_HZ = intArrayOf(125, 250, 500, 1000)
    private val RATE_BYTE = intArrayOf(0x08, 0x04, 0x02, 0x01)

    /** Relatório 0x04 (DPI): 52 bytes no cabo, 56 bytes no dongle. */
    fun buildDpi(dpis: IntArray, active: Int, wireless: Boolean): ByteArray {
        val size = if (wireless) 56 else 52
        val a = IntArray(size)
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
        return ByteArray(size) { a[it].toByte() }
    }

    /** Relatório 0x05 (luz contínua), experimental: 13 bytes no cabo, 15 no dongle. */
    fun buildLight(wireless: Boolean): ByteArray {
        val size = if (wireless) 15 else 13
        val a = IntArray(size)
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
        return ByteArray(size) { a[it].toByte() }
    }

    /** Relatório 0x06 (taxa de atualização): 06 09 01 [taxa] [0xff - taxa] 00 00 00 00 */
    fun buildPolling(idx: Int): ByteArray {
        val r = RATE_BYTE[idx]
        val a = intArrayOf(0x06, 0x09, 0x01, r, 0xff - r, 0, 0, 0, 0)
        return ByteArray(9) { a[it].toByte() }
    }
}

class MainActivity : Activity() {

    private val actionPerm = "com.sharkdpi.USB_PERMISSION"
    private val defaults = intArrayOf(800, 1600, 2400, 3200, 4000, 5000)
    private val defaultActive = 1 // posição 1 = nível 2

    private val PID_CABLE = 0xfa55
    private val PID_DONGLE = 0xfa60

    private lateinit var usb: UsbManager
    private var device: UsbDevice? = null
    private val prefs by lazy { getSharedPreferences("shark", MODE_PRIVATE) }

    // Escuta do mouse
    private var conn: UsbDeviceConnection? = null
    private var iface: UsbInterface? = null
    private var listenThread: Thread? = null
    @Volatile private var listening = false
    @Volatile private var ackCount = 0
    @Volatile private var lastAck = -1
    private var unknownLogged = 0

    private lateinit var logView: TextView
    private lateinit var modeView: TextView
    private lateinit var stageView: TextView
    private lateinit var batteryView: TextView
    private lateinit var pollView: TextView
    private lateinit var stageSpinner: Spinner
    private lateinit var pollSpinner: Spinner
    private lateinit var lightCheck: CheckBox
    private lateinit var saveBtn: Button
    private lateinit var pollBtn: Button
    private val dpiInputs = ArrayList<EditText>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                actionPerm -> {
                    val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        log("Permissão concedida.")
                        device?.let { startListening(it) }
                    } else {
                        log("Permissão negada.")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val d = usbDeviceFrom(i)
                    if (d != null && d.vendorId == 0x1d57) {
                        stopListening()
                        device = null
                        log("Mouse desconectado.")
                        resetStatus()
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun usbDeviceFrom(i: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager

        val filter = IntentFilter(actionPerm)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
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
        root.addView(text("Attack Shark X11. DPI: salva pelo cabo ou pelo dongle. Taxa: só pelo dongle."))

        val connectBtn = Button(this)
        connectBtn.text = "1. Conectar ao mouse"
        connectBtn.setOnClickListener { connect() }
        root.addView(connectBtn)

        modeView = text("Conexão: —", 15f)
        stageView = text("Nível ativo no mouse: —", 18f)
        batteryView = text("Bateria do mouse: —", 18f)
        root.addView(modeView)
        root.addView(stageView)
        root.addView(batteryView)

        root.addView(text("DPI de 50 a 5000 (passos de 50)."))

        for (i in 0 until 6) {
            root.addView(text("Nível ${i + 1}"))
            val e = EditText(this)
            e.inputType = InputType.TYPE_CLASS_NUMBER
            e.setText(prefs.getInt("dpi$i", defaults[i]).toString())
            dpiInputs.add(e)
            root.addView(e)
        }

        root.addView(text("Nível ativo (para salvar)"))
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
        saveBtn.text = "2. Salvar DPI no mouse"
        saveBtn.setOnClickListener { save() }
        root.addView(saveBtn)

        root.addView(text("Taxa de atualização (só dongle)", 18f))
        pollView = text("", 14f)
        root.addView(pollView)
        refreshPollView()

        pollSpinner = Spinner(this)
        pollSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            Protocol.RATE_HZ.map { "$it Hz" }
        )
        pollSpinner.setSelection(prefs.getInt("pollIdx", 3))
        root.addView(pollSpinner)

        pollBtn = Button(this)
        pollBtn.text = "3. Enviar taxa para o mouse"
        pollBtn.setOnClickListener { sendPolling() }
        root.addView(pollBtn)

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
        stopListening()
        unregisterReceiver(receiver)
    }

    private fun log(msg: String) {
        runOnUiThread {
            logView.append(msg + "\n")
            prefs.edit().putString("log", logView.text.toString().takeLast(4000)).apply()
        }
    }

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it) }

    private fun modeName(d: UsbDevice) = when (d.productId) {
        PID_CABLE -> "cabo"
        PID_DONGLE -> "dongle"
        else -> "desconhecido"
    }

    private fun resetStatus() {
        runOnUiThread {
            modeView.text = "Conexão: —"
            stageView.text = "Nível ativo no mouse: —"
            batteryView.text = "Bateria do mouse: —"
        }
    }

    private fun refreshPollView() {
        val hz = prefs.getInt("pollHz", 0)
        pollView.text = if (hz > 0) {
            "Último envio do app: $hz Hz (o mouse não informa a taxa atual)"
        } else {
            "O mouse não informa a taxa atual e o app ainda não enviou nenhuma."
        }
    }

    private fun setBusy(b: Boolean) {
        runOnUiThread {
            saveBtn.isEnabled = !b
            pollBtn.isEnabled = !b
        }
    }

    /** Só preenche os campos. Para valer no mouse, toque em "Salvar DPI no mouse". */
    private fun fillDefaults() {
        for (i in 0 until 6) dpiInputs[i].setText(defaults[i].toString())
        stageSpinner.setSelection(defaultActive)
        log("Campos preenchidos com os padrões. Toque em \"Salvar DPI no mouse\" para aplicar.")
    }

    private fun connect() {
        val all = usb.deviceList.values
        if (all.isEmpty()) {
            log("Nenhum aparelho USB visto. Ligue o cabo USB-C ou o dongle (com adaptador) no celular.")
            return
        }
        log("USB visto: " + all.joinToString { "%04x:%04x".format(it.vendorId, it.productId) })
        val shark = all.filter { it.vendorId == 0x1d57 }
        if (shark.isEmpty()) {
            log("Nenhum aparelho com ID 1d57 (Attack Shark).")
            return
        }
        val d = shark.firstOrNull { it.productId == PID_CABLE }
            ?: shark.firstOrNull { it.productId == PID_DONGLE }
        if (d == null) {
            log("Este mouse aparece como 1d57:%04x e eu só estudei o fa55 e o fa60. Não vou usar. Me mande esse número.".format(shark[0].productId))
            return
        }
        device = d
        if (usb.hasPermission(d)) {
            startListening(d)
        } else {
            val intent = Intent(actionPerm).setPackage(packageName)
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            usb.requestPermission(d, PendingIntent.getBroadcast(this, 0, intent, flags))
            log("Aparelho encontrado. Aceite o pedido de permissão na tela.")
        }
    }

    // ---------- Escuta ----------

    private fun startListening(d: UsbDevice) {
        stopListening()
        val itf = (0 until d.interfaceCount).map { d.getInterface(it) }.firstOrNull { it.id == 2 }
        if (itf == null) {
            log("Interface 2 não encontrada no mouse.")
            return
        }
        val ep = (0 until itf.endpointCount).map { itf.getEndpoint(it) }.firstOrNull { it.address == 0x83 }
        if (ep == null) {
            log("Canal de avisos (0x83) não encontrado.")
            return
        }
        val c = usb.openDevice(d)
        if (c == null) {
            log("Não consegui abrir o mouse.")
            return
        }
        if (!c.claimInterface(itf, true)) {
            log("Não consegui assumir a interface 2.")
            c.close()
            return
        }
        conn = c
        iface = itf
        unknownLogged = 0
        listening = true
        listenThread = Thread { listenLoop(c, ep) }.also { it.start() }

        log("Escutando o mouse (${modeName(d)}).")
        runOnUiThread {
            modeView.text = "Conexão: ${modeName(d)} (%04x)".format(d.productId)
            stageView.text = "Nível ativo no mouse: aperte o botão de DPI"
            batteryView.text = if (d.productId == PID_CABLE) {
                "Bateria do mouse: sem dado pelo cabo"
            } else {
                "Bateria do mouse: esperando o dongle avisar…"
            }
        }
    }

    private fun stopListening() {
        listening = false
        try {
            listenThread?.join(800)
        } catch (e: Exception) {
        }
        listenThread = null
        val c = conn
        val itf = iface
        if (c != null) {
            try {
                if (itf != null) c.releaseInterface(itf)
            } catch (e: Exception) {
            }
            c.close()
        }
        conn = null
        iface = null
    }

    private fun listenLoop(c: UsbDeviceConnection, ep: UsbEndpoint) {
        val buf = ByteArray(maxOf(ep.maxPacketSize, 8))
        while (listening) {
            val n = c.bulkTransfer(ep, buf, buf.size, 500)
            if (n > 0) {
                handlePacket(buf.copyOf(n))
            } else if (n < 0) {
                try {
                    Thread.sleep(30)
                } catch (e: InterruptedException) {
                }
            }
        }
    }

    private fun handlePacket(p: ByteArray) {
        val b = IntArray(p.size) { p[it].toInt() and 0xff }
        if (b.size >= 5 && b[0] == 0x03 && b[1] == 0x55) {
            when (b[2]) {
                0x40 -> { // bateria
                    val pct = b[4]
                    if (pct in 0..100) showBattery(pct) else logUnknown(p)
                }
                0x10 -> { // botão de DPI
                    val s = b[3]
                    if (s in 1..6) showStage(s) else logUnknown(p)
                }
                0x50 -> { // confirmação de comando recebido
                    lastAck = b[4]
                    ackCount++
                }
                else -> logUnknown(p)
            }
        } else {
            logUnknown(p)
        }
    }

    private fun logUnknown(p: ByteArray) {
        if (unknownLogged < 15) {
            unknownLogged++
            log("  pacote desconhecido: " + hex(p))
        }
    }

    private fun showStage(s: Int) {
        runOnUiThread {
            val key = "dpi${s - 1}"
            val extra = if (prefs.contains(key)) "  (DPI pelo bilhete: ${prefs.getInt(key, 0)})" else ""
            stageView.text = "Nível ativo no mouse: $s$extra"
            // O seletor "para salvar" acompanha o nível do mouse.
            stageSpinner.setSelection(s - 1)
        }
    }

    private fun showBattery(pct: Int) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        runOnUiThread {
            batteryView.text = "Bateria do mouse: $pct%  (às $t)"
        }
    }

    // ---------- Envio ----------

    /** Envia um relatório e espera a confirmação do mouse. Devolve true se o envio foi aceito pelo USB. */
    private fun sendReport(report: ByteArray, wValue: Int, label: String): Boolean {
        val c = conn
        if (c == null) {
            log("Sem conexão com o mouse. Toque em \"Conectar ao mouse\".")
            return false
        }
        val before = ackCount
        log("Enviando $label (${report.size} bytes): ${hex(report)}")
        val r = c.controlTransfer(0x21, 0x09, wValue, 2, report, report.size, 3000)
        if (r != report.size) {
            log("Envio falhou (código $r).")
            return false
        }
        log("Envio OK.")
        var waited = 0
        while (ackCount == before && waited < 2000) {
            Thread.sleep(50)
            waited += 50
        }
        if (ackCount != before) {
            log("O mouse confirmou o recebimento (código %02x).".format(lastAck))
        } else {
            log("Sem confirmação do mouse (isso não prova que falhou).")
        }
        return true
    }

    private fun save() {
        val d = device
        if (d == null || !usb.hasPermission(d) || conn == null) {
            log("Primeiro toque em \"Conectar ao mouse\".")
            return
        }
        val wireless = d.productId == PID_DONGLE
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

        val report = Protocol.buildDpi(dpis, active, wireless)
        val light = if (lightCheck.isChecked) Protocol.buildLight(wireless) else null

        setBusy(true)
        Thread {
            try {
                if (sendReport(report, 0x0304, "DPI") && light != null) {
                    Thread.sleep(300)
                    sendReport(light, 0x0305, "luz")
                }
            } catch (e: Exception) {
                log("Erro: ${e.message}")
            }
            setBusy(false)
        }.start()
    }

    private fun sendPolling() {
        val d = device
        if (d == null || !usb.hasPermission(d) || conn == null) {
            log("Primeiro toque em \"Conectar ao mouse\".")
            return
        }
        if (d.productId != PID_DONGLE) {
            log("A taxa só é enviada pelo dongle. Pelo cabo o mouse fica em 125 Hz.")
            return
        }
        val idx = pollSpinner.selectedItemPosition
        val hz = Protocol.RATE_HZ[idx]
        val report = Protocol.buildPolling(idx)

        setBusy(true)
        Thread {
            try {
                if (sendReport(report, 0x0306, "taxa $hz Hz")) {
                    prefs.edit().putInt("pollHz", hz).putInt("pollIdx", idx).apply()
                    runOnUiThread { refreshPollView() }
                }
            } catch (e: Exception) {
                log("Erro: ${e.message}")
            }
            setBusy(false)
        }.start()
    }
}
