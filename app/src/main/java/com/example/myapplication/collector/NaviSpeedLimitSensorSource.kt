package com.example.myapplication.collector

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log

/** Adds NaviHelper's current-road speed limit to the same sampled stream as VHAL values. */
class NaviSpeedLimitSensorSource(context: Context) : SensorSource {
    private val appContext = context.applicationContext
    @Volatile private var helper: NaviHelper? = null
    @Volatile private var listenerRegistered = false
    @Volatile private var stopping = false
    @Volatile private var callback: ((Channel, Float) -> Unit)? = null

    private val listener = object : NaviHelperEventListener {
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) {
            if (speedLimit !in 1..200 || stopping) {
                log("Navi 제한속도 값 범위 오류/미상: ${speedLimit}")
                return
            }
            // NaviHelper models speed limits as integer km/h; core's input is also km/h.
            callback?.invoke(Channel.SPEED_LIMIT, speedLimit.toFloat())
            log("Navi 현재 도로 제한속도=${speedLimit} km/h")
        }

        override fun onError(errorCode: NaviErrorCode) {
            log("NaviHelper 오류: $errorCode")
        }
    }

    override fun start(onValue: (Channel, Float) -> Unit) {
        if (helper != null) return
        stopping = false
        callback = onValue
        try {
            val instance = NaviHelper(appContext)
            helper = instance
            instance.initialize(
                onConnected = {
                    if (!stopping) {
                        try {
                            instance.addListener(listener)
                            listenerRegistered = true
                            log("NaviHelper 연결 및 도로 제한속도 구독 시작")
                        } catch (e: Exception) {
                            log("NaviHelper 리스너 등록 실패: ${e.javaClass.simpleName}: ${e.message}")
                        }
                    }
                },
                onFailed = { error ->
                    log("NaviHelper 연결 실패: ${error.javaClass.simpleName}: ${error.message}")
                },
            )
        } catch (e: Exception) {
            log("NaviHelper 시작 실패: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { helper?.release() }
            helper = null
        }
    }

    override fun stop() {
        stopping = true
        callback = null
        val active = helper
        helper = null
        if (active != null) {
            if (listenerRegistered) runCatching { active.removeListener(listener) }
            listenerRegistered = false
            runCatching { active.release() }
        }
        log("NaviHelper 제한속도 입력 종료")
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        SensorDebugLog.log(message)
    }

    private companion object { const val TAG = "OnGilNavi" }
}

/** Starts multiple independent sources through the collector's single callback. */
class CombinedSensorSource(private vararg val sources: SensorSource) : SensorSource {
    override fun start(onValue: (Channel, Float) -> Unit) {
        sources.forEach { it.start(onValue) }
    }

    override fun stop() {
        sources.forEach { runCatching { it.stop() } }
    }
}
