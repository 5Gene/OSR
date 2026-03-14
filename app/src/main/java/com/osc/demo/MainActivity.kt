package com.osc.demo

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.osc.demo.ui.components.MapTrackView

class MainActivity : AppCompatActivity() {

    private lateinit var mapTrackView: MapTrackView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mapTrackView = findViewById(R.id.mapTrackView)
        mapTrackView.setOnAnimationListener(object : MapTrackView.OnAnimationListener {
            override fun onAnimationComplete() {
                // 动画完成回调
            }
        })
    }
}