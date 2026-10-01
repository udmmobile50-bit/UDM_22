package com.example.printer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.example.data.entity.AppSettingsEntity
import com.example.data.entity.PaymentEntity
import com.example.data.entity.RepairEntity
import com.example.data.entity.RepairItemEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReceiptBitmapGenerator {

    const val BITMAP_WIDTH = 384 // 48 mm printable width at 203 DPI

    /**
     * Converts an ARGB_8888 Bitmap to a 1-bit monochrome byte array (48 bytes per row).
     * Pixel luminance threshold: <= 190 is converted to a black dot (bit 1, heater on),
     * > 190 is converted to white (bit 0, heater off).
     */
    fun convertTo1BitMonochrome(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val bytesPerRow = (width + 7) / 8 // 48 bytes for 384 px
        val out = ByteArray(bytesPerRow * height)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val threshold = 190

        for (y in 0 until height) {
            val rowOffset = y * bytesPerRow
            val pixelRowOffset = y * width
            for (byteX in 0 until bytesPerRow) {
                var currentByte = 0
                val pxBase = byteX * 8
                for (bit in 0 until 8) {
                    val px = pxBase + bit
                    if (px < width) {
                        val pixel = pixels[pixelRowOffset + px]
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF
                        val alpha = (pixel shr 24) and 0xFF

                        // Standard ITU-R BT.601 luminance
                        val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                        if (alpha > 50 && lum <= threshold) {
                            currentByte = currentByte or (128 shr bit)
                        }
                    }
                }
                out[rowOffset + byteX] = currentByte.toByte()
            }
        }
        return out
    }

    fun sanitizeText(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val cleanWhitespace = input.replace("\r\n", " ")
            .replace("\r", " ")
            .replace("\n", " ")
            .replace("\t", " ")

        val sb = StringBuilder(cleanWhitespace.length)
        var i = 0
        while (i < cleanWhitespace.length) {
            val codePoint = cleanWhitespace.codePointAt(i)
            val charCount = Character.charCount(codePoint)

            when {
                // Control chars (0..31, 127..159)
                codePoint < 32 || (codePoint in 127..159) -> {
                    // Skip control chars
                }
                // Emojis / surrogate blocks (0x1F000..0x1FFFF, 0x2600..0x27BF, 0xFE00..0xFE0F)
                codePoint in 0x1F000..0x1FFFF || codePoint in 0x2600..0x27BF || codePoint in 0xFE00..0xFE0F -> {
                    sb.append(" ")
                }
                // Standard ASCII printable (32..126)
                codePoint in 32..126 -> {
                    sb.appendCodePoint(codePoint)
                }
                // Sinhala (0x0D80..0x0DFF)
                codePoint in 0x0D80..0x0DFF -> {
                    sb.appendCodePoint(codePoint)
                }
                // Tamil (0x0B80..0x0BFF)
                codePoint in 0x0B80..0x0BFF -> {
                    sb.appendCodePoint(codePoint)
                }
                // Latin-1 Supplement & Extended (0x00A0..0x024F)
                codePoint in 0x00A0..0x024F -> {
                    sb.appendCodePoint(codePoint)
                }
                // Standard Letter, Digit, Punctuation or Space
                Character.isLetterOrDigit(codePoint) || Character.isWhitespace(codePoint) -> {
                    sb.appendCodePoint(codePoint)
                }
                // Unassigned or Private Use or other surrogates -> fallback '?'
                else -> {
                    if (Character.isDefined(codePoint)) {
                        sb.appendCodePoint(codePoint)
                    } else {
                        sb.append("?")
                    }
                }
            }
            i += charCount
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    fun formatCurrency(currency: String, amount: Double): String {
        val cur = currency.ifBlank { "Rs." }
        return if (amount % 1.0 == 0.0) {
            "$cur${String.format(Locale.US, "%,d", amount.toLong())}"
        } else {
            "$cur${String.format(Locale.US, "%,.2f", amount)}"
        }
    }

    fun formatTimestamp(timestamp: Long, fallbackDate: String, fallbackTime: String): String {
        return try {
            if (timestamp > 0) {
                val sdf = SimpleDateFormat("dd/MM/yyyy  HH:mm", Locale.US)
                sdf.format(Date(timestamp))
            } else if (fallbackDate.isNotBlank()) {
                val parts = fallbackDate.trim().split("-")
                val formattedDate = if (parts.size == 3) "${parts[2]}/${parts[1]}/${parts[0]}" else fallbackDate.trim()
                val timeStr = fallbackTime.trim()
                if (timeStr.isNotBlank()) "$formattedDate  $timeStr" else formattedDate
            } else {
                val sdf = SimpleDateFormat("dd/MM/yyyy  HH:mm", Locale.US)
                sdf.format(Date())
            }
        } catch (_: Exception) {
            val sdf = SimpleDateFormat("dd/MM/yyyy  HH:mm", Locale.US)
            sdf.format(Date())
        }
    }

    private fun formatJobNumber(raw: String): String {
        val clean = sanitizeText(raw)
        return if (clean.isNotBlank() && clean.all { it.isDigit() }) {
            String.format(Locale.US, "%04d", clean.toIntOrNull() ?: 0)
        } else if (clean.isNotBlank()) {
            clean
        } else {
            "0001"
        }
    }

    /**
     * Generates a Customer Handover Receipt Bitmap with full customization support.
     */
    fun generateCustomerReceipt(
        repair: RepairEntity,
        items: List<RepairItemEntity>,
        payments: List<PaymentEntity>,
        settings: AppSettingsEntity,
        config: CustomerReceiptConfig = CustomerReceiptConfig()
    ): Bitmap {
        val cur = if (settings.currency.isNotBlank()) settings.currency.trim() else "Rs."
        val lines = mutableListOf<RenderItem>()

        // 1. SHOP INFORMATION (HEADER)
        val shopNameText = sanitizeText(config.shopName.ifBlank { settings.shopName.ifBlank { "UDM MOBILE REPAIR" } })
        val subtitleText = sanitizeText(config.subtitle.ifBlank { "Mobile Phone Repair" })
        val phoneRaw = config.phone.ifBlank { settings.shopPhone.ifBlank { "07XXXXXXXX" } }
        val phoneText = if (phoneRaw.startsWith("Tel:", ignoreCase = true)) phoneRaw else "Tel: $phoneRaw"
        val addressText = sanitizeText(config.address.ifBlank { settings.shopAddress })
        val whatsappText = sanitizeText(config.whatsapp.ifBlank { settings.whatsappNumber })

        if (config.showShopName && shopNameText.isNotBlank()) {
            lines.add(
                RenderItem.Text(
                    text = shopNameText,
                    isHeader = true,
                    isLarge = true,
                    isBold = config.boldShopName,
                    alignment = config.headerAlignment
                )
            )
        }
        if (config.showSubtitle && subtitleText.isNotBlank()) {
            lines.add(
                RenderItem.Text(
                    text = subtitleText,
                    isHeader = true,
                    isBold = false,
                    alignment = config.headerAlignment
                )
            )
        }
        if (config.showPhone && phoneText.isNotBlank()) {
            lines.add(
                RenderItem.Text(
                    text = phoneText,
                    isHeader = true,
                    isBold = false,
                    alignment = config.headerAlignment
                )
            )
        }
        if (config.showAddress && addressText.isNotBlank()) {
            lines.add(
                RenderItem.Text(
                    text = addressText,
                    isHeader = true,
                    isBold = false,
                    alignment = config.headerAlignment
                )
            )
        }
        if (whatsappText.isNotBlank()) {
            val waLabel = if (whatsappText.startsWith("WA:", ignoreCase = true) || whatsappText.startsWith("WhatsApp:", ignoreCase = true)) {
                whatsappText
            } else {
                "WhatsApp: $whatsappText"
            }
            lines.add(
                RenderItem.Text(
                    text = waLabel,
                    isHeader = true,
                    isBold = false,
                    alignment = config.headerAlignment
                )
            )
        }

        if (lines.isNotEmpty()) {
            lines.add(RenderItem.Spacer(10))
        }

        // 2. JOB NO & DATE/TIME
        val jobNo = formatJobNumber(repair.jobNumber)
        if (config.showJobNumber) {
            lines.add(
                RenderItem.Text(
                    text = "Job No: $jobNo",
                    isBold = config.boldJobNumber,
                    alignment = config.bodyAlignment
                )
            )
        }
        if (config.showDateTime) {
            val timestampStr = formatTimestamp(repair.receivedTimestamp, repair.receivedDate, repair.receivedTime)
            lines.add(
                RenderItem.Text(
                    text = "Date: $timestampStr",
                    isBold = false,
                    alignment = config.bodyAlignment
                )
            )
        }

        if (config.showJobNumber || config.showDateTime) {
            lines.add(RenderItem.Spacer(8))
        }

        // 3. CUSTOMER INFORMATION
        val customerName = sanitizeText(repair.customerName).ifBlank { "Ashoka" }
        val customerPhone = sanitizeText(repair.customerPhone).ifBlank { "07XXXXXXXX" }

        if (config.showCustomerName) {
            lines.add(
                RenderItem.Text(
                    text = "Customer: $customerName",
                    isBold = false,
                    alignment = config.bodyAlignment
                )
            )
        }
        if (config.showCustomerPhone) {
            lines.add(
                RenderItem.Text(
                    text = "Phone: $customerPhone",
                    isBold = false,
                    alignment = config.bodyAlignment
                )
            )
        }

        // 4. DEVICE DETAILS (Brand & Model)
        val brand = sanitizeText(repair.brand)
        val model = sanitizeText(repair.model)
        if (config.showBrand && config.showModel) {
            val deviceDesc = if (brand.isNotBlank() && model.isNotBlank()) {
                if (model.startsWith(brand, ignoreCase = true)) model else "$brand $model"
            } else if (brand.isNotBlank()) brand else if (model.isNotBlank()) model else "General Device"
            lines.add(
                RenderItem.Text(
                    text = "Device: $deviceDesc",
                    isBold = false,
                    alignment = config.bodyAlignment
                )
            )
        } else if (config.showBrand && brand.isNotBlank()) {
            lines.add(
                RenderItem.Text(
                    text = "Brand: $brand",
                    isBold = false,
                    alignment = config.bodyAlignment
                )
            )
        } else if (config.showModel && model.isNotBlank()) {
            lines.add(
                RenderItem.Text(
                    text = "Model: $model",
                    isBold = false,
                    alignment = config.bodyAlignment
                )
            )
        }

        // IMEI
        val imei = sanitizeText(repair.imei)
        if (config.showImei && imei.isNotBlank()) {
            lines.add(
                RenderItem.Text(
                    text = "IMEI: $imei",
                    isBold = false,
                    alignment = config.bodyAlignment
                )
            )
        }

        // Complaint
        val fault = sanitizeText(repair.fault)
        if (config.showComplaint && fault.isNotBlank()) {
            lines.add(RenderItem.Spacer(6))
            lines.add(
                RenderItem.Text(
                    text = "Complaint:",
                    isBold = true,
                    alignment = config.bodyAlignment
                )
            )
            lines.add(
                RenderItem.Text(
                    text = fault,
                    isBold = false,
                    alignment = config.bodyAlignment
                )
            )
        }

        lines.add(RenderItem.Spacer(8))

        // 5. REPAIR ITEMS
        if (config.showRepairItems) {
            lines.add(
                RenderItem.Text(
                    text = "Repair:",
                    isBold = true,
                    alignment = config.bodyAlignment
                )
            )
            if (items.isNotEmpty()) {
                for (item in items) {
                    val itemTitle = sanitizeText(item.repairType).ifBlank { "Repair Service" }
                    val itemPrice = formatCurrency(cur, item.price)
                    lines.add(
                        RenderItem.TwoColumns(
                            left = itemTitle,
                            right = itemPrice,
                            isBold = false,
                            alignment = config.bodyAlignment
                        )
                    )
                }
            } else {
                val serviceTitle = if (fault.isNotBlank()) fault else "Water Damage Service"
                val servicePrice = formatCurrency(cur, repair.totalPrice)
                lines.add(
                    RenderItem.TwoColumns(
                        left = serviceTitle,
                        right = servicePrice,
                        isBold = false,
                        alignment = config.bodyAlignment
                    )
                )
            }
            lines.add(RenderItem.Spacer(8))
        }

        // 6. DIVIDER
        if (config.dividerStyle != ReceiptDividerStyle.NONE) {
            lines.add(RenderItem.Divider(config.dividerStyle))
            lines.add(RenderItem.Spacer(4))
        }

        // 7. FINANCIALS (TOTAL, PAID, BALANCE)
        if (config.showTotal) {
            lines.add(
                RenderItem.TwoColumns(
                    left = "TOTAL:",
                    right = formatCurrency(cur, repair.totalPrice),
                    isBold = config.boldTotal,
                    alignment = config.bodyAlignment
                )
            )
        }
        if (config.showPaid) {
            lines.add(
                RenderItem.TwoColumns(
                    left = "PAID:",
                    right = formatCurrency(cur, repair.amountPaid),
                    isBold = config.boldPaid,
                    alignment = config.bodyAlignment
                )
            )
        }
        if (config.showBalance) {
            lines.add(
                RenderItem.TwoColumns(
                    left = "BALANCE:",
                    right = formatCurrency(cur, repair.balance),
                    isBold = config.boldBalance,
                    alignment = config.bodyAlignment
                )
            )
        }

        // Payment status
        if (config.showPaymentStatus) {
            lines.add(RenderItem.Spacer(6))
            val pStatus = sanitizeText(repair.paymentStatus).ifBlank { "PAID" }
            lines.add(
                RenderItem.Text(
                    text = "Payment Status: $pStatus",
                    isBold = true,
                    alignment = config.bodyAlignment
                )
            )
        }

        // 8. FOOTER / THANK YOU
        if (config.showFooter && config.footer.isNotBlank()) {
            lines.add(RenderItem.Spacer(12))
            val footerLines = config.footer.split("\n", "\r\n").map { sanitizeText(it) }.filter { it.isNotBlank() }
            for ((idx, fLine) in footerLines.withIndex()) {
                val isLast = idx == footerLines.lastIndex && footerLines.size > 1
                lines.add(
                    RenderItem.Text(
                        text = fLine,
                        isFooter = true,
                        isBold = isLast,
                        alignment = config.footerAlignment
                    )
                )
            }
        }

        return renderItemsToBitmap(lines, config.textSize)
    }

    /**
     * Generates a Payment Receipt Bitmap with full customization support.
     */
    fun generatePaymentReceipt(
        repair: RepairEntity,
        payment: PaymentEntity,
        settings: AppSettingsEntity,
        config: PaymentReceiptConfig = PaymentReceiptConfig()
    ): Bitmap {
        val cur = if (settings.currency.isNotBlank()) settings.currency.trim() else "Rs."
        val lines = mutableListOf<RenderItem>()

        // 1. SHOP INFORMATION (HEADER)
        val shopNameText = sanitizeText(config.shopName.ifBlank { settings.shopName.ifBlank { "UDM MOBILE REPAIR" } })
        val subtitleText = sanitizeText(config.subtitle.ifBlank { "Mobile Phone Repair" })
        val phoneRaw = config.phone.ifBlank { settings.shopPhone.ifBlank { "07XXXXXXXX" } }
        val phoneText = if (phoneRaw.startsWith("Tel:", ignoreCase = true)) phoneRaw else "Tel: $phoneRaw"
        val addressText = sanitizeText(config.address.ifBlank { settings.shopAddress })
        val whatsappText = sanitizeText(config.whatsapp.ifBlank { settings.whatsappNumber })

        if (config.showShopName && shopNameText.isNotBlank()) {
            lines.add(
                RenderItem.Text(
                    text = shopNameText,
                    isHeader = true,
                    isLarge = true,
                    isBold = config.boldShopName,
                    alignment = config.headerAlignment
                )
            )
        }
        if (config.showSubtitle && subtitleText.isNotBlank()) {
            lines.add(
                RenderItem.Text(
                    text = subtitleText,
                    isHeader = true,
                    isBold = false,
                    alignment = config.headerAlignment
                )
            )
        }
        if (config.showPhone && phoneText.isNotBlank()) {
            lines.add(
                RenderItem.Text(
                    text = phoneText,
                    isHeader = true,
                    isBold = false,
                    alignment = config.headerAlignment
                )
            )
        }
        if (config.showAddress && addressText.isNotBlank()) {
            lines.add(
                RenderItem.Text(
                    text = addressText,
                    isHeader = true,
                    isBold = false,
                    alignment = config.headerAlignment
                )
            )
        }
        if (whatsappText.isNotBlank()) {
            val waLabel = if (whatsappText.startsWith("WA:", ignoreCase = true) || whatsappText.startsWith("WhatsApp:", ignoreCase = true)) {
                whatsappText
            } else {
                "WhatsApp: $whatsappText"
            }
            lines.add(
                RenderItem.Text(
                    text = waLabel,
                    isHeader = true,
                    isBold = false,
                    alignment = config.headerAlignment
                )
            )
        }

        // 2. PAYMENT RECEIPT TITLE
        if (config.showReceiptTitle) {
            lines.add(RenderItem.Spacer(8))
            val titleText = sanitizeText(config.receiptTitle).ifBlank { "PAYMENT RECEIPT" }
            lines.add(
                RenderItem.Text(
                    text = titleText,
                    isHeader = true,
                    isMedium = true,
                    isBold = config.boldReceiptTitle,
                    alignment = config.headerAlignment
                )
            )
            lines.add(RenderItem.Spacer(8))
        } else {
            lines.add(RenderItem.Spacer(10))
        }

        // 3. JOB NO & DATE/TIME
        val jobNo = formatJobNumber(repair.jobNumber)
        if (config.showJobNumber) {
            lines.add(
                RenderItem.Text(
                    text = "Job No: $jobNo",
                    isBold = config.boldJobNumber,
                    alignment = config.bodyAlignment
                )
            )
        }
        if (config.showDateTime) {
            val timestampStr = formatTimestamp(payment.timestamp, payment.date, payment.time)
            lines.add(
                RenderItem.Text(
                    text = "Date: $timestampStr",
                    isBold = false,
                    alignment = config.bodyAlignment
                )
            )
        }

        if (config.showJobNumber || config.showDateTime) {
            lines.add(RenderItem.Spacer(8))
        }

        // 4. CUSTOMER INFORMATION
        val customerName = sanitizeText(repair.customerName).ifBlank { "Ashoka" }
        val customerPhone = sanitizeText(repair.customerPhone).ifBlank { "07XXXXXXXX" }

        if (config.showCustomerName) {
            lines.add(
                RenderItem.Text(
                    text = "Customer: $customerName",
                    isBold = false,
                    alignment = config.bodyAlignment
                )
            )
        }
        if (config.showCustomerPhone) {
            lines.add(
                RenderItem.Text(
                    text = "Phone: $customerPhone",
                    isBold = false,
                    alignment = config.bodyAlignment
                )
            )
        }

        if (config.showCustomerName || config.showCustomerPhone) {
            lines.add(RenderItem.Spacer(8))
        }

        // 5. PAYMENT RECEIVED
        if (config.showPaymentReceived) {
            val amountReceivedStr = formatCurrency(cur, payment.amount)
            lines.add(
                RenderItem.TwoColumns(
                    left = "Payment Received:",
                    right = amountReceivedStr,
                    isBold = config.boldPaymentReceived,
                    alignment = config.bodyAlignment
                )
            )
            lines.add(RenderItem.Spacer(8))
        }

        // 6. DIVIDER
        if (config.dividerStyle != ReceiptDividerStyle.NONE) {
            lines.add(RenderItem.Divider(config.dividerStyle))
            lines.add(RenderItem.Spacer(4))
        }

        // 7. FINANCIALS (Total, Paid, Balance)
        if (config.showTotal) {
            lines.add(
                RenderItem.TwoColumns(
                    left = "Total:",
                    right = formatCurrency(cur, repair.totalPrice),
                    isBold = config.boldTotal,
                    alignment = config.bodyAlignment
                )
            )
        }
        if (config.showPaid) {
            lines.add(
                RenderItem.TwoColumns(
                    left = "Paid:",
                    right = formatCurrency(cur, repair.amountPaid),
                    isBold = config.boldPaid,
                    alignment = config.bodyAlignment
                )
            )
        }
        if (config.showBalance) {
            lines.add(
                RenderItem.TwoColumns(
                    left = "Balance:",
                    right = formatCurrency(cur, repair.balance),
                    isBold = config.boldBalance,
                    alignment = config.bodyAlignment
                )
            )
        }

        // 8. PAYMENT STATUS
        if (config.showPaymentStatus) {
            lines.add(RenderItem.Spacer(6))
            val pStatus = sanitizeText(repair.paymentStatus).ifBlank { "PAID" }
            lines.add(
                RenderItem.Text(
                    text = "Payment Status: $pStatus",
                    isBold = true,
                    alignment = config.bodyAlignment
                )
            )
        }

        // 9. FOOTER / THANK YOU
        if (config.showFooter && config.footer.isNotBlank()) {
            lines.add(RenderItem.Spacer(12))
            val footerLines = config.footer.split("\n", "\r\n").map { sanitizeText(it) }.filter { it.isNotBlank() }
            for ((idx, fLine) in footerLines.withIndex()) {
                val isLast = idx == footerLines.lastIndex && footerLines.size > 1
                lines.add(
                    RenderItem.Text(
                        text = fLine,
                        isFooter = true,
                        isBold = isLast,
                        alignment = config.footerAlignment
                    )
                )
            }
        }

        return renderItemsToBitmap(lines, config.textSize)
    }

    /**
     * Generates a realistic live preview Bitmap for Customer Receipt customization.
     */
    fun generateCustomerReceiptPreview(config: CustomerReceiptConfig, currency: String = "Rs."): Bitmap {
        val sampleRepair = RepairEntity(
            jobNumber = "0027",
            customerName = "Ashoka",
            customerPhone = "07XXXXXXXX",
            brand = "Samsung",
            model = "M02",
            imei = "354678129034567",
            fault = "Water damage, not powering on",
            totalPrice = 800.0,
            amountPaid = 800.0,
            balance = 0.0,
            paymentStatus = "PAID",
            status = "DELIVERED",
            receivedDate = "29/09/2026",
            receivedTime = "09:15"
        )
        val sampleItems = listOf(
            RepairItemEntity(repairId = 0L, repairType = "Water Damage Service", price = 800.0)
        )
        val sampleSettings = AppSettingsEntity(
            shopName = config.shopName,
            currency = currency
        )
        return generateCustomerReceipt(sampleRepair, sampleItems, emptyList(), sampleSettings, config)
    }

    /**
     * Generates a realistic live preview Bitmap for Payment Receipt customization.
     */
    fun generatePaymentReceiptPreview(config: PaymentReceiptConfig, currency: String = "Rs."): Bitmap {
        val sampleRepair = RepairEntity(
            jobNumber = "0027",
            customerName = "Ashoka",
            customerPhone = "07XXXXXXXX",
            brand = "Samsung",
            model = "M02",
            fault = "Water Damage Service",
            totalPrice = 800.0,
            amountPaid = 800.0,
            balance = 0.0,
            paymentStatus = "PAID",
            status = "DELIVERED",
            receivedDate = "29/09/2026",
            receivedTime = "09:15"
        )
        val samplePayment = PaymentEntity(
            repairId = 0L,
            paymentNumber = 1,
            amount = 800.0,
            date = "29/09/2026",
            time = "09:15"
        )
        val sampleSettings = AppSettingsEntity(
            shopName = config.shopName,
            currency = currency
        )
        return generatePaymentReceipt(sampleRepair, samplePayment, sampleSettings, config)
    }

    /**
     * Generates a Test Print Receipt Bitmap for the Marklife P50S.
     */
    fun generateTestReceipt(deviceName: String = "P50S-496A-BLE"): Bitmap {
        val lines = listOf(
            RenderItem.Text("UDM MOBILE REPAIR", isHeader = true, isLarge = true, isBold = true, alignment = ReceiptAlignment.CENTER),
            RenderItem.Divider(ReceiptDividerStyle.SOLID),
            RenderItem.Text("P50S TEST PRINT", isHeader = true, isMedium = true, isBold = true, alignment = ReceiptAlignment.CENTER),
            RenderItem.Divider(ReceiptDividerStyle.DASHED),
            RenderItem.Text("Printer: Marklife P50S", isBold = false, alignment = ReceiptAlignment.LEFT),
            RenderItem.Text("Device: $deviceName", isBold = false, alignment = ReceiptAlignment.LEFT),
            RenderItem.Divider(ReceiptDividerStyle.DASHED),
            RenderItem.Text("PRINT TEST OK", isHeader = true, isMedium = true, isBold = true, alignment = ReceiptAlignment.CENTER),
            RenderItem.Divider(ReceiptDividerStyle.SOLID)
        )
        return renderItemsToBitmap(lines, ReceiptTextSize.NORMAL)
    }

    /**
     * Minimal customer test receipt for step-by-step diagnostic verification.
     */
    fun generateMinimalCustomerReceipt(settings: AppSettingsEntity = AppSettingsEntity()): Bitmap {
        val cur = if (settings.currency.isNotBlank()) settings.currency.trim() else "Rs."
        val shopName = sanitizeText(settings.shopName).ifBlank { "UDM MOBILE REPAIR" }
        val lines = listOf(
            RenderItem.Text(shopName, isHeader = true, isLarge = true, isBold = true, alignment = ReceiptAlignment.CENTER),
            RenderItem.Divider(ReceiptDividerStyle.SOLID),
            RenderItem.Spacer(6),
            RenderItem.Text("JOB: 0001", isHeader = true, isMedium = true, isBold = true, alignment = ReceiptAlignment.CENTER),
            RenderItem.Spacer(6),
            RenderItem.Text("TEST", isHeader = true, isMedium = true, isBold = true, alignment = ReceiptAlignment.CENTER),
            RenderItem.Divider(ReceiptDividerStyle.DASHED),
            RenderItem.TwoColumns("TOTAL:", "$cur 100", isBold = false, alignment = ReceiptAlignment.LEFT),
            RenderItem.TwoColumns("PAID:", "$cur 100", isBold = false, alignment = ReceiptAlignment.LEFT),
            RenderItem.TwoColumns("BALANCE:", "$cur 0", isBold = true, alignment = ReceiptAlignment.LEFT),
            RenderItem.Spacer(6),
            RenderItem.Divider(ReceiptDividerStyle.SOLID)
        )
        return renderItemsToBitmap(lines, ReceiptTextSize.NORMAL)
    }

    /**
     * Diagnostic bitmap for Marklife P50S continuous raster stream testing.
     * Contains a single continuous 600px tall bitmap with 3 visual test sections:
     * - Section 0: "SLICE 0" (Rows 0 - 199)
     * - Section 1: "SLICE 1" (Rows 200 - 399)
     * - Section 2: "SLICE 2" (Rows 400 - 599)
     * Renders each label inside its corresponding visual section to physically verify
     * that all 600px of the continuous raster stream are printed by the printer.
     */
    fun generateCustomerReceiptDiagnostic(): Bitmap {
        val sliceHeight = 200
        val totalHeight = sliceHeight * 3 // 600 px
        val bitmap = Bitmap.createBitmap(BITMAP_WIDTH, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
        }

        for (sliceIdx in 0..2) {
            val yStart = sliceIdx * sliceHeight
            val yEnd = yStart + sliceHeight

            // Border line at top of slice
            paint.strokeWidth = 3f
            canvas.drawLine(16f, (yStart + 4).toFloat(), (BITMAP_WIDTH - 16).toFloat(), (yStart + 4).toFloat(), paint)

            // Header of slice
            paint.strokeWidth = 0f
            paint.textSize = 18f
            paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("=== SLICE $sliceIdx (Y: $yStart - ${yEnd - 1}) ===", BITMAP_WIDTH / 2f, (yStart + 30).toFloat(), paint)

            // Obvious large label inside slice
            paint.textSize = 48f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText("SLICE $sliceIdx", BITMAP_WIDTH / 2f, (yStart + 95).toFloat(), paint)

            // Diagnostic note
            paint.textSize = 15f
            paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            val note = when (sliceIdx) {
                0 -> "FIRST SLICE (0..199)"
                1 -> "MIDDLE SLICE (200..399)"
                else -> "FINAL SLICE (400..599)"
            }
            canvas.drawText(note, BITMAP_WIDTH / 2f, (yStart + 135).toFloat(), paint)
            canvas.drawText("P50S MULTI-SLICE DIAGNOSTIC", BITMAP_WIDTH / 2f, (yStart + 160).toFloat(), paint)

            // Border line at bottom of slice
            paint.strokeWidth = 3f
            canvas.drawLine(16f, (yEnd - 4).toFloat(), (BITMAP_WIDTH - 16).toFloat(), (yEnd - 4).toFloat(), paint)
        }

        return bitmap
    }

    data class PowerTestSpec(
        val id: String,
        val height: Int,
        val targetDensityPercent: Int,
        val marker: String
    )

    fun getPowerTestSpec(testId: String): PowerTestSpec {
        val upper = testId.trim().uppercase()
        return when {
            upper.contains("TEST A") || upper == "A" -> PowerTestSpec("TEST A", 200, 0, "HEIGHT 200")
            upper.contains("TEST B") || upper == "B" -> PowerTestSpec("TEST B", 300, 0, "HEIGHT 300")
            upper.contains("TEST C") || upper == "C" -> PowerTestSpec("TEST C", 400, 0, "HEIGHT 400")
            upper.contains("TEST D") || upper == "D" -> PowerTestSpec("TEST D", 500, 0, "HEIGHT 500")
            upper.contains("TEST E") || upper == "E" -> PowerTestSpec("TEST E", 600, 0, "HEIGHT 600")
            upper.contains("TEST F") || upper == "F" -> PowerTestSpec("TEST F", 400, 25, "HEIGHT 400 (25% DENSITY)")
            upper.contains("TEST G") || upper == "G" -> PowerTestSpec("TEST G", 400, 50, "HEIGHT 400 (50% DENSITY)")
            upper.contains("TEST H") || upper == "H" -> PowerTestSpec("TEST H", 400, 75, "HEIGHT 400 (75% DENSITY)")
            upper.contains("TEST I") || upper == "I" -> PowerTestSpec("TEST I", 600, 25, "HEIGHT 600 (25% DENSITY)")
            upper.contains("TEST J") || upper == "J" -> PowerTestSpec("TEST J", 600, 50, "HEIGHT 600 (50% DENSITY)")
            else -> PowerTestSpec("TEST A", 200, 0, "HEIGHT 200")
        }
    }

    /**
     * Generates a controlled diagnostic bitmap to isolate height vs. black density power limits.
     * Uses controlled checkerboard/text patterns rather than large solid blocks.
     */
    fun generatePowerDiagnosticBitmap(testSpec: PowerTestSpec): Bitmap {
        val width = BITMAP_WIDTH
        val height = testSpec.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
        }

        // Top border
        paint.strokeWidth = 3f
        canvas.drawLine(16f, 4f, (width - 16).toFloat(), 4f, paint)

        // Header info
        paint.strokeWidth = 0f
        paint.textSize = 20f
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("=== ${testSpec.id} ===", width / 2f, 32f, paint)

        paint.textSize = 34f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(testSpec.marker, width / 2f, 76f, paint)

        paint.textSize = 15f
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        canvas.drawText("HEIGHT: ${height}px  WIDTH: ${width}px", width / 2f, 108f, paint)
        canvas.drawText("TARGET: ${if (testSpec.targetDensityPercent == 0) "LOW DENSITY" else "${testSpec.targetDensityPercent}% DENSITY"}", width / 2f, 130f, paint)

        // Divider
        paint.strokeWidth = 2f
        canvas.drawLine(20f, 142f, (width - 20).toFloat(), 142f, paint)

        // If target density is specified, draw regular geometric pattern in the middle zone
        val patternStartY = 150
        val patternEndY = height - 40
        if (testSpec.targetDensityPercent > 0 && patternEndY > patternStartY) {
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val margin = 20
            for (y in patternStartY until patternEndY) {
                val rowOffset = y * width
                for (x in margin until (width - margin)) {
                    val isBlack = when (testSpec.targetDensityPercent) {
                        25 -> (x % 2 == 0 && y % 2 == 0) // 1 in 4 pixels = 25%
                        50 -> ((x + y) % 2 == 0)         // checkerboard = 50%
                        75 -> !(x % 2 == 1 && y % 2 == 1) // 3 in 4 pixels = 75%
                        else -> false
                    }
                    if (isBlack) {
                        pixels[rowOffset + x] = Color.BLACK
                    }
                }
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        }

        // Bottom label
        paint.strokeWidth = 0f
        paint.textSize = 14f
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("END OF ${testSpec.id} (${height}px)", width / 2f, (height - 18).toFloat(), paint)

        // Bottom border
        paint.strokeWidth = 3f
        canvas.drawLine(16f, (height - 4).toFloat(), (width - 16).toFloat(), (height - 4).toFloat(), paint)

        return bitmap
    }

    private sealed class RenderItem {
        data class Text(
            val text: String,
            val isHeader: Boolean = false,
            val isFooter: Boolean = false,
            val isLarge: Boolean = false,
            val isMedium: Boolean = false,
            val isBold: Boolean = false,
            val alignment: ReceiptAlignment = ReceiptAlignment.LEFT
        ) : RenderItem()

        data class TwoColumns(
            val left: String,
            val right: String,
            val isBold: Boolean = false,
            val alignment: ReceiptAlignment = ReceiptAlignment.LEFT
        ) : RenderItem()

        data class Divider(val style: ReceiptDividerStyle) : RenderItem()
        data class Spacer(val heightPx: Int) : RenderItem()
    }

    private fun renderItemsToBitmap(items: List<RenderItem>, textSize: ReceiptTextSize): Bitmap {
        val leftMargin = 16f
        val rightMargin = BITMAP_WIDTH - 16f

        // Font scaling based on user preference
        val fontScale = when (textSize) {
            ReceiptTextSize.SMALL -> 0.88f
            ReceiptTextSize.NORMAL -> 1.0f
            ReceiptTextSize.LARGE -> 1.15f
        }

        val baseNormalSize = 18f * fontScale
        val baseHeaderLargeSize = 24f * fontScale
        val baseHeaderMediumSize = 20f * fontScale

        // Expand text with automatic wrapping
        val expandedItems = mutableListOf<RenderItem>()
        for (item in items) {
            when (item) {
                is RenderItem.Text -> {
                    val maxChars = when {
                        item.isLarge -> (20 / fontScale).toInt().coerceIn(16, 26)
                        item.isMedium -> (23 / fontScale).toInt().coerceIn(18, 30)
                        else -> (28 / fontScale).toInt().coerceIn(20, 36)
                    }
                    val wrapped = wrapText(item.text, maxChars)
                    for (line in wrapped) {
                        expandedItems.add(item.copy(text = line))
                    }
                }
                is RenderItem.TwoColumns -> {
                    val rightLen = item.right.length
                    val maxLeftChars = if (rightLen > 0) ((30 - rightLen) / fontScale).toInt().coerceIn(12, 22) else 26
                    val wrappedLeft = wrapText(item.left, maxLeftChars)
                    for ((index, w) in wrappedLeft.withIndex()) {
                        if (index == 0) {
                            expandedItems.add(item.copy(left = w))
                        } else {
                            expandedItems.add(
                                RenderItem.TwoColumns(
                                    left = w,
                                    right = "",
                                    isBold = item.isBold,
                                    alignment = item.alignment
                                )
                            )
                        }
                    }
                }
                is RenderItem.Divider, is RenderItem.Spacer -> {
                    expandedItems.add(item)
                }
            }
        }

        // Measure dynamic height
        var totalHeight = 16 // Top margin
        for (item in expandedItems) {
            totalHeight += when (item) {
                is RenderItem.Text -> {
                    when {
                        item.isLarge -> (32 * fontScale).toInt()
                        item.isMedium -> (27 * fontScale).toInt()
                        else -> (23 * fontScale).toInt()
                    }
                }
                is RenderItem.TwoColumns -> (24 * fontScale).toInt()
                is RenderItem.Divider -> 16
                is RenderItem.Spacer -> item.heightPx
            }
        }
        totalHeight += 24 // Bottom tear margin

        // Align height to multiple of 8 for thermal printer microcontroller safety
        val remainder = totalHeight % 8
        if (remainder != 0) {
            totalHeight += (8 - remainder)
        }

        val bitmap = Bitmap.createBitmap(BITMAP_WIDTH, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
        }

        var y = 16f

        for (item in expandedItems) {
            when (item) {
                is RenderItem.Text -> {
                    val size = when {
                        item.isLarge -> baseHeaderLargeSize
                        item.isMedium -> baseHeaderMediumSize
                        else -> baseNormalSize
                    }
                    paint.textSize = size
                    val typefaceStyle = if (item.isBold) Typeface.BOLD else Typeface.NORMAL
                    paint.typeface = if (item.isHeader || item.isFooter) {
                        Typeface.create(Typeface.DEFAULT, typefaceStyle)
                    } else {
                        Typeface.create(Typeface.MONOSPACE, typefaceStyle)
                    }

                    y += size

                    val x = when (item.alignment) {
                        ReceiptAlignment.LEFT -> {
                            paint.textAlign = Paint.Align.LEFT
                            leftMargin
                        }
                        ReceiptAlignment.CENTER -> {
                            paint.textAlign = Paint.Align.CENTER
                            BITMAP_WIDTH / 2f
                        }
                        ReceiptAlignment.RIGHT -> {
                            paint.textAlign = Paint.Align.RIGHT
                            rightMargin
                        }
                    }
                    canvas.drawText(item.text, x, y, paint)
                    y += (5f * fontScale)
                }
                is RenderItem.TwoColumns -> {
                    val size = baseNormalSize
                    paint.textSize = size
                    val typefaceStyle = if (item.isBold) Typeface.BOLD else Typeface.NORMAL
                    paint.typeface = Typeface.create(Typeface.MONOSPACE, typefaceStyle)

                    y += size

                    // Left column
                    paint.textAlign = Paint.Align.LEFT
                    canvas.drawText(item.left, leftMargin, y, paint)

                    // Right column
                    if (item.right.isNotBlank()) {
                        paint.textAlign = Paint.Align.RIGHT
                        canvas.drawText(item.right, rightMargin, y, paint)
                    }
                    y += (5f * fontScale)
                }
                is RenderItem.Divider -> {
                    when (item.style) {
                        ReceiptDividerStyle.DASHED -> {
                            paint.textSize = 16f
                            paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                            paint.textAlign = Paint.Align.CENTER
                            y += 12f
                            canvas.drawText("------------------------------", BITMAP_WIDTH / 2f, y, paint)
                            y += 4f
                        }
                        ReceiptDividerStyle.SOLID -> {
                            y += 8f
                            paint.strokeWidth = 2f
                            canvas.drawLine(leftMargin, y, rightMargin, y, paint)
                            paint.strokeWidth = 0f
                            y += 8f
                        }
                        ReceiptDividerStyle.NONE -> {
                            // No line drawn
                        }
                    }
                }
                is RenderItem.Spacer -> {
                    y += item.heightPx
                }
            }
        }

        return bitmap
    }

    fun wrapText(rawText: String, maxChars: Int): List<String> {
        val safeMax = maxChars.coerceAtLeast(10)
        val clean = rawText.replace("\r\n", "\n").replace("\r", "\n").replace("\t", " ")
        val rawLines = clean.split("\n")
        val result = mutableListOf<String>()

        for (line in rawLines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.length <= safeMax) {
                result.add(trimmed)
                continue
            }

            val words = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
            var current = StringBuilder()

            for (word in words) {
                if (word.length > safeMax) {
                    if (current.isNotEmpty()) {
                        result.add(current.toString())
                        current = StringBuilder()
                    }
                    var start = 0
                    while (start < word.length) {
                        val end = minOf(start + safeMax, word.length)
                        val sub = word.substring(start, end)
                        if (end == word.length) {
                            current = StringBuilder(sub)
                        } else {
                            result.add(sub)
                        }
                        start = end
                    }
                } else if (current.isEmpty()) {
                    current.append(word)
                } else if (current.length + 1 + word.length <= safeMax) {
                    current.append(" ").append(word)
                } else {
                    result.add(current.toString())
                    current = StringBuilder(word)
                }
            }
            if (current.isNotEmpty()) {
                result.add(current.toString())
            }
        }

        return if (result.isEmpty()) listOf(rawText.take(safeMax)) else result
    }
}
