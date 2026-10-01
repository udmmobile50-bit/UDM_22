package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.entity.AppSettingsEntity
import com.example.data.entity.PaymentEntity
import com.example.data.entity.RepairEntity
import com.example.data.entity.RepairItemEntity
import com.example.printer.CustomerReceiptConfig
import com.example.printer.P50SProtocol
import com.example.printer.PaymentReceiptConfig
import com.example.printer.PrinterBleManager
import com.example.printer.PrintResult
import com.example.printer.ReceiptAlignment
import com.example.printer.ReceiptBitmapGenerator
import com.example.printer.ReceiptDividerStyle
import com.example.printer.ReceiptPreferences
import com.example.printer.ReceiptTextSize
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReceiptCustomizationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ReceiptPreferences.resetCustomerReceiptConfig(context)
        ReceiptPreferences.resetPaymentReceiptConfig(context)
    }

    @Test
    fun `test Customer Receipt default configuration values`() {
        val config = CustomerReceiptConfig()
        assertEquals("UDM MOBILE REPAIR", config.shopName)
        assertEquals("Mobile Phone Repair", config.subtitle)
        assertEquals("07XXXXXXXX", config.phone)
        assertTrue(config.showShopName)
        assertTrue(config.showSubtitle)
        assertTrue(config.showPhone)
        assertFalse(config.showAddress)
        assertTrue(config.showDateTime)
        assertTrue(config.showJobNumber)
        assertTrue(config.showCustomerName)
        assertTrue(config.showCustomerPhone)
        assertTrue(config.showBrand)
        assertTrue(config.showModel)
        assertFalse(config.showImei)
        assertFalse(config.showComplaint)
        assertTrue(config.showRepairItems)
        assertTrue(config.showTotal)
        assertTrue(config.showPaid)
        assertTrue(config.showBalance)
        assertFalse(config.showPaymentStatus)
        assertTrue(config.showFooter)

        assertEquals(ReceiptTextSize.NORMAL, config.textSize)
        assertTrue(config.boldShopName)
        assertTrue(config.boldJobNumber)
        assertTrue(config.boldTotal)
        assertTrue(config.boldPaid)
        assertTrue(config.boldBalance)

        assertEquals(ReceiptAlignment.CENTER, config.headerAlignment)
        assertEquals(ReceiptAlignment.LEFT, config.bodyAlignment)
        assertEquals(ReceiptAlignment.CENTER, config.footerAlignment)
        assertEquals(ReceiptDividerStyle.DASHED, config.dividerStyle)
    }

    @Test
    fun `test Payment Receipt default configuration values`() {
        val config = PaymentReceiptConfig()
        assertEquals("UDM MOBILE REPAIR", config.shopName)
        assertEquals("Mobile Phone Repair", config.subtitle)
        assertEquals("07XXXXXXXX", config.phone)
        assertEquals("PAYMENT RECEIPT", config.receiptTitle)

        assertTrue(config.showShopName)
        assertTrue(config.showSubtitle)
        assertTrue(config.showPhone)
        assertFalse(config.showAddress)
        assertTrue(config.showDateTime)
        assertTrue(config.showReceiptTitle)
        assertTrue(config.showJobNumber)
        assertTrue(config.showCustomerName)
        assertTrue(config.showCustomerPhone)
        assertTrue(config.showPaymentReceived)
        assertTrue(config.showTotal)
        assertTrue(config.showPaid)
        assertTrue(config.showBalance)
        assertTrue(config.showPaymentStatus)
        assertTrue(config.showFooter)

        assertEquals(ReceiptTextSize.NORMAL, config.textSize)
        assertTrue(config.boldShopName)
        assertTrue(config.boldReceiptTitle)
        assertTrue(config.boldJobNumber)
        assertTrue(config.boldPaymentReceived)
        assertTrue(config.boldTotal)
        assertTrue(config.boldPaid)
        assertTrue(config.boldBalance)

        assertEquals(ReceiptAlignment.CENTER, config.headerAlignment)
        assertEquals(ReceiptAlignment.LEFT, config.bodyAlignment)
        assertEquals(ReceiptAlignment.CENTER, config.footerAlignment)
        assertEquals(ReceiptDividerStyle.DASHED, config.dividerStyle)
    }

    @Test
    fun `test Customer Receipt persistence and JSON serialization`() {
        val custom = CustomerReceiptConfig(
            shopName = "Custom Fix Center",
            subtitle = "Electronics & Gadgets",
            phone = "0712345678",
            address = "No. 12 High Street",
            whatsapp = "0712345678",
            footer = "Custom Warranty 60 Days",
            showAddress = true,
            showImei = true,
            showComplaint = true,
            textSize = ReceiptTextSize.LARGE,
            boldShopName = false,
            headerAlignment = ReceiptAlignment.LEFT,
            bodyAlignment = ReceiptAlignment.CENTER,
            footerAlignment = ReceiptAlignment.RIGHT,
            dividerStyle = ReceiptDividerStyle.SOLID
        )

        ReceiptPreferences.saveCustomerReceiptConfig(context, custom)
        val loaded = ReceiptPreferences.getCustomerReceiptConfig(context)

        assertEquals("Custom Fix Center", loaded.shopName)
        assertEquals("Electronics & Gadgets", loaded.subtitle)
        assertEquals("0712345678", loaded.phone)
        assertEquals("No. 12 High Street", loaded.address)
        assertEquals("Custom Warranty 60 Days", loaded.footer)
        assertTrue(loaded.showAddress)
        assertTrue(loaded.showImei)
        assertTrue(loaded.showComplaint)
        assertEquals(ReceiptTextSize.LARGE, loaded.textSize)
        assertFalse(loaded.boldShopName)
        assertEquals(ReceiptAlignment.LEFT, loaded.headerAlignment)
        assertEquals(ReceiptAlignment.CENTER, loaded.bodyAlignment)
        assertEquals(ReceiptAlignment.RIGHT, loaded.footerAlignment)
        assertEquals(ReceiptDividerStyle.SOLID, loaded.dividerStyle)
    }

    @Test
    fun `test Payment Receipt persistence and JSON serialization`() {
        val custom = PaymentReceiptConfig(
            shopName = "Quick Cash Repair",
            receiptTitle = "OFFICIAL INVOICE",
            phone = "0798765432",
            textSize = ReceiptTextSize.SMALL,
            boldReceiptTitle = false,
            headerAlignment = ReceiptAlignment.RIGHT,
            dividerStyle = ReceiptDividerStyle.NONE
        )

        ReceiptPreferences.savePaymentReceiptConfig(context, custom)
        val loaded = ReceiptPreferences.getPaymentReceiptConfig(context)

        assertEquals("Quick Cash Repair", loaded.shopName)
        assertEquals("OFFICIAL INVOICE", loaded.receiptTitle)
        assertEquals("0798765432", loaded.phone)
        assertEquals(ReceiptTextSize.SMALL, loaded.textSize)
        assertFalse(loaded.boldReceiptTitle)
        assertEquals(ReceiptAlignment.RIGHT, loaded.headerAlignment)
        assertEquals(ReceiptDividerStyle.NONE, loaded.dividerStyle)
    }

    @Test
    fun `test modifying Customer Receipt does NOT change Payment Receipt`() {
        val initialPayment = ReceiptPreferences.getPaymentReceiptConfig(context)

        val updatedCustomer = CustomerReceiptConfig(
            shopName = "Modified Only In Customer",
            textSize = ReceiptTextSize.LARGE,
            dividerStyle = ReceiptDividerStyle.SOLID
        )
        ReceiptPreferences.saveCustomerReceiptConfig(context, updatedCustomer)

        val currentPayment = ReceiptPreferences.getPaymentReceiptConfig(context)
        assertEquals(initialPayment.shopName, currentPayment.shopName)
        assertEquals(initialPayment.textSize, currentPayment.textSize)
        assertEquals(initialPayment.dividerStyle, currentPayment.dividerStyle)
    }

    @Test
    fun `test modifying Payment Receipt does NOT change Customer Receipt`() {
        val initialCustomer = ReceiptPreferences.getCustomerReceiptConfig(context)

        val updatedPayment = PaymentReceiptConfig(
            shopName = "Modified Only In Payment",
            textSize = ReceiptTextSize.SMALL,
            dividerStyle = ReceiptDividerStyle.NONE
        )
        ReceiptPreferences.savePaymentReceiptConfig(context, updatedPayment)

        val currentCustomer = ReceiptPreferences.getCustomerReceiptConfig(context)
        assertEquals(initialCustomer.shopName, currentCustomer.shopName)
        assertEquals(initialCustomer.textSize, currentCustomer.textSize)
        assertEquals(initialCustomer.dividerStyle, currentCustomer.dividerStyle)
    }

    @Test
    fun `test resetting Customer Receipt does NOT reset Payment Receipt`() {
        // Save customized configs for both
        ReceiptPreferences.saveCustomerReceiptConfig(context, CustomerReceiptConfig(shopName = "Cust Custom"))
        ReceiptPreferences.savePaymentReceiptConfig(context, PaymentReceiptConfig(shopName = "Pay Custom"))

        // Reset customer receipt only
        ReceiptPreferences.resetCustomerReceiptConfig(context)

        val customer = ReceiptPreferences.getCustomerReceiptConfig(context)
        val payment = ReceiptPreferences.getPaymentReceiptConfig(context)

        assertEquals("UDM MOBILE REPAIR", customer.shopName) // reset to default
        assertEquals("Pay Custom", payment.shopName) // untouched!
    }

    @Test
    fun `test resetting Payment Receipt does NOT reset Customer Receipt`() {
        // Save customized configs for both
        ReceiptPreferences.saveCustomerReceiptConfig(context, CustomerReceiptConfig(shopName = "Cust Custom"))
        ReceiptPreferences.savePaymentReceiptConfig(context, PaymentReceiptConfig(shopName = "Pay Custom"))

        // Reset payment receipt only
        ReceiptPreferences.resetPaymentReceiptConfig(context)

        val customer = ReceiptPreferences.getCustomerReceiptConfig(context)
        val payment = ReceiptPreferences.getPaymentReceiptConfig(context)

        assertEquals("Cust Custom", customer.shopName) // untouched!
        assertEquals("UDM MOBILE REPAIR", payment.shopName) // reset to default
    }

    @Test
    fun `test Customer Receipt live preview generation produces 384 dot bitmap`() {
        val config = CustomerReceiptConfig()
        val bitmap = ReceiptBitmapGenerator.generateCustomerReceiptPreview(config)

        assertNotNull(bitmap)
        assertEquals(P50SProtocol.PRINTER_WIDTH_DOTS, bitmap.width)
        assertTrue("Bitmap height should be > 100", bitmap.height > 100)
        assertEquals(0, bitmap.height % 8) // Multiple of 8 hardware safety

        val mono = ReceiptBitmapGenerator.convertTo1BitMonochrome(bitmap)
        assertEquals(bitmap.height * 48, mono.size)

        val payload = P50SProtocol.buildPrintPayload(mono, bitmap.height)
        assertTrue(payload.isNotEmpty())
    }

    @Test
    fun `test Payment Receipt live preview generation produces 384 dot bitmap`() {
        val config = PaymentReceiptConfig()
        val bitmap = ReceiptBitmapGenerator.generatePaymentReceiptPreview(config)

        assertNotNull(bitmap)
        assertEquals(P50SProtocol.PRINTER_WIDTH_DOTS, bitmap.width)
        assertTrue("Bitmap height should be > 100", bitmap.height > 100)
        assertEquals(0, bitmap.height % 8)

        val mono = ReceiptBitmapGenerator.convertTo1BitMonochrome(bitmap)
        assertEquals(bitmap.height * 48, mono.size)

        val payload = P50SProtocol.buildPrintPayload(mono, bitmap.height)
        assertTrue(payload.isNotEmpty())
    }

    @Test
    fun `test text size scaling changes bitmap height appropriately`() {
        val smallConfig = CustomerReceiptConfig(textSize = ReceiptTextSize.SMALL)
        val normalConfig = CustomerReceiptConfig(textSize = ReceiptTextSize.NORMAL)
        val largeConfig = CustomerReceiptConfig(textSize = ReceiptTextSize.LARGE)

        val smallBmp = ReceiptBitmapGenerator.generateCustomerReceiptPreview(smallConfig)
        val normalBmp = ReceiptBitmapGenerator.generateCustomerReceiptPreview(normalConfig)
        val largeBmp = ReceiptBitmapGenerator.generateCustomerReceiptPreview(largeConfig)

        assertTrue("Small text bitmap should be shorter than Normal", smallBmp.height <= normalBmp.height)
        assertTrue("Large text bitmap should be taller than Normal", largeBmp.height >= normalBmp.height)
        assertEquals(0, smallBmp.height % 8)
        assertEquals(0, normalBmp.height % 8)
        assertEquals(0, largeBmp.height % 8)
    }

    @Test
    fun `test long customer name, repair item, and complaint wrap safely`() {
        val repair = RepairEntity(
            jobNumber = "0027",
            customerName = "Ven. Dr. Ananda Dharmapala Gunasekara Thero of Western Province",
            customerPhone = "0771234567 / 0719876543",
            brand = "Samsung Electronics Corp",
            model = "Galaxy Note 20 Ultra 5G Snapdragon Mystic Bronze 512GB",
            fault = "Display touch digitized layer completely shattered, water inside charging port, battery swollen, and speaker buzzing on loudspeaker mode",
            totalPrice = 12500.0,
            amountPaid = 5000.0,
            balance = 7500.0,
            status = "IN_PROGRESS",
            paymentStatus = "PARTIAL",
            receivedDate = "29/09/2026",
            receivedTime = "09:15"
        )
        val items = listOf(
            RepairItemEntity(repairId = 1L, repairType = "Original Super AMOLED Display with Touch Glass Assembly", price = 9500.0),
            RepairItemEntity(repairId = 1L, repairType = "Type-C Charging Sub-Board Replacement with Mic", price = 3000.0)
        )
        val config = CustomerReceiptConfig(
            showComplaint = true,
            showImei = true,
            showAddress = true,
            address = "No. 45, Temple Road, Colombo 03"
        )
        val settings = AppSettingsEntity()

        val bitmap = ReceiptBitmapGenerator.generateCustomerReceipt(repair, items, emptyList(), settings, config)
        assertNotNull(bitmap)
        assertEquals(384, bitmap.width)
        assertEquals(0, bitmap.height % 8)

        val mono = ReceiptBitmapGenerator.convertTo1BitMonochrome(bitmap)
        assertEquals(bitmap.height * 48, mono.size)

        val payload = P50SProtocol.buildPrintPayload(mono, bitmap.height)
        assertTrue(payload.isNotEmpty())
        assertTrue("Payload size must stay within safe buffer limits", payload.size < 70000)
    }

    @Test
    fun `test disconnected printer returns immediate error without sending or crashing`() = runBlocking {
        val printerManager = PrinterBleManager.getInstance(context)
        // Ensure disconnected state in test environment
        printerManager.disconnect()
        assertFalse(printerManager.isPrinterConnected())

        val repair = RepairEntity(
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
            receivedDate = "29/09/2026",
            receivedTime = "09:15"
        )
        val payment = PaymentEntity(
            repairId = 1L,
            paymentNumber = 1,
            amount = 800.0,
            date = "29/09/2026",
            time = "09:15"
        )
        val settings = AppSettingsEntity()

        // 1. Customer receipt print when disconnected
        val custResult = printerManager.printCustomerReceipt(repair, emptyList(), emptyList(), settings)
        assertTrue("Customer print when disconnected must return Error", custResult is PrintResult.Error)
        val custErrorMsg = (custResult as PrintResult.Error).message
        assertTrue("Must contain 'Printer Not Connected'", custErrorMsg.contains("Printer Not Connected", ignoreCase = true))

        // 2. Payment receipt print when disconnected
        val payResult = printerManager.printPaymentReceipt(repair, payment, settings)
        assertTrue("Payment print when disconnected must return Error", payResult is PrintResult.Error)
        val payErrorMsg = (payResult as PrintResult.Error).message
        assertTrue("Must contain 'Printer Not Connected'", payErrorMsg.contains("Printer Not Connected", ignoreCase = true))

        // 3. Test print when disconnected
        val testResult = printerManager.printTestReceipt()
        assertTrue("Test print when disconnected must return Error", testResult is PrintResult.Error)
        val testErrorMsg = (testResult as PrintResult.Error).message
        assertTrue("Must contain 'Printer Not Connected'", testErrorMsg.contains("Printer Not Connected", ignoreCase = true))
    }
}
