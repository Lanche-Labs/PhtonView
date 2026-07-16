package com.phtontools.phtonview.collab

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * CollabMessage ↔ JSON 序列化器（迭代 #18）。
 *
 * 序列化约束：
 * - 顶层字段：`type` + 协议元数据 + 子类字段
 * - 枚举用 `name()` 字符串（Host/Operator/Viewer）
 * - ByteArray（FrameBroadcast.jpegBytes）走 Base64
 * - 反序列化容错：缺字段视为 null，缺 `type` 抛 IllegalArgumentException
 *
 * 为什么不直接用 kotlinx.serialization：
 * - 避免引入新依赖（项目里没有）
 * - JSONObject 是 Android framework 内置，0 成本
 * - 调试时可读性更好
 */
object CollabMessageSerializer {

    private const val FIELD_TYPE = "type"
    private const val FIELD_MSG_ID = "msgId"
    private const val FIELD_SENDER = "senderPeerId"
    private const val FIELD_TIMESTAMP = "timestampMs"
    private const val FIELD_VERSION = "protocolVersion"

    // 子类 type 字符串
    private const val TYPE_JOIN_REQ = "join_req"
    private const val TYPE_JOIN_RES = "join_res"
    private const val TYPE_MEMBER_UPDATE = "member_update"
    private const val TYPE_CAPTURE_REQ = "capture_req"
    private const val TYPE_CAPTURE_ACK = "capture_ack"
    private const val TYPE_SETTINGS = "settings"
    private const val TYPE_FRAME = "frame"
    private const val TYPE_CHAT = "chat"
    private const val TYPE_HEARTBEAT = "heartbeat"
    private const val TYPE_LEAVE = "leave"

    /**
     * 序列化。
     * @return UTF-8 JSON 字符串
     */
    fun encode(message: CollabMessage): String {
        val obj = JSONObject()
        obj.put(FIELD_TYPE, typeOf(message))
        obj.put(FIELD_MSG_ID, message.msgId)
        obj.put(FIELD_SENDER, message.senderPeerId)
        obj.put(FIELD_TIMESTAMP, message.timestampMs)
        obj.put(FIELD_VERSION, message.protocolVersion)

        when (message) {
            is CollabMessage.JoinRequest -> {
                obj.put("displayName", message.displayName)
                obj.put("requestedRole", message.requestedRole.name)
            }
            is CollabMessage.JoinResponse -> {
                obj.put("accepted", message.accepted)
                message.room?.let { obj.put("room", roomToJson(it)) }
                message.reason?.let { obj.put("reason", it) }
            }
            is CollabMessage.MemberUpdate -> {
                obj.put("room", roomToJson(message.room))
            }
            is CollabMessage.CaptureRequest -> {
                obj.put("delayMs", message.delayMs)
                obj.put("burstCount", message.burstCount)
            }
            is CollabMessage.CaptureAck -> {
                obj.put("requestMsgId", message.requestMsgId)
                obj.put("success", message.success)
                message.aperture?.let { obj.put("aperture", it) }
                message.shutter?.let { obj.put("shutter", it) }
                message.iso?.let { obj.put("iso", it) }
                message.errorMessage?.let { obj.put("errorMessage", it) }
            }
            is CollabMessage.SettingsSnapshot -> {
                obj.put("aperture", message.aperture)
                obj.put("shutter", message.shutter)
                obj.put("iso", message.iso)
                obj.put("ev", message.ev.toDouble())
                obj.put("whiteBalance", message.whiteBalance)
                obj.put("flashMode", message.flashMode)
                obj.put("shootingMode", message.shootingMode)
            }
            is CollabMessage.FrameBroadcast -> {
                obj.put("width", message.width)
                obj.put("height", message.height)
                obj.put("jpegBase64", Base64.getEncoder().encodeToString(message.jpegBytes))
                obj.put("isKeyFrame", message.isKeyFrame)
            }
            is CollabMessage.ChatMessage -> {
                obj.put("senderName", message.senderName)
                obj.put("text", message.text)
            }
            is CollabMessage.Heartbeat -> {
                obj.put("sequence", message.sequence)
                obj.put("uptimeMs", message.uptimeMs)
            }
            is CollabMessage.LeaveNotice -> {
                message.reason?.let { obj.put("reason", it) }
            }
        }
        return obj.toString()
    }

    /**
     * 反序列化。
     * @throws IllegalArgumentException 缺 type / 未知 type / 必填字段缺失
     */
    fun decode(jsonString: String): CollabMessage {
        val obj = JSONObject(jsonString)
        val type = obj.optString(FIELD_TYPE, "")
        if (type.isEmpty()) throw IllegalArgumentException("Missing type field")
        val msgId = obj.optString(FIELD_MSG_ID, java.util.UUID.randomUUID().toString())
        val sender = obj.optString(FIELD_SENDER, "")
        val ts = obj.optLong(FIELD_TIMESTAMP, System.currentTimeMillis())
        val ver = obj.optString(FIELD_VERSION, CollabMessage.PROTOCOL_VERSION)

        return when (type) {
            TYPE_JOIN_REQ -> CollabMessage.JoinRequest(
                msgId = msgId,
                senderPeerId = sender,
                displayName = obj.optString("displayName", "Unknown"),
                requestedRole = parseRole(obj.optString("requestedRole", "Viewer")),
                timestampMs = ts,
                protocolVersion = ver
            )
            TYPE_JOIN_RES -> CollabMessage.JoinResponse(
                msgId = msgId,
                senderPeerId = sender,
                accepted = obj.optBoolean("accepted", false),
                room = obj.optJSONObject("room")?.let { roomFromJson(it) },
                reason = obj.optString("reason", "").takeIf { it.isNotEmpty() },
                timestampMs = ts,
                protocolVersion = ver
            )
            TYPE_MEMBER_UPDATE -> CollabMessage.MemberUpdate(
                msgId = msgId,
                senderPeerId = sender,
                room = roomFromJson(obj.getJSONObject("room")),
                timestampMs = ts,
                protocolVersion = ver
            )
            TYPE_CAPTURE_REQ -> CollabMessage.CaptureRequest(
                msgId = msgId,
                senderPeerId = sender,
                delayMs = obj.optLong("delayMs", 0),
                burstCount = obj.optInt("burstCount", 1),
                timestampMs = ts,
                protocolVersion = ver
            )
            TYPE_CAPTURE_ACK -> CollabMessage.CaptureAck(
                msgId = msgId,
                senderPeerId = sender,
                requestMsgId = obj.optString("requestMsgId", ""),
                success = obj.optBoolean("success", false),
                aperture = obj.optString("aperture", "").takeIf { it.isNotEmpty() },
                shutter = obj.optString("shutter", "").takeIf { it.isNotEmpty() },
                iso = if (obj.has("iso")) obj.getInt("iso") else null,
                errorMessage = obj.optString("errorMessage", "").takeIf { it.isNotEmpty() },
                timestampMs = ts,
                protocolVersion = ver
            )
            TYPE_SETTINGS -> CollabMessage.SettingsSnapshot(
                msgId = msgId,
                senderPeerId = sender,
                aperture = obj.optString("aperture", ""),
                shutter = obj.optString("shutter", ""),
                iso = obj.optInt("iso", 400),
                ev = obj.optDouble("ev", 0.0).toFloat(),
                whiteBalance = obj.optString("whiteBalance", "Auto"),
                flashMode = obj.optString("flashMode", "Auto"),
                shootingMode = obj.optString("shootingMode", "M"),
                timestampMs = ts,
                protocolVersion = ver
            )
            TYPE_FRAME -> {
                val b64 = obj.optString("jpegBase64", "")
                CollabMessage.FrameBroadcast(
                    msgId = msgId,
                    senderPeerId = sender,
                    width = obj.optInt("width", 0),
                    height = obj.optInt("height", 0),
                    jpegBytes = if (b64.isEmpty()) ByteArray(0) else Base64.getDecoder().decode(b64),
                    isKeyFrame = obj.optBoolean("isKeyFrame", true),
                    timestampMs = ts,
                    protocolVersion = ver
                )
            }
            TYPE_CHAT -> CollabMessage.ChatMessage(
                msgId = msgId,
                senderPeerId = sender,
                senderName = obj.optString("senderName", ""),
                text = obj.optString("text", ""),
                timestampMs = ts,
                protocolVersion = ver
            )
            TYPE_HEARTBEAT -> CollabMessage.Heartbeat(
                msgId = msgId,
                senderPeerId = sender,
                sequence = obj.optInt("sequence", 0),
                uptimeMs = obj.optLong("uptimeMs", 0),
                timestampMs = ts,
                protocolVersion = ver
            )
            TYPE_LEAVE -> CollabMessage.LeaveNotice(
                msgId = msgId,
                senderPeerId = sender,
                reason = obj.optString("reason", "").takeIf { it.isNotEmpty() },
                timestampMs = ts,
                protocolVersion = ver
            )
            else -> throw IllegalArgumentException("Unknown collab message type: $type")
        }
    }

    private fun typeOf(message: CollabMessage): String = when (message) {
        is CollabMessage.JoinRequest -> TYPE_JOIN_REQ
        is CollabMessage.JoinResponse -> TYPE_JOIN_RES
        is CollabMessage.MemberUpdate -> TYPE_MEMBER_UPDATE
        is CollabMessage.CaptureRequest -> TYPE_CAPTURE_REQ
        is CollabMessage.CaptureAck -> TYPE_CAPTURE_ACK
        is CollabMessage.SettingsSnapshot -> TYPE_SETTINGS
        is CollabMessage.FrameBroadcast -> TYPE_FRAME
        is CollabMessage.ChatMessage -> TYPE_CHAT
        is CollabMessage.Heartbeat -> TYPE_HEARTBEAT
        is CollabMessage.LeaveNotice -> TYPE_LEAVE
    }

    private fun parseRole(s: String): MemberRole = try {
        MemberRole.valueOf(s)
    } catch (e: Exception) {
        MemberRole.Viewer
    }

    private fun memberToJson(m: CollabMember): JSONObject = JSONObject().apply {
        put("peerId", m.peerId)
        put("displayName", m.displayName)
        put("role", m.role.name)
        put("joinedAtMs", m.joinedAtMs)
    }

    private fun memberFromJson(obj: JSONObject): CollabMember = CollabMember(
        peerId = obj.optString("peerId", ""),
        displayName = obj.optString("displayName", ""),
        role = parseRole(obj.optString("role", "Viewer")),
        joinedAtMs = obj.optLong("joinedAtMs", System.currentTimeMillis())
    )

    private fun roomToJson(room: CollabRoom): JSONObject = JSONObject().apply {
        put("code", room.code)
        put("hostPeerId", room.hostPeerId)
        val arr = JSONArray()
        room.members.forEach { arr.put(memberToJson(it)) }
        put("members", arr)
        put("createdAtMs", room.createdAtMs)
    }

    private fun roomFromJson(obj: JSONObject): CollabRoom {
        val arr = obj.optJSONArray("members") ?: JSONArray()
        val members = (0 until arr.length()).map { i -> memberFromJson(arr.getJSONObject(i)) }
        return CollabRoom(
            code = obj.optString("code", ""),
            hostPeerId = obj.optString("hostPeerId", ""),
            members = members,
            createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis())
        )
    }
}
