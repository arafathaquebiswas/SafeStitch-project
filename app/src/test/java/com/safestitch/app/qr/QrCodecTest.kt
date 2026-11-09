package com.safestitch.app.qr

import com.safestitch.app.domain.QrPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class QrCodecTest {
    /** Renders the QR modules into an 8-bit luminance buffer like a camera Y plane. */
    private fun render(text: String, scale: Int, padding: Int, rowStride: Int? = null): Triple<ByteArray, Int, Int> {
        val matrix = QrCodec.encodeModules(text)
        val width = matrix.width * scale + padding * 2
        val height = matrix.height * scale + padding * 2
        val stride = rowStride ?: width
        val data = ByteArray(stride * height) { 0xFF.toByte() }
        for (y in 0 until matrix.height * scale) {
            for (x in 0 until matrix.width * scale) {
                if (matrix.get(x / scale, y / scale)) data[(y + padding) * stride + x + padding] = 0
            }
        }
        return Triple(data, width, height)
    }

    @Test
    fun decodesLabelPayloadFromLuminancePlane() {
        val payload = QrPayload.forCheckpoint("SS-LINE-04")
        val (data, width, height) = render(payload, scale = 6, padding = 40)
        assertEquals(payload, QrCodec.decodeLuminance(data, width, width, height))
    }

    @Test
    fun handlesPaddedCameraRowStride() {
        val payload = QrPayload.forCheckpoint("FE-0012")
        val (_, width, _) = render(payload, scale = 5, padding = 30)
        val stride = width + 64
        val (data, w, h) = render(payload, scale = 5, padding = 30, rowStride = stride)
        assertEquals(payload, QrCodec.decodeLuminance(data, stride, w, h))
    }

    @Test
    fun decodesInvertedLabel() {
        val payload = QrPayload.forCheckpoint("SS-ELEC-01")
        val (data, width, height) = render(payload, scale = 6, padding = 40)
        for (i in data.indices) data[i] = (255 - (data[i].toInt() and 0xFF)).toByte()
        assertEquals(payload, QrCodec.decodeLuminance(data, width, width, height))
    }

    @Test
    fun returnsNullWhenNoQrCodeIsVisible() {
        val random = Random(42)
        val width = 320
        val height = 240
        val noise = ByteArray(width * height) { random.nextInt(256).toByte() }
        assertNull(QrCodec.decodeLuminance(noise, width, width, height))
    }
}
