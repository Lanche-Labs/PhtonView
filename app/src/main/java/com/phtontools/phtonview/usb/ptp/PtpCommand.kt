package com.phtontools.phtonview.usb.ptp

import com.phtontools.phtonview.util.AppLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PTP command / data / response container helpers.
 */
class PtpCommand(
    val operationCode: Short,
    val transactionId: Int,
    val parameters: IntArray = intArrayOf()
) {

    /**
     * Encode command as USB bulk-transfer byte stream.
     */
    fun encode(): ByteArray {
        val length = 12 + parameters.size * 4
        val buffer = ByteBuffer.allocate(length)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(length)
        buffer.putShort(PtpConstants.CONTAINER_TYPE_COMMAND)
        buffer.putShort(operationCode)
        buffer.putInt(transactionId)
        parameters.forEach { buffer.putInt(it) }
        return buffer.array()
    }

    companion object {
        /**
         * Decode a PTP response packet.
         */
        fun decodeResponse(data: ByteArray): Pair<Short, IntArray> {
            val buffer = ByteBuffer.wrap(data)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            buffer.getInt() // length
            buffer.getShort() // container type
            val responseCode = buffer.getShort()
            buffer.getInt() // transaction id
            val params = mutableListOf<Int>()
            while (buffer.remaining() >= 4) {
                params.add(buffer.getInt())
            }
            return responseCode to params.toIntArray()
        }

        /**
         * Decode a data container into raw bytes (skip 12-byte header).
         */
        fun decodeDataPayload(data: ByteArray): ByteArray {
            if (data.size < 12) return data
            return data.copyOfRange(12, data.size)
        }

        /**
         * Decode an array of 32-bit integers from a data payload.
         */
        fun decodeIntArray(data: ByteArray): IntArray {
            val payload = decodeDataPayload(data)
            if (payload.size < 4) return intArrayOf()
            val buffer = ByteBuffer.wrap(payload)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            val count = buffer.getInt()
            val result = IntArray(count)
            for (i in 0 until count) {
                if (buffer.remaining() < 4) break
                result[i] = buffer.getInt()
            }
            return result
        }

        /**
         * Decode PTP ObjectInfo dataset.
         * Returns a triple of (objectHandle, filename, objectFormat).
         */
        fun decodeObjectInfo(data: ByteArray): ObjectInfo? {
            val payload = decodeDataPayload(data)
            if (payload.size < 12) return null
            val buffer = ByteBuffer.wrap(payload)
            buffer.order(ByteOrder.LITTLE_ENDIAN)

            return try {
                buffer.getInt() // StorageID
                val format = buffer.getShort()
                buffer.getShort() // protectionStatus
                val compressedSize = buffer.getInt()
                buffer.getShort() // thumbFormat
                buffer.getInt() // thumbCompressedSize
                buffer.getInt() // thumbPixWidth
                buffer.getInt() // thumbPixHeight
                val imagePixWidth = buffer.getInt()
                val imagePixHeight = buffer.getInt()
                buffer.getInt() // imageBitDepth
                buffer.getInt() // parentObject
                buffer.getShort() // associationType
                buffer.getInt() // associationDesc
                buffer.getInt() // sequenceNumber
                val filename = readPtpString(buffer)
                val captureDate = readPtpString(buffer)
                readPtpString(buffer) // modificationDate
                readPtpString(buffer) // keywords

                ObjectInfo(
                    filename = filename.ifBlank { "unknown" },
                    objectFormat = format,
                    compressedSize = compressedSize.toLong() and 0xFFFFFFFFL,
                    imagePixWidth = imagePixWidth,
                    imagePixHeight = imagePixHeight,
                    captureDate = captureDate
                )
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Decode PTP DeviceInfo dataset and return the model string.
         */
        fun decodeDeviceInfoModel(data: ByteArray): String {
            val payload = decodeDataPayload(data)
            if (payload.size < 12) return "Unknown"
            val buffer = ByteBuffer.wrap(payload)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            return try {
                buffer.getShort() // StandardVersion
                buffer.getInt() // VendorExtensionID
                buffer.getShort() // VendorExtensionVersion
                readPtpString(buffer) // skip vendor extension desc
                buffer.getShort() // FunctionalMode
                buffer.position(buffer.position() + buffer.getInt() * 2) // operations
                buffer.position(buffer.position() + buffer.getInt() * 2) // events
                buffer.position(buffer.position() + buffer.getInt() * 2) // properties
                buffer.position(buffer.position() + buffer.getInt() * 2) // capture formats
                buffer.position(buffer.position() + buffer.getInt() * 2) // image formats
                readPtpString(buffer) // manufacturer
                readPtpString(buffer) // model
            } catch (e: Exception) {
                AppLogger.e("decodeDeviceInfoModel failed: ${e.message}", e)
                "Unknown"
            }
        }

        /**
         * 从 DeviceInfo 原始字节中读取 VendorExtensionID（偏移 8，4 字节）。
         */
        fun decodeVendorExtensionId(data: ByteArray): Int? {
            val payload = decodeDataPayload(data)
            if (payload.size < 12) return null
            return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).let {
                it.getShort() // StandardVersion
                it.getInt()   // VendorExtensionID
            }
        }

        /**
         * Decode a length-prefixed UTF-16LE PTP string from [buffer].
         * ponytail: kept simple; caller checks remaining bytes before use.
         */
        fun readPtpString(buffer: ByteBuffer): String {
            if (buffer.remaining() < 1) return ""
            val lengthByte = buffer.get().toInt() and 0xFF
            if (lengthByte == 0) return ""
            val charCount = lengthByte - 1
            if (buffer.remaining() < lengthByte * 2) return ""
            val chars = CharArray(charCount)
            for (i in 0 until charCount) {
                chars[i] = buffer.getShort().toInt().toChar()
            }
            buffer.getShort() // consume null terminator
            return String(chars)
        }

        /**
         * Encode a PTP string: 1 byte length (including null terminator) +
         * UTF-16LE characters + 2 byte null terminator.
         * ponytail: single source of truth, reused by repository and connection layers.
         */
        fun encodePtpString(text: String): ByteArray {
            val chars = text.toCharArray()
            val bytes = ByteArray(1 + chars.size * 2 + 2)
            bytes[0] = (chars.size + 1).toByte()
            ByteBuffer.wrap(bytes, 1, chars.size * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply { chars.forEach { putShort(it.code.toShort()) } }
            return bytes
        }
    }
}

data class ObjectInfo(
    val filename: String,
    val objectFormat: Short,
    val compressedSize: Long,
    val imagePixWidth: Int,
    val imagePixHeight: Int,
    val captureDate: String
)
