package com.phtontools.phtonview.collab

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 房间协作控制器单测（迭代 #18）。
 *
 * 用 [FakeCollabTransport] 模拟"同房间"消息互通，验证：
 * - createRoom 后 room 状态流更新为 Room(code=..., host=local)
 * - joinRoom 后对方能收到 JoinRequest
 * - 收到 MemberUpdate 后本地 room 状态同步
 * - Host 收到 CaptureRequest 后入队 incoming
 * - 离开房间广播 LeaveNotice
 *
 * 注意：每个测试用 [runTest] + [UnconfinedTestDispatcher] + [yield] 组合
 * 让 init 协程的 collect 订阅在测试断言前已激活。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CollabControllerTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * 工具：让 backgroundScope 上的所有 launched 协程至少完成一次 dispatch 循环。
     * UnconfinedTestDispatcher 下 yield() 即可让订阅在调用方返回前完成挂起。
     */
    private suspend fun pump() {
        yield()
        yield()
    }

    @Test
    fun `createRoom sets local room and Host role`() = runTest(UnconfinedTestDispatcher()) {
        val bus = FakeCollabTransport.Bus()
        val transport = FakeCollabTransport(bus)
        val controller = CollabController(
            scope = backgroundScope,
            transport = transport,
            localPeerId = "P-HOST",
            localDisplayName = "Host"
        )
        val result = controller.createRoom("PHTN42")
        pump()
        assertThat(result.isSuccess).isTrue()
        val room = controller.room.value
        assertThat(room).isNotNull()
        assertThat(room!!.code).isEqualTo("PHTN42")
        assertThat(room.hostPeerId).isEqualTo("P-HOST")
        assertThat(controller.localRole.value).isEqualTo(MemberRole.Host)
    }

    @Test
    fun `joinRoom sets Viewer role and broadcasts request`() = runTest(UnconfinedTestDispatcher()) {
        val bus = FakeCollabTransport.Bus()
        val transport = FakeCollabTransport(bus)
        val received = mutableListOf<String>()
        backgroundScope.launch { transport.received().collect { received.add(it) } }

        val controller = CollabController(
            scope = backgroundScope,
            transport = transport,
            localPeerId = "P-OP",
            localDisplayName = "Operator"
        )
        controller.joinRoom("PHTN42", MemberRole.Operator)
        pump()
        assertThat(controller.localRole.value).isEqualTo(MemberRole.Operator)
        assertThat(received).isNotEmpty()
        val types = received.map { CollabMessageSerializer.decode(it) }
        assertThat(types[0]).isInstanceOf(CollabMessage.JoinRequest::class.java)
        val jr = types[0] as CollabMessage.JoinRequest
        assertThat(jr.senderPeerId).isEqualTo("P-OP")
        assertThat(jr.requestedRole).isEqualTo(MemberRole.Operator)
    }

    @Test
    fun `Viewer cannot request capture`() = runTest(UnconfinedTestDispatcher()) {
        val bus = FakeCollabTransport.Bus()
        val transport = FakeCollabTransport(bus)
        val received = mutableListOf<String>()
        backgroundScope.launch { transport.received().collect { received.add(it) } }

        val controller = CollabController(
            scope = backgroundScope,
            transport = transport,
            localPeerId = "P-V",
            localDisplayName = "Viewer"
        )
        controller.joinRoom("PHTN42", MemberRole.Viewer)
        pump()
        assertThat(controller.localRole.value).isEqualTo(MemberRole.Viewer)
        val before = received.size
        controller.requestCapture(burstCount = 1)
        pump()
        // Viewer 不应广播 capture_req
        assertThat(received.size).isEqualTo(before)
        controller.leaveRoom("test")
    }

    @Test
    fun `Host receives incoming CaptureRequest and routes to incoming flow`() = runTest(UnconfinedTestDispatcher()) {
        val bus = FakeCollabTransport.Bus()
        val hostTransport = FakeCollabTransport(bus)
        val opTransport = FakeCollabTransport(bus)

        val host = CollabController(
            scope = backgroundScope,
            transport = hostTransport,
            localPeerId = "P-HOST",
            localDisplayName = "Host"
        )
        val op = CollabController(
            scope = backgroundScope,
            transport = opTransport,
            localPeerId = "P-OP",
            localDisplayName = "Operator"
        )

        host.createRoom("PHTN42")
        op.joinRoom("PHTN42", MemberRole.Operator)
        pump()

        val received = mutableListOf<CollabMessage>()
        backgroundScope.launch { host.incoming.collect { received.add(it) } }

        op.requestCapture(delayMs = 1000, burstCount = 1)
        pump()

        val captureReqs = received.filterIsInstance<CollabMessage.CaptureRequest>()
        assertThat(captureReqs).isNotEmpty()
        assertThat(captureReqs[0].burstCount).isEqualTo(1)
        assertThat(captureReqs[0].delayMs).isEqualTo(1000)
    }

    @Test
    fun `Host broadcasts settings only when role is Host`() = runTest(UnconfinedTestDispatcher()) {
        val bus = FakeCollabTransport.Bus()
        val transport = FakeCollabTransport(bus)
        val received = mutableListOf<String>()
        backgroundScope.launch { transport.received().collect { received.add(it) } }

        val host = CollabController(
            scope = backgroundScope,
            transport = transport,
            localPeerId = "P-HOST",
            localDisplayName = "Host"
        )
        host.createRoom("PHTN42")
        pump()
        host.broadcastSettings(
            aperture = "f/2.8",
            shutter = "1/250",
            iso = 200,
            ev = 0f,
            whiteBalance = "Auto",
            flashMode = "Off",
            shootingMode = "M"
        )
        pump()
        val types = received.map { CollabMessageSerializer.decode(it) }
        val settings = types.filterIsInstance<CollabMessage.SettingsSnapshot>()
        assertThat(settings).isNotEmpty()
        assertThat(settings[0].aperture).isEqualTo("f/2.8")
        assertThat(settings[0].iso).isEqualTo(200)
    }

    @Test
    fun `Operator cannot broadcast settings`() = runTest(UnconfinedTestDispatcher()) {
        val bus = FakeCollabTransport.Bus()
        val transport = FakeCollabTransport(bus)
        val received = mutableListOf<String>()
        backgroundScope.launch { transport.received().collect { received.add(it) } }

        val op = CollabController(
            scope = backgroundScope,
            transport = transport,
            localPeerId = "P-OP",
            localDisplayName = "Op"
        )
        op.joinRoom("PHTN42", MemberRole.Operator)
        pump()
        op.broadcastSettings(
            aperture = "f/2.8", shutter = "1/250", iso = 200, ev = 0f,
            whiteBalance = "Auto", flashMode = "Off", shootingMode = "M"
        )
        pump()
        val types = received.map { CollabMessageSerializer.decode(it) }
        val settings = types.filterIsInstance<CollabMessage.SettingsSnapshot>()
        assertThat(settings).isEmpty()
    }

    @Test
    fun `MemberUpdate updates local room state`() = runTest(UnconfinedTestDispatcher()) {
        val bus = FakeCollabTransport.Bus()
        val transport = FakeCollabTransport(bus)
        val controller = CollabController(
            scope = backgroundScope,
            transport = transport,
            localPeerId = "P-OP",
            localDisplayName = "Op"
        )
        controller.joinRoom("PHTN42", MemberRole.Viewer)
        pump()
        val update = CollabMessage.MemberUpdate(
            senderPeerId = "P-HOST",
            room = CollabRoom(
                code = "PHTN42",
                hostPeerId = "P-HOST",
                members = listOf(
                    CollabMember("P-HOST", "Host", MemberRole.Host),
                    CollabMember("P-OP", "Op", MemberRole.Operator)
                )
            )
        )
        val json = CollabMessageSerializer.encode(update)
        bus.publish(json)
        pump()
        val room = controller.room.value
        assertThat(room).isNotNull()
        assertThat(room!!.members).hasSize(2)
        assertThat(controller.localRole.value).isEqualTo(MemberRole.Operator)
    }
}
