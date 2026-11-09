package com.safestitch.app.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Offline QR encoding/decoding (ZXing). Pure JVM code so it is unit-tested on the Mac. */
object QrCodec {
    private val decodeHints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    /**
     * Decodes a QR code from an 8-bit luminance plane such as CameraX's Y plane.
     * [rowStride] may be wider than [width] (camera buffers are often padded).
     */
    fun decodeLuminance(data: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        require(width > 0 && height > 0) { "Empty image" }
        require(rowStride >= width) { "rowStride must be >= width" }
        require(data.size >= rowStride * (height - 1) + width) { "Buffer too small" }
        val source = PlanarYUVLuminanceSource(data, rowStride, height, 0, 0, width, height, false)
        return decode(source)
    }

    fun decode(source: LuminanceSource): String? {
        val reader = QRCodeReader()
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), decodeHints).text
        } catch (_: ReaderException) {
            // Also accept light-on-dark labels.
            try {
                reader.reset()
                reader.decode(BinaryBitmap(HybridBinarizer(source.invert())), decodeHints).text
            } catch (_: ReaderException) {
                null
            }
        }
    }

    /** One matrix cell per QR module, including a 2-module quiet zone. */
    fun encodeModules(text: String): BitMatrix = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(
            EncodeHintType.MARGIN to 2,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        ),
    )
}
