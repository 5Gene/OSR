package com.osr.demo

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.osr.demo.ui.components.ColorChangeView
import com.osr.demo.ui.components.MapTrackView
import com.osr.demo.ui.fbo.MapTrackFboRecorder
import com.osr.demo.ui.presentation.MapTrackPresentation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import osp.osr.OSR
import osp.osr.RecorderSession
import osp.osr.dsl.RecorderConfig
import osp.osr.fbo.fbo
import osp.osr.pres.presentation
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun Context.assetToFile(assetName: String): File {
    val file = File(cacheDir, assetName)
    if (!file.exists()) {
        assets.open(assetName).use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }
    return file
}

fun Context.rawToFile(@androidx.annotation.RawRes resId: Int, name: String): File {
    val file = File(cacheDir, name)
    if (!file.exists()) {
        resources.openRawResource(resId).use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }
    return file
}

class MainActivity : AppCompatActivity() {

    private lateinit var mapTrackView: MapTrackView

    /** 当前录制会话；停止并保存后在 onSaved 中置空 */
    private var recorderSession: RecorderSession? = null

    /** FBO2 地图录制控制器 */
    private var mapTrackFboRecorder: MapTrackFboRecorder? = null

    /** 最近一次应用私有目录下的录制文件 */
    private var lastRecordedFile: File? = null

    /** 移动到公共 Download/osr 并扫描后的 content URI（免 FileProvider） */
    private var lastScannedUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mapTrackView = findViewById(R.id.mapTrackView)
        mapTrackView.setOnMapLoadedListener {
            // 地图加载完成
        }
        mapTrackView.setOnAnimationEndListener {
            // 轨迹动画结束
        }
    }

    fun record(view: View) {
        toggleOrStart(view as Button, "开始录制") {
            presentation { display, session ->
                MapTrackPresentation(this@MainActivity, display, session)
            }
        }
    }

    fun recordFbo(view: View) {
        toggleOrStart(view as Button, "开始 FBO 录制") {
            fbo {
                view { session ->
                    ColorChangeView(this@MainActivity).apply {
                        onStart = { session.startRecord() }
                        onEnd = { session.stopRecord() }
                        start()
                    }
                }
            }
        }
    }

    fun recordFbo2(view: View) {
        toggleOrStart(
            button = view as Button,
            startMsg = "开始 FBO2 录制",
            afterSessionCreated = { mapTrackFboRecorder?.start() }
        ) {
            fbo {
                renderer { renderer, session ->
                    // prepare 阶段挂载 CustomRenderer；会话返回后再 start()
                    mapTrackFboRecorder = MapTrackFboRecorder(mapTrackView, renderer, session)
                }
            }
        }
    }

    /**
     * 已在录制则停止；否则创建会话。
     * [strategyBlock] 只配 presentation / fbo；音视频与 listener 走公共模板。
     */
    private fun toggleOrStart(
        button: Button,
        startMsg: String,
        afterSessionCreated: (() -> Unit)? = null,
        strategyBlock: RecorderConfig.() -> Unit
    ) {
        val session = recorderSession
        if (session != null) {
            if (session.getState() == osp.osr.model.RecorderState.RECORDING) {
                session.stopRecord()
                button.text = "录制"
            }
            return
        }

        lifecycleScope.launch {
            val outputFile = createOutputFile()
            try {
                val newSession = createCommonRecorder(outputFile, button, startMsg, strategyBlock)
                recorderSession = newSession
                afterSessionCreated?.invoke()
            } catch (e: Exception) {
                mapTrackFboRecorder = null
                Toast.makeText(this@MainActivity, "创建录制失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 统一 video / output / audio / listener，减少三路录制样板代码 */
    private suspend fun createCommonRecorder(
        outputFile: File,
        button: Button,
        startMsg: String,
        strategyBlock: RecorderConfig.() -> Unit
    ): RecorderSession {
        return OSR.recorder(applicationContext) {
            video {
                // width/height/dpi 默认屏幕；关掉 MaxFS 强缩以冲高清
                strictAvcLevel41 = false
                fps = 30
                bitrate = 4_000_000
            }
            output {
                file = outputFile
            }
            audio {
                file = rawToFile(R.raw.xiayu, "calm.acc")
            }
            listener {
                onStart = {
                    runOnUiThread {
                        button.text = "停止录制"
                        Toast.makeText(this@MainActivity, startMsg, Toast.LENGTH_SHORT).show()
                        lastScannedUri = null
                    }
                }
                onSaved = { file ->
                    runOnUiThread {
                        recorderSession = null
                        mapTrackFboRecorder = null
                        button.text = "录制"
                        lastRecordedFile = file
                        showSuccessSnackbar(file)
                    }
                }
                onError = { error ->
                    Log.i("OSR", "record: error : ${Log.getStackTraceString(error)}")
                    runOnUiThread {
                        recorderSession = null
                        mapTrackFboRecorder = null
                        button.text = "录制"
                        Toast.makeText(this@MainActivity, "录制错误: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            strategyBlock()
        }
    }

    private fun createOutputFile(): File {
        val downloadDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: getExternalFilesDir(null)?.let { File(it, "Download").apply { mkdirs() } }
            ?: File(filesDir, "Download").apply { mkdirs() }
        val fileName = "osr_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.mp4"
        return File(downloadDir, fileName)
    }

    /**
     * Snackbar 双 Action：原生「打开」+ 动态注入「移动」。
     * 均复制到公共 Download/osr 并用 MediaScanner 拿 content URI，无需 FileProvider。
     */
    private fun showSuccessSnackbar(file: File) {
        val snackbar = Snackbar.make(
            findViewById(android.R.id.content),
            "录制成功",
            Snackbar.LENGTH_LONG
        )
        snackbar.setAction("打开") {
            copyScanThen(file, openAfter = true)
        }

        val snackbarLayout = snackbar.view as Snackbar.SnackbarLayout
        val actionButton =
            snackbarLayout.findViewById<Button>(com.google.android.material.R.id.snackbar_action)
        val parent = actionButton.parent as android.view.ViewGroup

        val moveButton = Button(this).apply {
            text = "移动"
            setTextColor(actionButton.textColors)
            background = null
            setOnClickListener {
                copyScanThen(file, openAfter = false)
                snackbar.dismiss()
            }
        }
        parent.addView(moveButton, parent.indexOfChild(actionButton))
        snackbar.show()
    }

    /** 复制到 Download/osr + MediaScanner；[openAfter] 为 true 则用扫描 URI 打开播放器 */
    private fun copyScanThen(privateFile: File, openAfter: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val publicDownloadDir =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val osrDir = File(publicDownloadDir, "osr").apply { if (!exists()) mkdirs() }
                val targetFile = File(osrDir, privateFile.name)
                privateFile.inputStream().use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                MediaScannerConnection.scanFile(
                    applicationContext,
                    arrayOf(targetFile.absolutePath),
                    arrayOf("video/mp4")
                ) { _, uri ->
                    runOnUiThread {
                        if (uri != null) lastScannedUri = uri
                        if (openAfter) {
                            openScannedVideo(uri)
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                "已移动到 Download/osr",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "操作失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun openScannedVideo(uri: Uri?) {
        val playUri = uri ?: lastScannedUri
        if (playUri == null) {
            Toast.makeText(this, "视频尚未准备就绪，请稍后重试", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(playUri, "video/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        } catch (e: Exception) {
            Toast.makeText(this, "没有找到合适的视频播放器: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
