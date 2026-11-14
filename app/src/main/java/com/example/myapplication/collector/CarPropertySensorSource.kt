package com.example.myapplication.collector

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.Executors
import kotlin.math.abs

/** Reads the properties published by Pleos Connect Emulator's VHAL. */
class CarPropertySensorSource(private val context: Context) : SensorSource {
    private val handlerThread = HandlerThread("ongil-car-properties").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var callback: ((Channel, Float) -> Unit)? = null
    @Volatile private var car: Car? = null
    @Volatile private var manager: CarPropertyManager? = null
    @Volatile private var stopping = false
    private val counts = HashMap<Int, Int>()

    private val propertyCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (value.propertyStatus != CarPropertyValue.STATUS_AVAILABLE) {
                log("${propertyName(value.propertyId)} 상태=${value.propertyStatus}")
                return
            }
            val raw = value.value
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> (raw as? Number)?.toFloat()?.let {
                    publish(Channel.SPEED, abs(it), value.propertyId)
                }
                VehiclePropertyIds.GEAR_SELECTION -> (raw as? Number)?.toInt()?.let {
                    if (it != VehicleGear.GEAR_UNKNOWN) publish(Channel.GEAR, it.toFloat(), value.propertyId)
                }
                VehiclePropertyIds.ACCELERATOR_PEDAL_COMPRESSION_PERCENTAGE -> (raw as? Number)?.toFloat()?.let {
                    publish(Channel.ACCELERATOR, it.coerceIn(0f, 100f), value.propertyId)
                }
                VehiclePropertyIds.BRAKE_PEDAL_COMPRESSION_PERCENTAGE -> (raw as? Number)?.toFloat()?.let {
                    publish(Channel.BRAKE, it.coerceIn(0f, 100f), value.propertyId)
                }
                VehiclePropertyIds.PERF_STEERING_ANGLE -> (raw as? Number)?.toFloat()?.let {
                    // AAOS front-wheel angle is degrees, left negative; core convention is left positive.
                    publish(Channel.STEERING, -it, value.propertyId)
                }
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            log("${propertyName(propertyId)} 읽기 오류 (area=$areaId)")
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int, errorCode: Int) {
            log("${propertyName(propertyId)} 오류 code=$errorCode area=$areaId")
        }
    }

    private val lifecycleListener = Car.CarServiceLifecycleListener { connectedCar, ready ->
        if (ready) connect(connectedCar) else log("Car Service 연결 해제")
    }

    override fun start(onValue: (Channel, Float) -> Unit) {
        callback = onValue
        SensorDebugLog.log("CarPropertySensorSource 시작, Car Service 연결 요청")
        executor.execute {
            try {
                val instance = Car.createCar(
                    context.applicationContext, handler,
                    Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT, lifecycleListener,
                )
                if (stopping) instance.disconnect() else car = instance
            } catch (e: Exception) {
                log("Car Service 생성 실패: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun connect(connectedCar: Car) {
        if (stopping) return
        try {
            val propertyManager = connectedCar.getCarManager(CarPropertyManager::class.java)
            if (propertyManager == null) {
                log("CarPropertyManager를 사용할 수 없음")
                return
            }
            manager = propertyManager
            log("Car Service 연결됨. 차량 속성 구독 시작")
            subscribe(propertyManager, VehiclePropertyIds.PERF_VEHICLE_SPEED, 10f)
            subscribe(propertyManager, VehiclePropertyIds.GEAR_SELECTION, 1f)
            subscribe(propertyManager, VehiclePropertyIds.ACCELERATOR_PEDAL_COMPRESSION_PERCENTAGE, 10f)
            subscribe(propertyManager, VehiclePropertyIds.BRAKE_PEDAL_COMPRESSION_PERCENTAGE, 10f)
            subscribe(propertyManager, VehiclePropertyIds.PERF_STEERING_ANGLE, 10f)
        } catch (e: Exception) {
            log("차량 속성 연결 실패: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun subscribe(manager: CarPropertyManager, propertyId: Int, rateHz: Float) {
        try {
            val accepted = manager.subscribePropertyEvents(propertyId, rateHz, propertyCallback)
            log("${propertyName(propertyId)} 구독 ${if (accepted) "요청 성공" else "미지원/거부"}")
        } catch (e: SecurityException) {
            log("${propertyName(propertyId)} 권한 거부: ${e.message}")
        } catch (e: Exception) {
            log("${propertyName(propertyId)} 구독 오류: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun publish(channel: Channel, value: Float, propertyId: Int) {
        if (!value.isFinite() || stopping) return
        callback?.invoke(channel, value)
        val count = synchronized(counts) {
            val next = (counts[propertyId] ?: 0) + 1
            counts[propertyId] = next
            next
        }
        if (count == 1 || count % 50 == 0) {
            log("${propertyName(propertyId)} 값=${"%.3f".format(value)} (${count}회)")
        }
    }

    override fun stop() {
        stopping = true
        callback = null
        try { manager?.unsubscribePropertyEvents(propertyCallback) } catch (e: Exception) { log("구독 해제 오류: ${e.message}") }
        manager = null
        try { car?.disconnect() } catch (e: Exception) { log("Car 연결 종료 오류: ${e.message}") }
        car = null
        executor.shutdownNow()
        handlerThread.quitSafely()
        SensorDebugLog.log("CarPropertySensorSource 종료")
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        SensorDebugLog.log(message)
    }

    private fun propertyName(id: Int) = when (id) {
        VehiclePropertyIds.PERF_VEHICLE_SPEED -> "속도"
        VehiclePropertyIds.GEAR_SELECTION -> "기어"
        VehiclePropertyIds.ACCELERATOR_PEDAL_COMPRESSION_PERCENTAGE -> "가속 페달"
        VehiclePropertyIds.BRAKE_PEDAL_COMPRESSION_PERCENTAGE -> "브레이크 페달"
        VehiclePropertyIds.PERF_STEERING_ANGLE -> "앞바퀴 조향각"
        else -> "차량 속성 $id"
    }

    private companion object { const val TAG = "OnGilVehicle" }
}
