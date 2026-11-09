package com.safestitch.app.qr

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX analyzer that decodes QR codes from the luminance (Y) plane of each frame.
 * Reports the first code found, then pauses until [resume] is called.
 */
class QrCodeAnalyzer(private val onQrCode: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val paused = AtomicBoolean(false)
    private var buffer = ByteArray(0)

    override fun analyze(image: ImageProxy) {
        try {
            if (paused.get()) return
            val plane = image.planes.firstOrNull() ?: return
            val rowStride = plane.rowStride
            val needed = rowStride * image.height
            if (buffer.size < needed) buffer = ByteArray(needed)
            val source = plane.buffer
            source.rewind()
            source.get(buffer, 0, minOf(source.remaining(), needed))
            val text = QrCodec.decodeLuminance(buffer, rowStride, image.width, image.height) ?: return
            if (paused.compareAndSet(false, true)) onQrCode(text)
        } finally {
            image.close()
        }
    }

    fun resume() = paused.set(false)
}
