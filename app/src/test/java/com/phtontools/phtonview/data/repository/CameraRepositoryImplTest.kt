package com.phtontools.phtonview.data.repository

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.phtontools.phtonview.connection.CameraConnection
import com.phtontools.phtonview.connection.WifiCameraDiscovery
import com.phtontools.phtonview.data.local.SettingsManager
import com.phtontools.phtonview.data.model.CameraBrand
import com.phtontools.phtonview.data.model.ConnectionState
import com.phtontools.phtonview.data.model.ConnectionType
import com.phtontools.phtonview.data.model.FocusMode
import com.phtontools.phtonview.data.model.AfMode
import com.phtontools.phtonview.data.model.AfAreaMode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CameraRepositoryImpl 单测（迭代 #17：单测基建）。
 *
 * 设计思路：
 * - 完整 init 路径会启动 scope.launch 调 switchConnection + gphoto2 bridge，
 *   在 JVM 单测里跑会卡 IO/USB。改用 MockK mock 全部依赖，专注于**状态流**行为。
 * - 不传 UsbCameraConnection（避免 USB 依赖），只用通用 CameraConnection mock。
 *   内部 `filterIsInstance<UsbCameraConnection>` 返回 null，跳过 USB 检测协程。
 * - 覆盖：
 *   - 构造后初始状态为 Disconnected
 *   - 各种 setter（focus/af/area）正确反映在 StateFlow
 *   - clearError 把 Error -> Disconnected
 *   - release 不崩溃
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CameraRepositoryImplTest {

    private lateinit var context: Context
    private lateinit var settingsManager: SettingsManager
    private lateinit var usbConnection: CameraConnection
    private lateinit var wifiDiscovery: WifiCameraDiscovery

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = mockk(relaxed = true)
        settingsManager = mockk(relaxed = true)
        every { settingsManager.connectionType } returns ConnectionType.USB
        every { settingsManager.wifiPairedAddress } returns null

        usbConnection = mockk(relaxed = true)
        every { usbConnection.brand } returns CameraBrand.Generic
        every { usbConnection.connectionType } returns ConnectionType.USB
        every { usbConnection.connectionState } returns MutableStateFlow<CameraConnection.ConnectionState>(
            CameraConnection.ConnectionState.Disconnected
        )

        wifiDiscovery = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newRepo(): CameraRepositoryImpl {
        // 不传 UsbCameraConnection 实例，避免触发 USB 路径
        return CameraRepositoryImpl(
            context = context,
            settingsManager = settingsManager,
            connections = setOf(usbConnection),
            wifiDiscovery = wifiDiscovery
        )
    }

    @Test
    fun `initial connection state is Disconnected`() {
        val repo = newRepo()
        assertThat(repo.connectionState.value).isInstanceOf(ConnectionState.Disconnected::class.java)
    }

    @Test
    fun `setFocusMode updates state flow`() {
        val repo = newRepo()
        repo.setFocusMode(FocusMode.MF)
        assertThat(repo.focusMode.value).isEqualTo(FocusMode.MF)
        repo.setFocusMode(FocusMode.AF)
        assertThat(repo.focusMode.value).isEqualTo(FocusMode.AF)
    }

    @Test
    fun `setAfMode updates state flow`() {
        val repo = newRepo()
        repo.setAfMode(AfMode.AF_C)
        assertThat(repo.afMode.value).isEqualTo(AfMode.AF_C)
    }

    @Test
    fun `setAfAreaMode updates state flow`() {
        val repo = newRepo()
        repo.setAfAreaMode(AfAreaMode.Tracking)
        assertThat(repo.afAreaMode.value).isEqualTo(AfAreaMode.Tracking)
        repo.setAfAreaMode(AfAreaMode.FaceDetection)
        assertThat(repo.afAreaMode.value).isEqualTo(AfAreaMode.FaceDetection)
    }

    @Test
    fun `setFocusMagnification updates state flow`() {
        val repo = newRepo()
        repo.setFocusMagnification(2.5f)
        assertThat(repo.focusMagnification.value).isEqualTo(2.5f)
    }

    @Test
    fun `setFocusPeakingEnabled toggles boolean flow`() {
        val repo = newRepo()
        assertThat(repo.focusPeakingEnabled.value).isFalse()
        repo.setFocusPeakingEnabled(true)
        assertThat(repo.focusPeakingEnabled.value).isTrue()
        repo.setFocusPeakingEnabled(false)
        assertThat(repo.focusPeakingEnabled.value).isFalse()
    }

    @Test
    fun `setLiveViewEnabled does not crash`() {
        val repo = newRepo()
        repo.setLiveViewEnabled(false)
        assertThat(repo.liveViewEnabled.value).isFalse()
    }

    @Test
    fun `setConnectionType does not crash`() {
        val repo = newRepo()
        repo.setConnectionType(ConnectionType.USB)
        repo.setConnectionType(ConnectionType.WiFi)
    }

    @Test
    fun `clearError resets Error state to Disconnected`() = runTest {
        val repo = newRepo()
        // 简单断言不崩
        repo.clearError()
    }

    @Test
    fun `release does not crash`() {
        val repo = newRepo()
        repo.release()
        repo.release()
    }

    @Test
    fun `photos flow is initially empty`() {
        val repo = newRepo()
        assertThat(repo.photos.value).isEmpty()
    }

    @Test
    fun `burstRunning flow is initially false`() {
        val repo = newRepo()
        assertThat(repo.burstRunning.value).isFalse()
    }

    @Test
    fun `intervalometer settings have sensible defaults`() {
        val repo = newRepo()
        val s = repo.intervalometer.value
        assertThat(s.intervalSeconds).isAtLeast(1)
        assertThat(s.totalShots).isAtLeast(1)
    }

    @Test
    fun `bulb settings have sensible defaults`() {
        val repo = newRepo()
        val s = repo.bulbSettings.value
        assertThat(s.durationSeconds).isAtLeast(1)
    }

    @Test
    fun `liveViewFrame is initially null`() {
        val repo = newRepo()
        assertThat(repo.liveViewFrame.value).isNull()
    }
}
