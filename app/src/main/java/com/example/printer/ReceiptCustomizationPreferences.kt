package com.example.printer

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

enum class ReceiptTextSize {
    SMALL, NORMAL, LARGE;

    companion object {
        fun fromString(value: String?): ReceiptTextSize {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: NORMAL
        }
    }
}

enum class ReceiptAlignment {
    LEFT, CENTER, RIGHT;

    companion object {
        fun fromString(value: String?): ReceiptAlignment {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: LEFT
        }
    }
}

enum class ReceiptDividerStyle {
    DASHED, SOLID, NONE;

    companion object {
        fun fromString(value: String?): ReceiptDividerStyle {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DASHED
        }
    }
}

/**
 * Customer Receipt configuration model.
 * Defaults are strictly aligned with the default Customer Receipt design specification.
 */
data class CustomerReceiptConfig(
    // Shop information
    val shopName: String = "UDM MOBILE REPAIR",
    val subtitle: String = "Mobile Phone Repair",
    val phone: String = "07XXXXXXXX",
    val address: String = "",
    val whatsapp: String = "",
    val footer: String = "Thank You!\nUDM MOBILE REPAIR",

    // Visibility toggles
    val showShopName: Boolean = true,
    val showSubtitle: Boolean = true,
    val showPhone: Boolean = true,
    val showAddress: Boolean = false,
    val showDateTime: Boolean = true,
    val showJobNumber: Boolean = true,
    val showCustomerName: Boolean = true,
    val showCustomerPhone: Boolean = true,
    val showBrand: Boolean = true,
    val showModel: Boolean = true,
    val showImei: Boolean = false,
    val showComplaint: Boolean = false,
    val showRepairItems: Boolean = true,
    val showTotal: Boolean = true,
    val showPaid: Boolean = true,
    val showBalance: Boolean = true,
    val showPaymentStatus: Boolean = false,
    val showFooter: Boolean = true,

    // Text size
    val textSize: ReceiptTextSize = ReceiptTextSize.NORMAL,

    // Bold on/off
    val boldShopName: Boolean = true,
    val boldJobNumber: Boolean = true,
    val boldTotal: Boolean = true,
    val boldPaid: Boolean = true,
    val boldBalance: Boolean = true,

    // Alignment
    val headerAlignment: ReceiptAlignment = ReceiptAlignment.CENTER,
    val bodyAlignment: ReceiptAlignment = ReceiptAlignment.LEFT,
    val footerAlignment: ReceiptAlignment = ReceiptAlignment.CENTER,

    // Divider
    val dividerStyle: ReceiptDividerStyle = ReceiptDividerStyle.DASHED
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("shopName", shopName)
        json.put("subtitle", subtitle)
        json.put("phone", phone)
        json.put("address", address)
        json.put("whatsapp", whatsapp)
        json.put("footer", footer)

        json.put("showShopName", showShopName)
        json.put("showSubtitle", showSubtitle)
        json.put("showPhone", showPhone)
        json.put("showAddress", showAddress)
        json.put("showDateTime", showDateTime)
        json.put("showJobNumber", showJobNumber)
        json.put("showCustomerName", showCustomerName)
        json.put("showCustomerPhone", showCustomerPhone)
        json.put("showBrand", showBrand)
        json.put("showModel", showModel)
        json.put("showImei", showImei)
        json.put("showComplaint", showComplaint)
        json.put("showRepairItems", showRepairItems)
        json.put("showTotal", showTotal)
        json.put("showPaid", showPaid)
        json.put("showBalance", showBalance)
        json.put("showPaymentStatus", showPaymentStatus)
        json.put("showFooter", showFooter)

        json.put("textSize", textSize.name)

        json.put("boldShopName", boldShopName)
        json.put("boldJobNumber", boldJobNumber)
        json.put("boldTotal", boldTotal)
        json.put("boldPaid", boldPaid)
        json.put("boldBalance", boldBalance)

        json.put("headerAlignment", headerAlignment.name)
        json.put("bodyAlignment", bodyAlignment.name)
        json.put("footerAlignment", footerAlignment.name)

        json.put("dividerStyle", dividerStyle.name)
        return json.toString()
    }

    companion object {
        fun fromJson(jsonStr: String?): CustomerReceiptConfig {
            if (jsonStr.isNullOrBlank()) return CustomerReceiptConfig()
            return try {
                val json = JSONObject(jsonStr)
                CustomerReceiptConfig(
                    shopName = json.optString("shopName", "UDM MOBILE REPAIR"),
                    subtitle = json.optString("subtitle", "Mobile Phone Repair"),
                    phone = json.optString("phone", "07XXXXXXXX"),
                    address = json.optString("address", ""),
                    whatsapp = json.optString("whatsapp", ""),
                    footer = json.optString("footer", "Thank You!\nUDM MOBILE REPAIR"),

                    showShopName = json.optBoolean("showShopName", true),
                    showSubtitle = json.optBoolean("showSubtitle", true),
                    showPhone = json.optBoolean("showPhone", true),
                    showAddress = json.optBoolean("showAddress", false),
                    showDateTime = json.optBoolean("showDateTime", true),
                    showJobNumber = json.optBoolean("showJobNumber", true),
                    showCustomerName = json.optBoolean("showCustomerName", true),
                    showCustomerPhone = json.optBoolean("showCustomerPhone", true),
                    showBrand = json.optBoolean("showBrand", true),
                    showModel = json.optBoolean("showModel", true),
                    showImei = json.optBoolean("showImei", false),
                    showComplaint = json.optBoolean("showComplaint", false),
                    showRepairItems = json.optBoolean("showRepairItems", true),
                    showTotal = json.optBoolean("showTotal", true),
                    showPaid = json.optBoolean("showPaid", true),
                    showBalance = json.optBoolean("showBalance", true),
                    showPaymentStatus = json.optBoolean("showPaymentStatus", false),
                    showFooter = json.optBoolean("showFooter", true),

                    textSize = ReceiptTextSize.fromString(json.optString("textSize", "NORMAL")),

                    boldShopName = json.optBoolean("boldShopName", true),
                    boldJobNumber = json.optBoolean("boldJobNumber", true),
                    boldTotal = json.optBoolean("boldTotal", true),
                    boldPaid = json.optBoolean("boldPaid", true),
                    boldBalance = json.optBoolean("boldBalance", true),

                    headerAlignment = ReceiptAlignment.fromString(json.optString("headerAlignment", "CENTER")),
                    bodyAlignment = ReceiptAlignment.fromString(json.optString("bodyAlignment", "LEFT")),
                    footerAlignment = ReceiptAlignment.fromString(json.optString("footerAlignment", "CENTER")),

                    dividerStyle = ReceiptDividerStyle.fromString(json.optString("dividerStyle", "DASHED"))
                )
            } catch (_: Exception) {
                CustomerReceiptConfig()
            }
        }
    }
}

/**
 * Payment Receipt configuration model.
 * Defaults are strictly aligned with the default Payment Receipt design specification.
 */
data class PaymentReceiptConfig(
    // Shop information
    val shopName: String = "UDM MOBILE REPAIR",
    val subtitle: String = "Mobile Phone Repair",
    val phone: String = "07XXXXXXXX",
    val address: String = "",
    val whatsapp: String = "",
    val receiptTitle: String = "PAYMENT RECEIPT",
    val footer: String = "Thank You!\nUDM MOBILE REPAIR",

    // Visibility toggles
    val showShopName: Boolean = true,
    val showSubtitle: Boolean = true,
    val showPhone: Boolean = true,
    val showAddress: Boolean = false,
    val showDateTime: Boolean = true,
    val showReceiptTitle: Boolean = true,
    val showJobNumber: Boolean = true,
    val showCustomerName: Boolean = true,
    val showCustomerPhone: Boolean = true,
    val showPaymentReceived: Boolean = true,
    val showTotal: Boolean = true,
    val showPaid: Boolean = true,
    val showBalance: Boolean = true,
    val showPaymentStatus: Boolean = true,
    val showFooter: Boolean = true,

    // Text size
    val textSize: ReceiptTextSize = ReceiptTextSize.NORMAL,

    // Bold on/off
    val boldShopName: Boolean = true,
    val boldReceiptTitle: Boolean = true,
    val boldJobNumber: Boolean = true,
    val boldPaymentReceived: Boolean = true,
    val boldTotal: Boolean = true,
    val boldPaid: Boolean = true,
    val boldBalance: Boolean = true,

    // Alignment
    val headerAlignment: ReceiptAlignment = ReceiptAlignment.CENTER,
    val bodyAlignment: ReceiptAlignment = ReceiptAlignment.LEFT,
    val footerAlignment: ReceiptAlignment = ReceiptAlignment.CENTER,

    // Divider
    val dividerStyle: ReceiptDividerStyle = ReceiptDividerStyle.DASHED
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("shopName", shopName)
        json.put("subtitle", subtitle)
        json.put("phone", phone)
        json.put("address", address)
        json.put("whatsapp", whatsapp)
        json.put("receiptTitle", receiptTitle)
        json.put("footer", footer)

        json.put("showShopName", showShopName)
        json.put("showSubtitle", showSubtitle)
        json.put("showPhone", showPhone)
        json.put("showAddress", showAddress)
        json.put("showDateTime", showDateTime)
        json.put("showReceiptTitle", showReceiptTitle)
        json.put("showJobNumber", showJobNumber)
        json.put("showCustomerName", showCustomerName)
        json.put("showCustomerPhone", showCustomerPhone)
        json.put("showPaymentReceived", showPaymentReceived)
        json.put("showTotal", showTotal)
        json.put("showPaid", showPaid)
        json.put("showBalance", showBalance)
        json.put("showPaymentStatus", showPaymentStatus)
        json.put("showFooter", showFooter)

        json.put("textSize", textSize.name)

        json.put("boldShopName", boldShopName)
        json.put("boldReceiptTitle", boldReceiptTitle)
        json.put("boldJobNumber", boldJobNumber)
        json.put("boldPaymentReceived", boldPaymentReceived)
        json.put("boldTotal", boldTotal)
        json.put("boldPaid", boldPaid)
        json.put("boldBalance", boldBalance)

        json.put("headerAlignment", headerAlignment.name)
        json.put("bodyAlignment", bodyAlignment.name)
        json.put("footerAlignment", footerAlignment.name)

        json.put("dividerStyle", dividerStyle.name)
        return json.toString()
    }

    companion object {
        fun fromJson(jsonStr: String?): PaymentReceiptConfig {
            if (jsonStr.isNullOrBlank()) return PaymentReceiptConfig()
            return try {
                val json = JSONObject(jsonStr)
                PaymentReceiptConfig(
                    shopName = json.optString("shopName", "UDM MOBILE REPAIR"),
                    subtitle = json.optString("subtitle", "Mobile Phone Repair"),
                    phone = json.optString("phone", "07XXXXXXXX"),
                    address = json.optString("address", ""),
                    whatsapp = json.optString("whatsapp", ""),
                    receiptTitle = json.optString("receiptTitle", "PAYMENT RECEIPT"),
                    footer = json.optString("footer", "Thank You!\nUDM MOBILE REPAIR"),

                    showShopName = json.optBoolean("showShopName", true),
                    showSubtitle = json.optBoolean("showSubtitle", true),
                    showPhone = json.optBoolean("showPhone", true),
                    showAddress = json.optBoolean("showAddress", false),
                    showDateTime = json.optBoolean("showDateTime", true),
                    showReceiptTitle = json.optBoolean("showReceiptTitle", true),
                    showJobNumber = json.optBoolean("showJobNumber", true),
                    showCustomerName = json.optBoolean("showCustomerName", true),
                    showCustomerPhone = json.optBoolean("showCustomerPhone", true),
                    showPaymentReceived = json.optBoolean("showPaymentReceived", true),
                    showTotal = json.optBoolean("showTotal", true),
                    showPaid = json.optBoolean("showPaid", true),
                    showBalance = json.optBoolean("showBalance", true),
                    showPaymentStatus = json.optBoolean("showPaymentStatus", true),
                    showFooter = json.optBoolean("showFooter", true),

                    textSize = ReceiptTextSize.fromString(json.optString("textSize", "NORMAL")),

                    boldShopName = json.optBoolean("boldShopName", true),
                    boldReceiptTitle = json.optBoolean("boldReceiptTitle", true),
                    boldJobNumber = json.optBoolean("boldJobNumber", true),
                    boldPaymentReceived = json.optBoolean("boldPaymentReceived", true),
                    boldTotal = json.optBoolean("boldTotal", true),
                    boldPaid = json.optBoolean("boldPaid", true),
                    boldBalance = json.optBoolean("boldBalance", true),

                    headerAlignment = ReceiptAlignment.fromString(json.optString("headerAlignment", "CENTER")),
                    bodyAlignment = ReceiptAlignment.fromString(json.optString("bodyAlignment", "LEFT")),
                    footerAlignment = ReceiptAlignment.fromString(json.optString("footerAlignment", "CENTER")),

                    dividerStyle = ReceiptDividerStyle.fromString(json.optString("dividerStyle", "DASHED"))
                )
            } catch (_: Exception) {
                PaymentReceiptConfig()
            }
        }
    }
}

/**
 * Local persistent store for receipt customization settings.
 * Ensures complete independence between Customer Receipt and Payment Receipt.
 */
object ReceiptPreferences {
    private const val PREFS_NAME = "udm_receipt_customization_prefs"
    private const val KEY_CUSTOMER_RECEIPT = "customer_receipt_config_json"
    private const val KEY_PAYMENT_RECEIPT = "payment_receipt_config_json"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getCustomerReceiptConfig(context: Context): CustomerReceiptConfig {
        val json = getPrefs(context).getString(KEY_CUSTOMER_RECEIPT, null)
        return CustomerReceiptConfig.fromJson(json)
    }

    fun saveCustomerReceiptConfig(context: Context, config: CustomerReceiptConfig) {
        getPrefs(context).edit()
            .putString(KEY_CUSTOMER_RECEIPT, config.toJson())
            .apply()
    }

    fun resetCustomerReceiptConfig(context: Context): CustomerReceiptConfig {
        getPrefs(context).edit()
            .remove(KEY_CUSTOMER_RECEIPT)
            .apply()
        return CustomerReceiptConfig()
    }

    fun getPaymentReceiptConfig(context: Context): PaymentReceiptConfig {
        val json = getPrefs(context).getString(KEY_PAYMENT_RECEIPT, null)
        return PaymentReceiptConfig.fromJson(json)
    }

    fun savePaymentReceiptConfig(context: Context, config: PaymentReceiptConfig) {
        getPrefs(context).edit()
            .putString(KEY_PAYMENT_RECEIPT, config.toJson())
            .apply()
    }

    fun resetPaymentReceiptConfig(context: Context): PaymentReceiptConfig {
        getPrefs(context).edit()
            .remove(KEY_PAYMENT_RECEIPT)
            .apply()
        return PaymentReceiptConfig()
    }
}
