package com.osr.demo.ui.presentation

import android.app.Presentation
import android.os.Bundle
import android.view.Display
import androidx.appcompat.app.AppCompatActivity
import com.osr.demo.ui.components.MapTrackView
import osp.osr.RecorderSession

/**
 * 用于录制的 Presentation：展示 MapTrackView 动态轨迹动画，
 * 地图加载完成后 startRecord；第一帧就绪后再播轨迹，动画结束后 stopRecord。
 */
class MapTrackPresentation(
    activity: AppCompatActivity,
    display: Display,
    private val session: RecorderSession
) : Presentation(activity, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mapTrackView = MapTrackView(context)
        setContentView(mapTrackView)

        mapTrackView.setOnMapLoadedListener {
            // 静态首屏先出画；等编码器第一帧后再开轨迹，避免缺开头
            mapTrackView.postInvalidate()
            session.startRecord(onReady = { mapTrackView.startTrackAnimation() })
        }

        mapTrackView.setOnAnimationEndListener {
            session.stopRecord()
        }
    }
}
