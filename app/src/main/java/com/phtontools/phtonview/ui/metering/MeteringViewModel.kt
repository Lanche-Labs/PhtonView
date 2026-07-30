package com.phtontools.phtonview.ui.metering

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phtontools.phtonview.data.repository.CameraRepository
import com.phtontools.phtonview.metering.MeteringMath
import com.phtontools.phtonview.metering.PhoneCameraMeter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 测光界面 ViewModel（专业测光模式）。
 *
 * 核心逻辑：
 * 1. 从手机摄像头获取 Y 通道亮度
 * 2. 用专业公式计算 EV：EV = 10 + log2(Y/118)
 * 3. 用户选择固定参数（光圈/快门/ISO）
 * 4. 根据模式计算其他参数
 */
@HiltViewModel
class MeteringViewModel @Inject constructor(
    application: Application,
    private val repository: CameraRepository
) : AndroidViewModel(application) {

    /** 测光模式：固定光圈(A)、固定快门(S)、固定ISO(M) */
    enum class MeteringMode { AperturePriority, ShutterPriority, Manual }

    /** 推荐结果 */
    data class Recommendation(
        val aperture: Double,
        val shutterSeconds: Double,
        val iso: Int,
        val ev: Double,
        val meanLumaY: Double
    )

    // 当前测光模式
    private val _mode = MutableStateFlow(MeteringMode.AperturePriority)
    val mode: StateFlow<MeteringMode> = _mode.asStateFlow()

    // 用户选择的固定参数
    private val _fixedAperture = MutableStateFlow(5.6)
    val fixedAperture: StateFlow<Double> = _fixedAperture.asStateFlow()

    private val _fixedShutter = MutableStateFlow(1.0 / 125.0)
    val fixedShutter: StateFlow<Double> = _fixedShutter.asStateFlow()

    private val _fixedIso = MutableStateFlow(100)
    val fixedIso: StateFlow<Int> = _fixedIso.asStateFlow()

    // 计算出的推荐参数
    private val _recommendation = MutableStateFlow<Recommendation?>(null)
    val recommendation: StateFlow<Recommendation?> = _recommendation.asStateFlow()

    // 应用结果提示
    private val _applyMessage = MutableStateFlow<String?>(null)
    val applyMessage: StateFlow<String?> = _applyMessage.asStateFlow()

    // 测光模式（评价/中心/点测）
    private val _pattern = MutableStateFlow(PhoneCameraMeter.MeteringPattern.Matrix)
    val pattern: StateFlow<PhoneCameraMeter.MeteringPattern> = _pattern.asStateFlow()
    
    fun setPattern(p: PhoneCameraMeter.MeteringPattern) {
        _pattern.value = p
        meter.pattern = p
    }

    // 点测位置
    private val _spotPoint = MutableStateFlow<Pair<Float, Float>?>(null)
    val spotPoint: StateFlow<Pair<Float, Float>?> = _spotPoint.asStateFlow()
    
    fun setSpotPoint(p: Pair<Float, Float>?) {
        _spotPoint.value = p
        meter.spotRegion = p
    }

    val meter: PhoneCameraMeter = PhoneCameraMeter(application)

    init {
        meter.refreshPermissionState()
        // 订阅 meter 采样，实时刷新推荐参数
        viewModelScope.launch {
            meter.sample.collect { sample ->
                // EV 已在 PhoneCameraMeter 中计算好
                val ev = sample.sceneEvAtIso100
                
                // 根据当前模式计算推荐的第三个参数
                val rec = when (_mode.value) {
                    MeteringMode.AperturePriority -> {
                        // A 模式：用户选择光圈+ISO，推荐快门
                        val shutter = MeteringMath.computeShutterForAperture(ev, _fixedAperture.value, _fixedIso.value)
                        Recommendation(
                            aperture = _fixedAperture.value,
                            shutterSeconds = shutter,
                            iso = _fixedIso.value,
                            ev = ev,
                            meanLumaY = sample.meanLumaY
                        )
                    }
                    MeteringMode.ShutterPriority -> {
                        // S 模式：用户选择快门+ISO，推荐光圈
                        val aperture = MeteringMath.computeApertureForShutter(ev, _fixedShutter.value, _fixedIso.value)
                        Recommendation(
                            aperture = aperture,
                            shutterSeconds = _fixedShutter.value,
                            iso = _fixedIso.value,
                            ev = ev,
                            meanLumaY = sample.meanLumaY
                        )
                    }
                    MeteringMode.Manual -> {
                        // M 模式：用户选择光圈+快门，推荐 ISO
                        val iso = MeteringMath.computeIsoForApertureShutter(ev, _fixedAperture.value, _fixedShutter.value)
                        Recommendation(
                            aperture = _fixedAperture.value,
                            shutterSeconds = _fixedShutter.value,
                            iso = iso,
                            ev = ev,
                            meanLumaY = sample.meanLumaY
                        )
                    }
                }
                _recommendation.value = rec
            }
        }
    }

    fun setMode(m: MeteringMode) {
        _mode.value = m
    }

    fun setFixedAperture(a: Double) {
        _fixedAperture.value = MeteringMath.snapAperture(a)
    }

    fun setFixedShutter(s: Double) {
        _fixedShutter.value = MeteringMath.snapShutter(s)
    }

    fun setFixedIso(iso: Int) {
        _fixedIso.value = MeteringMath.snapIso(iso)
    }

    fun startMeter() {
        meter.start()
    }

    fun stopMeter() {
        meter.stop()
    }

    fun onPhonePreviewSurfaceAvailable(surface: android.view.Surface?) {
        meter.onPreviewSurfaceChanged(surface)
    }

    fun requestPermission() {
        meter.refreshPermissionState()
    }

    fun applyRecommendation() {
        val rec = _recommendation.value ?: run {
            _applyMessage.value = "暂无可应用的测光结果"
            return
        }
        viewModelScope.launch {
            try {
                repository.applyMeteredExposure(
                    aperture = MeteringMath.formatAperture(rec.aperture),
                    shutter = MeteringMath.formatShutter(rec.shutterSeconds),
                    iso = rec.iso
                )
                _applyMessage.value = "已应用：${MeteringMath.formatAperture(rec.aperture)}  ${MeteringMath.formatShutter(rec.shutterSeconds)}  ISO ${rec.iso}"
            } catch (e: Exception) {
                _applyMessage.value = "应用失败：${e.message}"
            }
        }
    }

    fun dismissApplyMessage() {
        _applyMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        meter.release()
    }
}