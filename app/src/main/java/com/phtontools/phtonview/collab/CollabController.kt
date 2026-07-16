package com.phtontools.phtonview.collab

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 房间协作控制器（迭代 #18）。
 *
 * 高级 API，给 ViewModel / UI 用：
 * - [createRoom] / [joinRoom] / [leaveRoom]
 * - [requestCapture]  —— Operator 远程请求过片
 * - [broadcastSettings]  —— Host 推送设置变更
 * - [broadcastFrame]  —— Host 推送取景帧
 * - [sendChat]  —— 任意成员发文字
 *
 * 内部：
 * - 维护 [room]（StateFlow 房间状态）
 * - 维护 [incoming]（SharedFlow 接收消息，UI/ViewModel 订阅）
 * - 转发到 [transport]（接口注入，单元测试可用 mock 替代真实 UDP）
 *
 * 注意：本类**不**直接处理网络收发，所有 IO 通过 [CollabTransport] 抽象；
 * 这样单元测试可在 JVM 上跑（用 FakeTransport 替代真实 socket）。
 */
class CollabController(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val transport: CollabTransport,
    private val localPeerId: String = CollabMember.newPeerId(),
    private val localDisplayName: String = "Operator"
) {

    private val _room = MutableStateFlow<CollabRoom?>(null)
    val room: StateFlow<CollabRoom?> = _room.asStateFlow()

    private val _incoming = MutableSharedFlow<CollabMessage>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val incoming: SharedFlow<CollabMessage> = _incoming.asSharedFlow()

    private val _localRole = MutableStateFlow(MemberRole.Viewer)
    val localRole: StateFlow<MemberRole> = _localRole.asStateFlow()

    init {
        // 启动 transport 接收循环，把每条消息推入 _incoming
        scope.launch {
            transport.received().collect { msgJson ->
                val message = runCatching { CollabMessageSerializer.decode(msgJson) }
                    .onFailure { /* 协议解析失败，丢弃 */ }
                    .getOrNull() ?: return@collect
                applyMessage(message)
            }
        }
    }

    /**
     * 创建房间（作为 Host）。
     */
    suspend fun createRoom(code: String): Result<CollabRoom> {
        val normalized = code.uppercase()
        val room = CollabRoom(
            code = normalized,
            hostPeerId = localPeerId,
            members = listOf(
                CollabMember(localPeerId, localDisplayName, MemberRole.Host)
            )
        )
        _room.value = room
        _localRole.value = MemberRole.Host
        transport.openRoom(normalized)
        // Host 主动广播 MemberUpdate 让 LAN 内其他客户端发现
        broadcast(room.toMemberUpdate())
        return Result.success(room)
    }

    /**
     * 加入房间（作为 Operator / Viewer）。
     */
    suspend fun joinRoom(code: String, role: MemberRole = MemberRole.Viewer): Result<Unit> {
        val normalized = code.uppercase()
        transport.joinRoom(normalized)
        // **修复**：joinRoom 时也要初始化 _room，避免后续 broadcast 提前 return
        val provisionalRoom = CollabRoom(
            code = normalized,
            hostPeerId = "",  // host 信息由后续 MemberUpdate 覆盖
            members = listOf(CollabMember(localPeerId, localDisplayName, role))
        )
        _room.value = provisionalRoom
        _localRole.value = role
        val request = CollabMessage.JoinRequest(
            senderPeerId = localPeerId,
            displayName = localDisplayName,
            requestedRole = role
        )
        broadcast(request)
        return Result.success(Unit)
    }

    /**
     * 离开房间。
     */
    suspend fun leaveRoom(reason: String? = null) {
        val current = _room.value
        if (current == null) return
        broadcast(
            CollabMessage.LeaveNotice(
                senderPeerId = localPeerId,
                reason = reason
            )
        )
        transport.leaveRoom(current.code)
        _room.value = null
        _localRole.value = MemberRole.Viewer
    }

    /**
     * Operator 远程请求过片。
     *
     * Host 收到后会触发 capture 并返回 [CollabMessage.CaptureAck]，
     * 原始请求方（Operator）通过 [incoming] 收到 ack 即可显示结果。
     */
    suspend fun requestCapture(delayMs: Long = 0, burstCount: Int = 1) {
        if (_localRole.value == MemberRole.Viewer) {
            // Viewer 无权触发；UI 应禁用按钮，这里直接 return
            return
        }
        broadcast(
            CollabMessage.CaptureRequest(
                senderPeerId = localPeerId,
                delayMs = delayMs,
                burstCount = burstCount
            )
        )
    }

    /**
     * Host 主动广播当前相机设置。
     * 触发时机：用户改设置 / 新成员加入 / 周期 30s 兜底。
     */
    suspend fun broadcastSettings(
        aperture: String,
        shutter: String,
        iso: Int,
        ev: Float,
        whiteBalance: String,
        flashMode: String,
        shootingMode: String
    ) {
        if (_localRole.value != MemberRole.Host) return
        broadcast(
            CollabMessage.SettingsSnapshot(
                senderPeerId = localPeerId,
                aperture = aperture,
                shutter = shutter,
                iso = iso,
                ev = ev,
                whiteBalance = whiteBalance,
                flashMode = flashMode,
                shootingMode = shootingMode
            )
        )
    }

    /**
     * Host 广播取景帧（应**严格**限频：>= 200ms 间隔 + <= 200KB 单帧）。
     *
     * 控制策略由调用方（CameraRepository 端）负责，本方法只做透传。
     */
    suspend fun broadcastFrame(
        width: Int,
        height: Int,
        jpegBytes: ByteArray,
        isKeyFrame: Boolean = true
    ) {
        if (_localRole.value != MemberRole.Host) return
        if (jpegBytes.isEmpty()) return
        broadcast(
            CollabMessage.FrameBroadcast(
                senderPeerId = localPeerId,
                width = width,
                height = height,
                jpegBytes = jpegBytes,
                isKeyFrame = isKeyFrame
            )
        )
    }

    /**
     * 任意成员发文字。
     */
    suspend fun sendChat(text: String) {
        if (text.isBlank()) return
        if (_room.value == null) return
        broadcast(
            CollabMessage.ChatMessage(
                senderPeerId = localPeerId,
                senderName = localDisplayName,
                text = text
            )
        )
    }

    /**
     * Host 处理来自 Operator 的 [CollabMessage.CaptureRequest]。
     * 默认实现：构造 CaptureAck 失败（需接入 CameraRepository），
     * 实际项目中 ViewModel 监听 [incoming] 自行处理。
     */
    private suspend fun handleCaptureRequest(message: CollabMessage.CaptureRequest) {
        // 占位实现：Host 收到 capture_req 后应触发 CameraRepository.captureImage()
        // 并把结果通过 CaptureAck 广播。这里只输出日志，不实际执行。
        if (_localRole.value != MemberRole.Host) return
        // 真实实现应该：
        // 1. 调用 cameraRepository.captureImage(delayMs = message.delayMs)
        // 2. 等待结果
        // 3. 构造 CaptureAck 广播
        // 此处由 ViewModel 监听 incoming 后注入 capture 逻辑。
    }

    /**
     * 内部：把消息下发给 transport。
     */
    private suspend fun broadcast(message: CollabMessage) {
        val room = _room.value ?: return
        val json = CollabMessageSerializer.encode(message)
        transport.send(room.code, json)
    }

    /**
     * 内部：处理收到的消息，必要时更新房间状态。
     */
    private fun applyMessage(message: CollabMessage) {
        when (message) {
            is CollabMessage.MemberUpdate -> {
                // 房间快照覆盖本地
                if (_room.value == null || _room.value?.code == message.room.code) {
                    _room.value = message.room
                    // 更新自己的 role（以服务器下发的为准）
                    val me = message.room.members.firstOrNull { it.peerId == localPeerId }
                    if (me != null) _localRole.value = me.role
                }
            }
            is CollabMessage.CaptureRequest -> {
                // Host 收到过片请求：触发相机过片
                if (_localRole.value == MemberRole.Host) {
                    scope.launch { handleCaptureRequest(message) }
                }
            }
            is CollabMessage.JoinRequest -> {
                // Host 收到加入请求：广播 MemberUpdate（实际项目里应通过服务端）
                if (_localRole.value == MemberRole.Host) {
                    val current = _room.value
                    if (current != null && !current.members.any { it.peerId == message.senderPeerId }) {
                        val newRoom = current.copy(
                            members = current.members + CollabMember(
                                peerId = message.senderPeerId,
                                displayName = message.displayName,
                                role = message.requestedRole
                            )
                        )
                        _room.value = newRoom
                        scope.launch { broadcast(newRoom.toMemberUpdate()) }
                    }
                }
            }
            is CollabMessage.LeaveNotice -> {
                val current = _room.value
                if (current != null) {
                    val newRoom = current.copy(
                        members = current.members.filter { it.peerId != message.senderPeerId }
                    )
                    _room.value = newRoom
                }
            }
            else -> {
                // 其它消息：交给订阅方处理
            }
        }
        // 始终推入 incoming 给 UI 监听
        _incoming.tryEmit(message)
    }

    /**
     * 释放资源。
     */
    fun release() {
        scope.launch { leaveRoom("released") }
    }

    /**
     * CollabRoom 内部便利方法。
     */
    private fun CollabRoom.toMemberUpdate(): CollabMessage.MemberUpdate =
        CollabMessage.MemberUpdate(
            senderPeerId = localPeerId,
            room = this
        )
}
