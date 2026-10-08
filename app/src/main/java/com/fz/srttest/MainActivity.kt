package com.fz.srttest

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/**
 * SRT 画质测试：按竞品 silu 的链路（Camera2 录像模板 → 硬件 H.264 VBR+QP 上限 → MPEG-TS → SRT）
 * 推到 MediaMTX，PC 用浏览器 / VLC 观看，与正式版 WebRTC 链路做画质对比。
 */
class MainActivity : Activity() {
    companion object {
        private const val REQ_CAMERA = 1
        private val RESOLUTIONS = listOf(
            "1280x720", "1920x1080", "2560x1440", "3840x2160", "1440x1080", "1024x768", "640x480")
        private val FPS = listOf("30", "25", "60")
        private val MODES = listOf("VBR", "CBR")
        private val PROFILES = listOf("High", "Main", "Baseline")
        private val RANGES = listOf("Full（同silu）", "Limited", "不设")
    }

    private lateinit var preview: AutoFitTextureView
    private lateinit var statsView: TextView
    private lateinit var form: LinearLayout
    private lateinit var startButton: Button
    private lateinit var urlView: TextView

    private lateinit var hostEdit: EditText
    private lateinit var portEdit: EditText
    private lateinit var pathEdit: EditText
    private lateinit var userEdit: EditText
    private lateinit var passEdit: EditText
    private lateinit var resSpinner: Spinner
    private lateinit var fpsSpinner: Spinner
    private lateinit var bitrateEdit: EditText
    private lateinit var qpEdit: EditText
    private lateinit var modeSpinner: Spinner
    private lateinit var profileSpinner: Spinner
    private lateinit var gopEdit: EditText
    private lateinit var latencyEdit: EditText
    private lateinit var stabCheck: CheckBox
    private lateinit var rangeSpinner: Spinner

    private var session: StreamSession? = null
    private var previewSurface: Surface? = null
    private var previewSize = 1280 to 720
    private val ui = Handler(Looper.getMainLooper())

    private val statsTick = object : Runnable {
        override fun run() {
            session?.let { statsView.text = it.statsText() }
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        fillForm(StreamConfig.load(this))
    }

    override fun onStop() {
        super.onStop()
        stopStream()
    }

    // ---------------- 推流控制 ----------------

    private fun onStartStopClicked() {
        if (session != null) {
            stopStream()
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        startStream()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startStream()
        } else {
            toast("需要摄像头权限")
        }
    }

    private fun startStream() {
        val cfgInput = readForm() ?: return
        if (cfgInput.host.isBlank()) {
            toast("请填写服务器地址")
            return
        }
        val st = preview.surfaceTexture
        if (st == null) {
            toast("预览还没准备好，稍后再试")
            return
        }
        val cameraId = CameraSource.backCameraId(this) ?: run {
            toast("没有可用的摄像头")
            return
        }
        cfgInput.save(this)
        val size = CameraSource.chooseSize(this, cameraId, cfgInput.width, cfgInput.height)
        val cfg = cfgInput.copy(width = size.width, height = size.height)
        if (size.width != cfgInput.width || size.height != cfgInput.height) {
            toast("该摄像头不支持 ${cfgInput.width}x${cfgInput.height}，改用 ${size.width}x${size.height}")
        }

        previewSize = size.width to size.height
        preview.setAspectRatio(size.width, size.height)
        st.setDefaultBufferSize(size.width, size.height)
        configureTransform(preview.width, preview.height)
        val surface = Surface(st)
        previewSurface = surface

        val s = StreamSession(this, cfg, cameraId, surface) { msg -> ui.post { toast(msg) } }
        try {
            s.start()
        } catch (t: Throwable) {
            toast(t.message ?: "启动失败")
            s.stop()
            surface.release()
            previewSurface = null
            return
        }
        session = s
        urlView.text = "PC 观看（推流开始几秒后可看）：\n浏览器  ${cfg.webPlayUrl}\nVLC/ffplay  ${cfg.srtPlayUrl}"
        setFormEnabled(false)
        startButton.text = "停止推流"
        ui.removeCallbacks(statsTick)
        ui.post(statsTick)
    }

    private fun stopStream() {
        val s = session ?: return
        session = null
        s.stop()
        previewSurface?.release()
        previewSurface = null
        ui.removeCallbacks(statsTick)
        statsView.text = "已停止"
        setFormEnabled(true)
        startButton.text = "开始推流"
    }

    /** 横屏下把摄像头画面转正并铺满（AutoFitTextureView 已保证宽高比一致） */
    @Suppress("DEPRECATION")
    private fun configureTransform(viewW: Int, viewH: Int) {
        if (viewW == 0 || viewH == 0) return
        val (pw, ph) = previewSize
        val rotation = windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewW.toFloat(), viewH.toFloat())
        val bufferRect = RectF(0f, 0f, ph.toFloat(), pw.toFloat())
        val cx = viewRect.centerX()
        val cy = viewRect.centerY()
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = maxOf(viewH.toFloat() / ph, viewW.toFloat() / pw)
            matrix.postScale(scale, scale, cx, cy)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), cx, cy)
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, cx, cy)
        }
        preview.setTransform(matrix)
    }

    // ---------------- 表单 ----------------

    private fun readForm(): StreamConfig? {
        fun int(e: EditText, name: String, min: Int, max: Int): Int? {
            val v = e.text.toString().trim().toIntOrNull()
            if (v == null || v < min || v > max) {
                toast("$name 需在 $min~$max 之间")
                return null
            }
            return v
        }
        val (w, h) = RESOLUTIONS[resSpinner.selectedItemPosition].split("x").map { it.toInt() }
        return StreamConfig(
            host = hostEdit.text.toString().trim(),
            port = int(portEdit, "端口", 1, 65535) ?: return null,
            path = pathEdit.text.toString().trim().ifEmpty { "test1" },
            user = userEdit.text.toString().trim(),
            pass = passEdit.text.toString(),
            width = w,
            height = h,
            fps = FPS[fpsSpinner.selectedItemPosition].toInt(),
            bitrateKbps = int(bitrateEdit, "码率", 300, 50000) ?: return null,
            qpMax = int(qpEdit, "QP上限", 0, 51) ?: return null,
            bitrateMode = if (modeSpinner.selectedItemPosition == 1) 2 else 1,
            profile = when (profileSpinner.selectedItemPosition) {
                1 -> StreamConfig.PROFILE_MAIN
                2 -> StreamConfig.PROFILE_BASELINE
                else -> StreamConfig.PROFILE_HIGH
            },
            keyIntervalSec = int(gopEdit, "关键帧间隔", 1, 10) ?: return null,
            latencyMs = int(latencyEdit, "SRT延迟", 20, 5000) ?: return null,
            disableStabilization = stabCheck.isChecked,
            colorRange = when (rangeSpinner.selectedItemPosition) {
                1 -> StreamConfig.RANGE_LIMITED
                2 -> StreamConfig.RANGE_NONE
                else -> StreamConfig.RANGE_FULL
            },
        )
    }

    private fun fillForm(c: StreamConfig) {
        hostEdit.setText(c.host)
        portEdit.setText(c.port.toString())
        pathEdit.setText(c.path)
        userEdit.setText(c.user)
        passEdit.setText(c.pass)
        resSpinner.setSelection(RESOLUTIONS.indexOf("${c.width}x${c.height}").coerceAtLeast(0))
        fpsSpinner.setSelection(FPS.indexOf(c.fps.toString()).coerceAtLeast(0))
        bitrateEdit.setText(c.bitrateKbps.toString())
        qpEdit.setText(c.qpMax.toString())
        modeSpinner.setSelection(if (c.bitrateMode == 2) 1 else 0)
        profileSpinner.setSelection(when (c.profile) {
            StreamConfig.PROFILE_MAIN -> 1
            StreamConfig.PROFILE_BASELINE -> 2
            else -> 0
        })
        gopEdit.setText(c.keyIntervalSec.toString())
        latencyEdit.setText(c.latencyMs.toString())
        stabCheck.isChecked = c.disableStabilization
        rangeSpinner.setSelection(when (c.colorRange) {
            StreamConfig.RANGE_LIMITED -> 1
            StreamConfig.RANGE_NONE -> 2
            else -> 0
        })
    }

    private fun setFormEnabled(enabled: Boolean) {
        for (i in 0 until form.childCount) {
            val v = form.getChildAt(i)
            if (v is ViewGroup) for (j in 0 until v.childCount) v.getChildAt(j).isEnabled = enabled
        }
    }

    // ---------------- 界面 ----------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
        }

        // 左：预览 + 统计
        val left = FrameLayout(this)
        preview = AutoFitTextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) = configureTransform(w, h)
                override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) = configureTransform(w, h)
                override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
            }
        }
        left.addView(preview, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        statsView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(dp(6), dp(4), dp(6), dp(4))
            text = "填好参数后点「开始推流」"
        }
        left.addView(statsView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        root.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3f))

        // 右：参数
        val scroll = ScrollView(this).apply { setBackgroundColor(0xFF1E1E1E.toInt()) }
        form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(16))
        }
        scroll.addView(form)
        root.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f))

        section("服务器（MediaMTX）")
        hostEdit = edit("服务器 IP", "如 110.42.9.179", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        portEdit = edit("SRT 端口", "8890", InputType.TYPE_CLASS_NUMBER)
        pathEdit = edit("流名", "test1", InputType.TYPE_CLASS_TEXT)
        userEdit = edit("推流账号", "没设可留空", InputType.TYPE_CLASS_TEXT)
        passEdit = edit("推流密码", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)

        section("编码（默认值 = silu）")
        resSpinner = spinner("分辨率", RESOLUTIONS)
        fpsSpinner = spinner("帧率", FPS)
        bitrateEdit = edit("码率 kbps", "8000", InputType.TYPE_CLASS_NUMBER)
        qpEdit = edit("QP 上限（0=不限）", "30", InputType.TYPE_CLASS_NUMBER)
        modeSpinner = spinner("码率模式", MODES)
        profileSpinner = spinner("Profile", PROFILES)
        gopEdit = edit("关键帧间隔 秒", "1", InputType.TYPE_CLASS_NUMBER)
        rangeSpinner = spinner("色彩范围", RANGES)

        section("采集 / 传输")
        stabCheck = CheckBox(this).apply {
            text = "关闭防抖（电子+光学）"
            setTextColor(Color.WHITE)
        }
        form.addView(LinearLayout(this).apply { addView(stabCheck) })
        latencyEdit = edit("SRT 延迟 ms", "50", InputType.TYPE_CLASS_NUMBER)

        startButton = Button(this).apply {
            text = "开始推流"
            setOnClickListener { onStartStopClicked() }
        }
        form.addView(startButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        urlView = TextView(this).apply {
            setTextColor(0xFF9CDCFE.toInt())
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, 0)
        }
        form.addView(urlView)
        form.addView(Button(this).apply {
            text = "复制播放地址"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("play", urlView.text))
                toast("已复制")
            }
        })
        return root
    }

    private fun section(title: String) {
        form.addView(TextView(this).apply {
            text = title
            setTextColor(0xFFFFC857.toInt())
            textSize = 13f
            setPadding(0, dp(10), 0, dp(2))
        })
    }

    private fun row(label: String, field: View) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        r.addView(TextView(this).apply {
            text = label
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 12f
        }, LinearLayout.LayoutParams(dp(118), ViewGroup.LayoutParams.WRAP_CONTENT))
        r.addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        form.addView(r)
    }

    private fun edit(label: String, hint: String, type: Int): EditText {
        val e = EditText(this).apply {
            this.hint = hint
            inputType = type
            textSize = 13f
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF777777.toInt())
            setSingleLine()
        }
        row(label, e)
        return e
    }

    private fun spinner(label: String, items: List<String>): Spinner {
        val s = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        }
        row(label, s)
        return s
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
