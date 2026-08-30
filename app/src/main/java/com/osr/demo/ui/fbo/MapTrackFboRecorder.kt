package com.osr.demo.ui.fbo

import android.opengl.GLSurfaceView
import com.amap.api.maps.CustomRenderer
import com.osr.demo.ui.components.MapTrackView
import osp.osr.RecorderSession
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 地图 FBO 录制控制器（对齐 [com.osr.demo.ui.presentation.MapTrackPresentation] 生命周期）。
 *
 * 职责：把 OSR 的 GL Renderer 挂到高德 CustomRenderer，并协调
 * 地图加载 → 轨迹动画（驱动产帧）→ startRecord → stopRecord。
 */
class MapTrackFboRecorder(
    private val mapTrackView: MapTrackView,
    private val renderer: GLSurfaceView.Renderer,
    private val session: RecorderSession
) {

    init {
        // 将 OSR Renderer 桥接为高德 CustomRenderer；地图画完一帧后回调 onDrawFrame 才会 capture
        val customRenderer = object : CustomRenderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                renderer.onSurfaceCreated(gl, config)
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                renderer.onSurfaceChanged(gl, width, height)
            }

            override fun onDrawFrame(gl: GL10?) {
                renderer.onDrawFrame(gl)
            }

            override fun OnMapReferencechanged() {
                // 高德地图投影矩阵变化回调，录制链路无需处理
            }
        }
        mapTrackView.mapView.map.setCustomRenderer(customRenderer)
    }

    /** 地图就绪后先开启动画驱动产帧，再 startRecord；动画结束自动 stop */
    fun start() {
        val startAction = {
            // 地图静止时不触发 onDrawFrame，先开启动画驱动 AMap 持续刷新产帧，避免首帧等待死锁
            mapTrackView.startTrackAnimation()
            session.startRecord()
        }

        if (mapTrackView.isMapLoaded) {
            startAction()
        } else {
            mapTrackView.setOnMapLoadedListener {
                startAction()
            }
        }

        mapTrackView.setOnAnimationEndListener {
            session.stopRecord()
        }
    }
}
