package com.osr.demo.ui.components

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.TextView
import com.amap.api.location.AMapLocationClient.updatePrivacyAgree
import com.amap.api.location.AMapLocationClient.updatePrivacyShow
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.CustomRenderer
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.PolylineOptions
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MapTrackView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val mapView: MapView
    private val distanceTextView: TextView

    private val trackPoints: List<LatLng> = generateTiananmenTrack()

    private var isAnimating = false
    private var currentPointIndex = 0
    private var totalDistance = 0f
    private var mapLoadedListener: OnMapLoadedListener? = null
    private var animationListener: OnAnimationEndListener? = null

    /** 地图加载完成回调，供外部使用 */
    fun interface OnMapLoadedListener {
        fun onMapLoaded()
    }

    /** 轨迹动画结束回调，供外部使用 */
    fun interface OnAnimationEndListener {
        fun onAnimationEnd()
    }

    init {
        // 初始化地图
        updatePrivacyAgree(context, true)
        updatePrivacyShow(context, true, true)

        mapView = MapView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            onCreate(null)
            map.uiSettings.apply {
                isZoomControlsEnabled = false
                isMyLocationButtonEnabled = false
                isScaleControlsEnabled = true
            }
            map.mapType = AMap.MAP_TYPE_SATELLITE
            map.setOnMapLoadedListener {
                mapLoadedListener?.onMapLoaded()
            }
            map.setCustomRenderer(object : CustomRenderer {
                override fun OnMapReferencechanged() {
                    println("CustomRenderer => OnMapReferencechanged")
                }

                override fun onDrawFrame(p0: GL10?) {
                    println("CustomRenderer => onDrawFrame")
                }

                override fun onSurfaceChanged(p0: GL10?, p1: Int, p2: Int) {
                    println("CustomRenderer => onSurfaceChanged p0 = [${p0}], p1 = [${p1}], p2 = [${p2}]")

                }

                override fun onSurfaceCreated(
                    p0: GL10?,
                    p1: EGLConfig?
                ) {
                    println("CustomRenderer => onSurfaceCreated -> p0 = ${p0}")
                }
            })

            // 设置北京天安门为中心
            val centerPoint = LatLng(39.905, 116.397)
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(centerPoint, 15f))
        }
        addView(mapView)

        // 左下角距离显示 (带圆角背景)
        distanceTextView = TextView(context).apply {
            text = "距离: 0.00 km"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(40, 24, 40, 24)

            val background = GradientDrawable().apply {
                setColor(Color.parseColor("#B3000000"))
                cornerRadius = 24f
            }
            setBackground(background)

            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
                setMargins(32, 0, 0, 132)
            }

            setOnClickListener {
                startAnimation()
            }
        }
        addView(distanceTextView)

    }

    /** 设置地图加载完成监听 */
    fun setOnMapLoadedListener(listener: OnMapLoadedListener?) {
        mapLoadedListener = listener
    }

    /** 设置轨迹动画结束监听 */
    fun setOnAnimationEndListener(listener: OnAnimationEndListener?) {
        animationListener = listener
    }

    /** 外部启动轨迹动画（如 Presentation 中录制时调用） */
    fun startTrackAnimation() {
        if (!isAnimating) startAnimation()
    }

    private fun startAnimation() {
        isAnimating = true
        currentPointIndex = 0
        totalDistance = 0f

        // 使用 Handler 实现动画
        android.os.Handler(android.os.Looper.getMainLooper()).post(object : Runnable {
            override fun run() {
                if (currentPointIndex < trackPoints.size) {
                    currentPointIndex++

                    // 更新地图
                    updateMap()

                    // 计算距离
                    if (currentPointIndex > 1) {
                        totalDistance = calculateTotalDistance()
                        distanceTextView.text = String.format("距离: %.2f km", totalDistance)
                    }

                    // 继续动画 - 50ms 一个点
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, 50)
                } else {
                    isAnimating = false
                    animationListener?.onAnimationEnd()
                }
            }
        })
    }

    private fun updateMap() {
        if (currentPointIndex <= 1) return

        val visiblePoints = trackPoints.take(currentPointIndex)

        // 清除之前的绘制
        mapView.map.clear()

        // 绘制已完成部分 - 蓝色
        if (visiblePoints.size > 1) {
            mapView.map.addPolyline(
                PolylineOptions()
                    .addAll(visiblePoints.take(visiblePoints.size - 1))
                    .color(Color.BLUE)
                    .width(15f)
            )
        }

        // 当前绘制部分 - 红色
        if (visiblePoints.size >= 2) {
            mapView.map.addPolyline(
                PolylineOptions()
                    .addAll(visiblePoints.takeLast(2))
                    .color(Color.RED)
                    .width(20f)
            )
        }
    }

    private fun calculateTotalDistance(): Float {
        var distance = 0f
        for (i in 1 until currentPointIndex) {
            distance += calculateDistance(trackPoints[i - 1], trackPoints[i])
        }
        return distance
    }

    private fun calculateDistance(start: LatLng, end: LatLng): Float {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            start.latitude, start.longitude,
            end.latitude, end.longitude,
            results
        )
        return results[0] / 1000f
    }

    // 生成北京天安门附近的轨迹点（100个点）
    private fun generateTiananmenTrack(): List<LatLng> {
        val points = mutableListOf<LatLng>()

        val centerLat = 39.905
        val centerLng = 116.397

        // 椭圆轨迹
        val a = 0.008
        val b = 0.005

        for (i in 0 until 100) {
            val angle = (i.toDouble() / 100) * 2 * Math.PI
            val lat = centerLat + b * Math.sin(angle)
            val lng = centerLng + a * Math.cos(angle)
            points.add(LatLng(lat, lng))
        }

        return points
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mapView.onDestroy()
    }
}