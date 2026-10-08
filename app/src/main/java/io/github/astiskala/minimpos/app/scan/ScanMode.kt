package io.github.astiskala.minimpos.app.scan

import com.google.zxing.BarcodeFormat

/**
 * What the camera scanner looks for.
 *
 * @property formats The ZXing formats decoded in this mode.
 */
enum class ScanMode(
    val formats: List<BarcodeFormat>,
) {
    /** QR codes only: refund codes on receipts and catalogue transfer codes. */
    QR(listOf(BarcodeFormat.QR_CODE)),

    /** Shopper wallet payment codes, rendered as QR or Code 128; never matched against product SKUs. */
    WALLET(listOf(BarcodeFormat.QR_CODE, BarcodeFormat.CODE_128)),

    /** Product barcodes (retail 1D codes, plus QR and Data Matrix), matched against SKUs. */
    BARCODE(
        listOf(
            BarcodeFormat.EAN_13,
            BarcodeFormat.EAN_8,
            BarcodeFormat.UPC_A,
            BarcodeFormat.UPC_E,
            BarcodeFormat.CODE_128,
            BarcodeFormat.CODE_39,
            BarcodeFormat.CODE_93,
            BarcodeFormat.ITF,
            BarcodeFormat.QR_CODE,
            BarcodeFormat.DATA_MATRIX,
        ),
    ),
}
