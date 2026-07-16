package com.phtontools.phtonview.collab

import java.util.UUID

/**
 * 房间协议（迭代 #18：远程协作 / 房间同步 / 远程过片）。
 *
 * 概念：
 * - **Room** = 一次拍摄协作会话，由 6 位字母数字 code 标识（如 "PHTN42"）
 * - **Member** = 加入房间的客户端，每个成员有唯一 peerId + displayName + role
 * - **Message** = 房间内传输的最小单元，按 type 分发
 * - **Transport** = 物理层，协议独立于传输（同一份消息可通过 LAN mDNS、WiFi 直连、远程转发）
 *
 * 设计目标：
 * - **轻量**：消息 < 1KB（除帧广播外），用 JSON 序列化便于调试
 * - **幂等**：每条消息带 msgId，重复投递可识别
 * - **可降级**：丢包 / 离线时本地缓存 5s 内的操作，恢复后批量回放
 * - **可扩展**：新增消息类型只需在 [CollabMessage] 加 sealed 子类
 *
 * 协议位置：
 * ```
 * UI / ViewModel
 *   ↓
 * CollabController  (高级 API: createRoom / joinRoom / broadcastCapture)
 *   ↓
 * CollabTransport   (UDP/WS 收发)
 *   ↓
 * [CollabMessage]   (本文件定义)
 * ```
 */

/**
 * 房间成员角色。
 *
 * - **Host**：拥有相机物理连接，能发起 capture/settings
 * - **Operator**：受 host 授权，可发送 capture 请求；host 收到后决定是否执行
 * - **Viewer**：纯观察者，仅接收 frame 广播与设置同步
 */
enum class MemberRole {
    Host, Operator, Viewer
}

/**
 * 房间成员。
 */
data class CollabMember(
    val peerId: String,
    val displayName: String,
    val role: MemberRole,
    val joinedAtMs: Long = System.currentTimeMillis()
) {
    companion object {
        /** 随机生成 peerId（UUID 短码）。 */
        fun newPeerId(): String = UUID.randomUUID().toString().take(8).uppercase()
    }
}

/**
 * 房间。
 */
data class CollabRoom(
    val code: String,
    val hostPeerId: String,
    val members: List<CollabMember> = emptyList(),
    val createdAtMs: Long = System.currentTimeMillis()
) {
    init {
        require(code.length in 4..8) { "Room code must be 4-8 chars, got '$code'" }
        require(code.all { it.isLetterOrDigit() }) { "Room code must be alphanumeric" }
    }
}

/**
 * 房间消息类型。
 *
 * 协议版本（递增且与旧版本不兼容时 major +1）：
 * - 1.0：初版
 * - 1.1：+ ChatMessage、SettingsSnapshotMessage
 */
sealed class CollabMessage {

    /** 协议元数据。 */
    abstract val msgId: String
    abstract val senderPeerId: String
    abstract val timestampMs: Long
    abstract val protocolVersion: String

    /**
     * 客户端 → 服务端：请求加入房间。
     */
    data class JoinRequest(
        override val msgId: String = UUID.randomUUID().toString(),
        override val senderPeerId: String,
        val displayName: String,
        val requestedRole: MemberRole = MemberRole.Viewer,
        override val timestampMs: Long = System.currentTimeMillis(),
        override val protocolVersion: String = PROTOCOL_VERSION
    ) : CollabMessage()

    /**
     * 服务端 → 客户端：加入结果（成功返回房间快照，失败返回 reason）。
     */
    data class JoinResponse(
        override val msgId: String = UUID.randomUUID().toString(),
        override val senderPeerId: String,
        val accepted: Boolean,
        val room: CollabRoom? = null,
        val reason: String? = null,
        override val timestampMs: Long = System.currentTimeMillis(),
        override val protocolVersion: String = PROTOCOL_VERSION
    ) : CollabMessage()

    /**
     * 服务端 → 全员：成员变更（加入/离开/角色变更）。
     */
    data class MemberUpdate(
        override val msgId: String = UUID.randomUUID().toString(),
        override val senderPeerId: String,
        val room: CollabRoom,
        override val timestampMs: Long = System.currentTimeMillis(),
        override val protocolVersion: String = PROTOCOL_VERSION
    ) : CollabMessage()

    /**
     * Operator → Host：请求过片。
     *
     * Host 收到后：
     * 1. 校验 Operator 权限
     * 2. 调用 CameraRepository.captureImage()
     * 3. 广播 [CaptureAck] 反馈结果
     */
    data class CaptureRequest(
        override val msgId: String = UUID.randomUUID().toString(),
        override val senderPeerId: String,
        val delayMs: Long = 0,
        val burstCount: Int = 1,
        override val timestampMs: Long = System.currentTimeMillis(),
        override val protocolVersion: String = PROTOCOL_VERSION
    ) : CollabMessage()

    /**
     * Host → 全员：过片结果（成功/失败 + 拍摄参数）。
     */
    data class CaptureAck(
        override val msgId: String = UUID.randomUUID().toString(),
        override val senderPeerId: String,
        val requestMsgId: String,
        val success: Boolean,
        val aperture: String? = null,
        val shutter: String? = null,
        val iso: Int? = null,
        val errorMessage: String? = null,
        override val timestampMs: Long = System.currentTimeMillis(),
        override val protocolVersion: String = PROTOCOL_VERSION
    ) : CollabMessage()

    /**
     * Host → 全员：相机当前设置快照（ISO/光圈/快门/WB/闪光）。
     *
     * 触发时机：
     * - 房间内任何设置变更后立即推送
     * - 新成员加入后立即推送
     * - 每 30s 周期性推送一次（兜底）
     */
    data class SettingsSnapshot(
        override val msgId: String = UUID.randomUUID().toString(),
        override val senderPeerId: String,
        val aperture: String,
        val shutter: String,
        val iso: Int,
        val ev: Float,
        val whiteBalance: String,
        val flashMode: String,
        val shootingMode: String,
        override val timestampMs: Long = System.currentTimeMillis(),
        override val protocolVersion: String = PROTOCOL_VERSION
    ) : CollabMessage()

    /**
     * Host → 全员：取景帧广播。
     *
     * 为避免带宽爆炸（30fps × 1MB = 30MB/s），仅在以下条件广播：
     * - 房间人数 >= 2
     * - 帧间隔 >= 200ms（5fps，远低于取景帧原始 30fps）
     * - 帧大小 <= 200KB（强制限速）
     * - 接收端 Viewer 数 <= 3（超过则降级为关键帧 only）
     */
    data class FrameBroadcast(
        override val msgId: String = UUID.randomUUID().toString(),
        override val senderPeerId: String,
        val width: Int,
        val height: Int,
        val jpegBytes: ByteArray,
        val isKeyFrame: Boolean = true,
        override val timestampMs: Long = System.currentTimeMillis(),
        override val protocolVersion: String = PROTOCOL_VERSION
    ) : CollabMessage() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FrameBroadcast) return false
            return msgId == other.msgId
        }

        override fun hashCode(): Int = msgId.hashCode()
    }

    /**
     * 全员：文本聊天（用于拍摄调度口令："3-2-1"、"再来一张"）。
     */
    data class ChatMessage(
        override val msgId: String = UUID.randomUUID().toString(),
        override val senderPeerId: String,
        val senderName: String,
        val text: String,
        override val timestampMs: Long = System.currentTimeMillis(),
        override val protocolVersion: String = PROTOCOL_VERSION
    ) : CollabMessage()

    /**
     * 心跳保活（每 5s 一次，丢失 3 次 = 离线）。
     */
    data class Heartbeat(
        override val msgId: String = UUID.randomUUID().toString(),
        override val senderPeerId: String,
        val sequence: Int,
        val uptimeMs: Long,
        override val timestampMs: Long = System.currentTimeMillis(),
        override val protocolVersion: String = PROTOCOL_VERSION
    ) : CollabMessage()

    /**
     * 全员：离开房间。
     */
    data class LeaveNotice(
        override val msgId: String = UUID.randomUUID().toString(),
        override val senderPeerId: String,
        val reason: String? = null,
        override val timestampMs: Long = System.currentTimeMillis(),
        override val protocolVersion: String = PROTOCOL_VERSION
    ) : CollabMessage()

    companion object {
        /** 协议版本号，破坏性变更递增 major。 */
        const val PROTOCOL_VERSION: String = "1.1"
    }
}
