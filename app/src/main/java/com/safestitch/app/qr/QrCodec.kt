package com.safestitch.app.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.oned.Code128Writer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Offline QR and 1D/2D barcode encoding/decoding (ZXing).
 * Supports QR_CODE, DATA_MATRIX, AZTEC, PDF_417, CODE_128, CODE_39, CODE_93, EAN_13, EAN_8, UPC_A, UPC_E, ITF, and CODABAR.
 */
object QrCodec {
    val SUPPORTED_FORMATS: List<BarcodeFormat> = listOf(
        BarcodeFormat.QR_CODE,
        BarcodeFormat.DATA_MATRIX,
        BarcodeFormat.AZTEC,
        BarcodeFormat.PDF_417,
        BarcodeFormat.CODE_128,
        BarcodeFormat.CODE_39,
        BarcodeFormat.CODE_93,
        BarcodeFormat.EAN_13,
        BarcodeFormat.EAN_8,
        BarcodeFormat.UPC_A,
        BarcodeFormat.UPC_E,
        BarcodeFormat.ITF,
        BarcodeFormat.CODABAR,
    )

    private val decodeHints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to SUPPORTED_FORMATS,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    /**
     * Decodes a QR code or 1D/2D barcode from an 8-bit luminance plane such as CameraX's Y plane.
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
        val reader = MultiFormatReader().apply { setHints(decodeHints) }
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: ReaderException) {
            // Also accept light-on-dark labels.
            try {
                reader.reset()
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source.invert()))).text
            } catch (_: ReaderException) {
                null
            }
        }
    }

    /** Decodes a QR code or barcode from a still image (ARGB pixels), e.g. a photo picked from the gallery. */
    fun decodeArgb(pixels: IntArray, width: Int, height: Int): String? {
        require(width > 0 && height > 0 && pixels.size >= width * height) { "Invalid image" }
        val source = RGBLuminanceSource(width, height, pixels)
        val hints = decodeHints + (DecodeHintType.TRY_HARDER to true)
        val reader = MultiFormatReader().apply { setHints(hints) }
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: ReaderException) {
            decode(source)
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

    /** Encodes a 1D Code-128 equipment barcode matrix for equipment tags and tests. */
    fun encodeBarcode128Modules(text: String, width: Int = 240, height: Int = 64): BitMatrix =
        Code128Writer().encode(
            text,
            BarcodeFormat.CODE_128,
            width,
            height,
            mapOf(EncodeHintType.MARGIN to 10),
        )
}
