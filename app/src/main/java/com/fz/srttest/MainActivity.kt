package com.fz.srttest

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
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
 * SRT 画质测试：全量照 silu 的推流方式（前台服务推流、摄像头直写编码器、OTG 一次拷贝 + FrameGate、
 * 硬件 H.264 VBR+QP 上限 → MPEG-TS → SRT），推到 MediaMTX，PC 用浏览器/VLC 观看，
 * 与正式版（WebRTC）对比画质与发热。界面只负责参数和预览，推流在 [StreamService] 里跑，锁屏/切后台不停。
 */
class MainActivity : Activity(), SurfaceHolder.Callback {
    companion object {
        private const val REQ_PERMS = 1
        private val SOURCES = listOf("手机自带摄像头", "外接OTG摄像头")
        private val RESOLUTIONS = listOf(
            "1280x720", "1920x1080", "2560x1440", "3840x2160", "1440x1080", "1024x768", "640x480")
        /** 选分辨率时自动填的码率：以 silu 默认 720p=8000 为基准按像素量递增，4K 封顶 20000（silu 上限） */
        private val AUTO_BITRATE = mapOf(
            "640x480" to 3000, "1024x768" to 5000, "1280x720" to 8000, "1440x1080" to 10000,
            "1920x1080" to 12000, "2560x1440" to 16000, "3840x2160" to 20000,
        )
        private val FPS = listOf("30", "25", "60")
        private val MODES = listOf("VBR", "CBR")
        private val PROFILES = listOf("High", "Main", "Baseline")
        private val RANGES = listOf("Full（同silu）", "Limited", "不设")
    }

    private lateinit var preview: AutoFitSurfaceView
    private lateinit var statsView: TextView
    private lateinit var form: LinearLayout
    private lateinit var startButton: Button
    private lateinit var urlView: TextView

    private lateinit var sourceSpinner: Spinner
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
    private lateinit var nv12Check: CheckBox
    private lateinit var rangeSpinner: Spinner

    private val ui = Handler(Looper.getMainLooper())
    /** 预览要固定的尺寸（= 推流实际尺寸）；摄像头要求预览 Surface 尺寸是它支持的输出尺寸 */
    private var previewTarget: Pair<Int, Int>? = null
    private var holderSize: Pair<Int, Int>? = null

    private val statsTick = object : Runnable {
        override fun run() {
            val s = StreamService.session
            if (s != null) {
                statsView.text = s.statsText()
                val (pw, ph) = s.previewSize
                syncPreviewSize(pw, ph)
            }
            setStreamingUi(s != null)
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        fillForm(StreamConfig.load(this))
        bindAutoBitrate()
        preview.holder.addCallback(this)
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(statsTick)
        ui.post(statsTick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(statsTick)
        // 不停推流：推流在前台服务里，界面不可见时预览随 surfaceDestroyed 自动撤掉
    }

    // ---------------- 预览 Surface ----------------

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        holderSize = width to height
        registerPreviewIfReady()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        holderSize = null
        PreviewRegistry.set(null)
    }

    private fun syncPreviewSize(w: Int, h: Int) {
        if (previewTarget == w to h) return
        previewTarget = w to h
        preview.setAspectRatio(w, h)
        preview.holder.setFixedSize(w, h)
        registerPreviewIfReady()
    }

    /** Surface 尺寸已等于推流尺寸才登记（否则摄像头会话配置会失败） */
    private fun registerPreviewIfReady() {
        val t = previewTarget ?: return
        if (holderSize == t) PreviewRegistry.set(preview.holder.surface)
    }

    // ---------------- 推流控制 ----------------

    private fun onStartStopClicked() {
        if (StreamService.session != null) {
            StreamService.stop(this)
            statsView.text = "已停止"
            setStreamingUi(false)
            return
        }
        val need = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
            return
        }
        startStream()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startStream()
        } else {
            toast("需要摄像头权限（OTG 外接摄像头同样需要）")
        }
    }

    private fun startStream() {
        val cfg = readForm() ?: return
        if (cfg.host.isBlank()) {
            toast("请填写服务器地址")
            return
        }
        cfg.save(this)
        previewTarget = null
        if (!cfg.isUvc) {
            // 自带摄像头：先把预览固定到摄像头可用尺寸，服务起会话时预览 Surface 已就绪
            CameraSource.backCameraId(this)?.let { id ->
                val size = CameraSource.chooseSize(this, id, cfg.width, cfg.height)
                if (size.width != cfg.width || size.height != cfg.height) {
                    toast("该摄像头不支持 ${cfg.width}x${cfg.height}，改用 ${size.width}x${size.height}")
                }
                val p = CameraSource.choosePreviewSize(this, id, size.width, size.height)
                syncPreviewSize(p.width, p.height)
            }
        } else {
            toast("请插上外接摄像头并允许 USB 访问")
        }
        StreamService.start(this)
        urlView.text = "PC 观看（推流开始几秒后可看）：\n浏览器  ${cfg.webPlayUrl}\nVLC/ffplay  ${cfg.srtPlayUrl}\n" +
                "可直接锁屏，推流在后台继续（照 silu，最省电）"
        setStreamingUi(true)
    }

    private fun setStreamingUi(streaming: Boolean) {
        setFormEnabled(!streaming)
        startButton.text = if (streaming) "停止推流" else "开始推流"
    }

    // ---------------- 表单 ----------------

    /** 用户换分辨率时自动填码率（仍可手动改）；Spinner 初始化时的那次回调不算，免得覆盖已保存的码率 */
    private fun bindAutoBitrate() {
        var lastPos = resSpinner.selectedItemPosition
        resSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (pos == lastPos) return
                lastPos = pos
                AUTO_BITRATE[RESOLUTIONS[pos]]?.let { bitrateEdit.setText(it.toString()) }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

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
            source = if (sourceSpinner.selectedItemPosition == 1) StreamConfig.SOURCE_UVC else StreamConfig.SOURCE_CAMERA,
            uvcNv12 = nv12Check.isChecked,
        )
    }

    private fun fillForm(c: StreamConfig) {
        sourceSpinner.setSelection(if (c.isUvc) 1 else 0)
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
        nv12Check.isChecked = c.uvcNv12
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
        preview = AutoFitSurfaceView(this)
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

        section("采集源")
        sourceSpinner = spinner("摄像头", SOURCES)
        nv12Check = CheckBox(this).apply {
            text = "OTG 色度按 NV12（颜色发蓝/发紫就取消勾选）"
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        form.addView(LinearLayout(this).apply { addView(nv12Check) })

        section("服务器（MediaMTX）")
        hostEdit = edit("服务器 IP", "如 103.80.16.160", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
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
