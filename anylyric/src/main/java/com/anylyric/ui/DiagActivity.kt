package com.anylyric.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.anylyric.R
import com.anylyric.diag.DiagCode
import com.anylyric.diag.DiagRules
import com.anylyric.diag.Probe
import com.anylyric.session.SessionHub
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 通道探测页 —— 一期的主要交付物。
 *
 * 它回答的不是「哪里坏了」，而是「这个播放器到底暴露了什么」：
 * 元数据键全表、通知字段、标题变化时间线、L0–L2 逐层尝试结果，最后给一条结论。
 *
 * 导出全部在设备内完成：复制到剪贴板 / 保存到下载目录 / 系统分享。
 * **不依赖 adb，不需要连接电脑。**
 */
class DiagActivity : BaseActivity() {

    private lateinit var conclusionText: TextView
    private lateinit var evidenceText: TextView
    private lateinit var reportText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            render()
            // 调试关着时不做任何轮询：这一页本来就没有数据可刷，每秒重建一次报告
            // 只是让车机白转。开关打开后重新进入本页（或在设置里打开再返回）即可恢复。
            if (Probe.enabled) handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
        }

        root.addView(text(getString(R.string.diag_title), 24f, bold = true).apply {
            setPadding(0, 0, 0, dp(12))
        })

        conclusionText = text("", 15f, bold = true)
        evidenceText = text("", 12f, secondary = true).apply {
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(
            card(
                listOf(
                    text(getString(R.string.diag_conclusion), 12f, secondary = true),
                    conclusionText,
                    evidenceText
                )
            )
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(textButton(getString(R.string.diag_share)) { shareReport() })
            addView(textButton(getString(R.string.diag_copy)) { copyReport() })
            addView(textButton(getString(R.string.diag_save)) { saveReportAndToast() })
            addView(textButton(getString(R.string.diag_clear)) {
                Probe.clear()
                render()
            })
        }
        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(actions)
                setPadding(0, 0, 0, dp(8))
            }
        )

        reportText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setTextColor(colorSecondaryText)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(if (isNight) 0xFF161616.toInt() else 0xFFF6F6F6.toInt())
        }

        root.addView(
            ScrollView(this).apply {
                addView(reportText)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }
        )
        return root
    }

    private fun render() {
        if (!Probe.enabled) {
            // 关着调试时整页不采集：连「现在有没有授权」都不去问系统。
            // 与其拿一份永远不更新的旧事实编结论，不如老实说没开。
            conclusionText.setTextColor(colorWarn)
            conclusionText.text = getString(R.string.diag_debug_off)
            evidenceText.text = getString(R.string.diag_debug_off_hint)
            reportText.text = getString(R.string.diag_debug_off_report)
            return
        }

        // 未连接时不跑责任链，快照里的权限字段不会自己刷新；这里每秒同步一次，
        // 免得报告把「已授权未绑定」和「没授权」混为一谈。
        SessionHub.get(this).syncPermissionFacts()

        val diagnosis = DiagRules.evaluate(Probe.snapshot)
        conclusionText.setTextColor(
            when (diagnosis.code) {
                DiagCode.OK -> colorOk
                // 这两条都是「按用户设置隐藏」，不是故障：报红会让人以为程序坏了
                DiagCode.LISTENER_UNBOUND,
                DiagCode.HIDDEN_BY_APP,
                DiagCode.A11Y_DISCONNECTED,
                DiagCode.HIDDEN_BY_RULE -> colorWarn

                else -> colorBad
            }
        )
        conclusionText.text = describe(diagnosis.code)
        evidenceText.text = getString(R.string.diag_evidence_label, diagnosis.evidence)

        val report = Probe.buildReport()
        if (reportText.text?.toString() != report) {
            reportText.text = report
        }
    }

    private fun describe(code: DiagCode): String = when (code) {
        DiagCode.PERMISSION_LISTENER -> getString(R.string.diag_code_permission_listener)
        DiagCode.LISTENER_UNBOUND -> getString(R.string.diag_code_listener_unbound)
        DiagCode.PERMISSION_OVERLAY -> getString(R.string.diag_code_permission_overlay)
        DiagCode.NO_SESSION -> getString(R.string.diag_code_no_session)
        DiagCode.SESSION_INACTIVE -> getString(R.string.diag_code_session_inactive)
        DiagCode.AVRCP_GATE -> getString(R.string.diag_code_avrcp_gate)
        DiagCode.NO_CHANNEL -> getString(R.string.diag_code_no_channel)
        DiagCode.CLOCK_UNSTABLE -> getString(R.string.diag_code_clock_unstable)
        DiagCode.OVERLAY_NOT_ATTACHED -> getString(R.string.diag_code_overlay_not_attached)
        DiagCode.OVERLAY_NOT_MEASURED -> getString(R.string.diag_code_overlay_not_measured)
        DiagCode.HIDDEN_BY_RULE -> getString(R.string.diag_code_hidden_by_rule)
        DiagCode.HIDDEN_BY_APP -> getString(R.string.diag_code_hidden_by_app)
        DiagCode.A11Y_DISCONNECTED -> getString(R.string.diag_code_a11y_disconnected)
        DiagCode.OK -> getString(R.string.diag_code_ok, Probe.hitSource ?: "-")
    }

    private fun copyReport() {
        val manager = getSystemService(ClipboardManager::class.java) ?: return
        manager.setPrimaryClip(ClipData.newPlainText("AnyLyric probe", Probe.buildReport()))
        toast(getString(R.string.diag_copied))
    }

    /** 写入公共下载目录：车机文件管理器可浏览，且返回的 uri 可直接用于分享。 */
    private fun saveReport(): Uri? {
        val report = Probe.buildReport()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "anylyric-probe-$stamp.txt")
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        return try {
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(report.toByteArray(Charsets.UTF_8))
            }
            uri
        } catch (_: Throwable) {
            null
        }
    }

    private fun saveReportAndToast() {
        val uri = saveReport()
        toast(
            if (uri != null) getString(R.string.diag_saved, "Downloads")
            else getString(R.string.diag_save_failed)
        )
    }

    private fun shareReport() {
        val report = Probe.buildReport()
        val uri = saveReport()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, report)
            if (uri != null) {
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        try {
            startActivity(Intent.createChooser(send, getString(R.string.diag_share)))
        } catch (_: Throwable) {
            toast(getString(R.string.diag_no_target))
        }
    }

    private companion object {
        const val REFRESH_MS = 2000L
    }
}
