package com.example.printer

import android.content.Context
import android.content.SharedPreferences

object PrinterPreferences {
    private const val PREFS_NAME = "udm_printer_prefs"
    private const val KEY_PRINTER_NAME = "saved_printer_name"
    private const val KEY_PRINTER_MAC = "saved_printer_mac"
    private const val KEY_AUTO_PRINT = "auto_print_customer_receipt"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getSavedPrinterName(context: Context): String? {
        return getPrefs(context).getString(KEY_PRINTER_NAME, null)
    }

    fun getSavedPrinterMac(context: Context): String? {
        return getPrefs(context).getString(KEY_PRINTER_MAC, null)
    }

    fun savePrinter(context: Context, name: String, mac: String) {
        getPrefs(context).edit()
            .putString(KEY_PRINTER_NAME, name)
            .putString(KEY_PRINTER_MAC, mac)
            .apply()
    }

    fun clearSavedPrinter(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_PRINTER_NAME)
            .remove(KEY_PRINTER_MAC)
            .apply()
    }

    fun isAutoPrintEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_PRINT, false)
    }

    fun setAutoPrintEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit()
            .putBoolean(KEY_AUTO_PRINT, enabled)
            .apply()
    }

    private const val KEY_CUSTOMER_RECEIPT_DIAGNOSTIC_MODE = "customer_receipt_diagnostic_mode"

    /**
     * Diagnostic mode prints explicit SLICE 0, SLICE 1, SLICE 2 test labels
     * to identify where P50S physical printing halts.
     * Defaulted to false for normal operation.
     */
    fun isCustomerReceiptDiagnosticMode(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_CUSTOMER_RECEIPT_DIAGNOSTIC_MODE, false)
    }

    fun setCustomerReceiptDiagnosticMode(context: Context, enabled: Boolean) {
        getPrefs(context).edit()
            .putBoolean(KEY_CUSTOMER_RECEIPT_DIAGNOSTIC_MODE, enabled)
            .apply()
    }
}
