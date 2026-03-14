package com.osr.demo

import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.osr.demo.ui.components.MapTrackView
import com.osr.demo.ui.presentation.MapTrackPresentation
import kotlinx.coroutines.launch
import osp.osr.OSR
import osp.osr.RecorderSession
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

    /** 当前录制会话，由 OSR 创建；停止并保存后在 onSaved 中置空 */
    private var recorderSession: RecorderSession? = null

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
        val session = recorderSession
        if (session != null) {
            // 已在录制：停止录制（会话会在 onSaved 中释放）
            if (session.getState() == osp.osr.model.RecorderState.RECORDING) {
                session.stopRecord()
                (view as? Button)?.text = "录制"
            }
            return
        }

        // 未在录制：在协程中创建会话并开始录制
        lifecycleScope.launch {
            // Download 目录：优先应用专属外部 Download；若为 null 则在外部 files 下建 Download；最后才用 data/data/files/Download
            val downloadDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: getExternalFilesDir(null)?.let { File(it, "Download").apply { mkdirs() } }
                ?: File(filesDir, "Download").apply { mkdirs() }
            val fileName = "osr_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.mp4"
            val outputFile = File(downloadDir, fileName)

            try {
                val newSession = OSR.recorder(applicationContext) {
                    video {
                        width = 1080
                        height = 1920
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
                                (view as? Button)?.text = "停止录制"
                                Toast.makeText(this@MainActivity, "开始录制", Toast.LENGTH_SHORT).show()
                            }
                        }
                        onSaved = { file ->
                            runOnUiThread {
                                recorderSession = null
                                (view as? Button)?.text = "录制"
                                Toast.makeText(this@MainActivity, "已保存: ${file.absolutePath}", Toast.LENGTH_LONG).show()
                            }
                        }
                        onError = { error ->
                            Log.i("OSR", "record: error : ${Log.getStackTraceString(error)}")
                            runOnUiThread {
                                recorderSession = null
                                (view as? Button)?.text = "录制"
                                Toast.makeText(this@MainActivity, "录制错误: ${error.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    presentation { display, session ->
                        MapTrackPresentation(this@MainActivity, display, session)
//                        ColorChangePresentation(this@MainActivity, display, session)
                    }
                }
                recorderSession = newSession
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "创建录制失败: ${e.message}", Toast.LENGTH_LONG).show()
            }

        }
    }
}