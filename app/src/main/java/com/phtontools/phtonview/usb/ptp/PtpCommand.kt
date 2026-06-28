package com.phtontools.phtonview.usb.ptp

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

            try {
                buffer.getInt() // StorageID
                val format = buffer.getShort()
                val protectionStatus = buffer.getShort()
                val compressedSize = buffer.getInt()
                val thumbFormat = buffer.getShort()
                val thumbCompressedSize = buffer.getInt()
                val thumbPixWidth = buffer.getInt()
                val thumbPixHeight = buffer.getInt()
                val imagePixWidth = buffer.getInt()
                val imagePixHeight = buffer.getInt()
                val imageBitDepth = buffer.getInt()
                val parentObject = buffer.getInt()
                val associationType = buffer.getShort()
                val associationDesc = buffer.getInt()
                val sequenceNumber = buffer.getInt()
                val filename = readPtpString(buffer)
                val captureDate = readPtpString(buffer)
                val modificationDate = readPtpString(buffer)
                val keywords = readPtpString(buffer)

                return ObjectInfo(
                    filename = filename ?: "unknown",
                    objectFormat = format,
                    compressedSize = compressedSize.toLong() and 0xFFFFFFFFL,
                    imagePixWidth = imagePixWidth,
                    imagePixHeight = imagePixHeight,
                    captureDate = captureDate ?: ""
                )
            } catch (e: Exception) {
                return null
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
            try {
                buffer.getShort() // StandardVersion
                buffer.getInt() // VendorExtensionID + VendorExtensionVersion
                buffer.getShort() // VendorExtensionDesc length byte handled below
                // Skip vendor extension desc string
                readPtpString(buffer)
                val functionalMode = buffer.getShort()
                val operationsCount = buffer.getInt()
                buffer.position(buffer.position() + operationsCount * 2)
                val eventsCount = buffer.getInt()
                buffer.position(buffer.position() + eventsCount * 2)
                val propertiesCount = buffer.getInt()
                buffer.position(buffer.position() + propertiesCount * 2)
                val captureFormatsCount = buffer.getInt()
                buffer.position(buffer.position() + captureFormatsCount * 2)
                val imageFormatsCount = buffer.getInt()
                buffer.position(buffer.position() + imageFormatsCount * 2)
                readPtpString(buffer) // Manufacturer
                return readPtpString(buffer) ?: "Unknown" // Model
            } catch (e: Exception) {
                return "Unknown"
            }
        }

        private fun readPtpString(buffer: ByteBuffer): String? {
            if (buffer.remaining() < 1) return ""
            val lengthByte = buffer.get().toInt() and 0xFF
            if (lengthByte == 0) return ""
            val charCount = lengthByte - 1
            if (buffer.remaining() < charCount * 2) return ""
            val chars = CharArray(charCount)
            for (i in 0 until charCount) {
                chars[i] = buffer.getShort().toChar()
            }
            return String(chars)
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
