package com.osr.demo.ui.presentation

import android.app.Presentation
import android.os.Bundle
import android.view.Display
import androidx.appcompat.app.AppCompatActivity
import com.osr.demo.ui.components.MapTrackView
import osp.osr.RecorderSession

/**
 * 用于录制的 Presentation：展示 MapTrackView 动态轨迹动画，
 * 地图加载完成后自动播放轨迹，动画结束后调用 session.stopRecord()。
 */
class MapTrackPresentation(
    private val activity: AppCompatActivity,
    display: Display,
    private val session: RecorderSession
) : Presentation(activity, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mapTrackView = MapTrackView(context)
        setContentView(mapTrackView)

        mapTrackView.setOnMapLoadedListener {
            // 地图加载完成后启动轨迹动画（约 100 点 × 50ms ≈ 5 秒）
            mapTrackView.startTrackAnimation()
            session.startRecord()
        }

        mapTrackView.setOnAnimationEndListener {
            // 轨迹动画结束，停止录制
            session.stopRecord()
        }
    }
}
