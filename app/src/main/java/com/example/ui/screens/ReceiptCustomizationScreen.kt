package com.example.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.printer.CustomerReceiptConfig
import com.example.printer.PaymentReceiptConfig
import com.example.printer.ReceiptAlignment
import com.example.printer.ReceiptBitmapGenerator
import com.example.printer.ReceiptDividerStyle
import com.example.printer.ReceiptTextSize
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.NavyBorder
import com.example.ui.theme.NavySurface
import com.example.ui.viewmodel.RepairViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptCustomizationScreen(
    viewModel: RepairViewModel,
    onNavigateBack: () -> Unit
) {
    BackHandler { onNavigateBack() }

    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val savedCustomerConfig by viewModel.customerReceiptConfig.collectAsState()
    val savedPaymentConfig by viewModel.paymentReceiptConfig.collectAsState()

    var activeTab by remember { mutableIntStateOf(0) } // 0 = Customer Receipt, 1 = Payment Receipt

    // Customer Receipt working local state
    var custConfig by remember(savedCustomerConfig) { mutableStateOf(savedCustomerConfig) }
    // Payment Receipt working local state
    var payConfig by remember(savedPaymentConfig) { mutableStateOf(savedPaymentConfig) }

    var showCustomerResetDialog by remember { mutableStateOf(false) }
    var showPaymentResetDialog by remember { mutableStateOf(false) }
    var showFullPreviewModal by remember { mutableStateOf(false) }

    // Live rendered bitmap previews
    val customerPreviewBitmap = remember(custConfig) {
        ReceiptBitmapGenerator.generateCustomerReceiptPreview(custConfig)
    }
    val paymentPreviewBitmap = remember(payConfig) {
        ReceiptBitmapGenerator.generatePaymentReceiptPreview(payConfig)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receipt Customization", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("receipt_custom_back_btn")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showFullPreviewModal = true },
                        modifier = Modifier.testTag("receipt_full_preview_btn")
                    ) {
                        Icon(Icons.Default.Preview, contentDescription = "Full Preview", tint = CyanAccent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (activeTab == 0) {
                                showCustomerResetDialog = true
                            } else {
                                showPaymentResetDialog = true
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag(if (activeTab == 0) "reset_customer_receipt_btn" else "reset_payment_receipt_btn")
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (activeTab == 0) "Reset Customer" else "Reset Payment",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Button(
                        onClick = {
                            if (activeTab == 0) {
                                viewModel.updateCustomerReceiptConfig(custConfig)
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Customer Receipt settings saved successfully!")
                                }
                            } else {
                                viewModel.updatePaymentReceiptConfig(payConfig)
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Payment Receipt settings saved successfully!")
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1.2f)
                            .height(48.dp)
                            .testTag("save_receipt_changes_btn")
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, tint = Color(0xFF090E1A), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("SAVE CHANGES", color = Color(0xFF090E1A), fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Main Top Tabs: Customer Receipt | Payment Receipt
            TabRow(
                selectedTabIndex = activeTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = CyanAccent,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[activeTab]),
                        color = CyanAccent
                    )
                }
            ) {
                Tab(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    text = {
                        Text(
                            "CUSTOMER RECEIPT",
                            fontWeight = if (activeTab == 0) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp
                        )
                    },
                    modifier = Modifier.testTag("tab_customer_receipt")
                )
                Tab(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    text = {
                        Text(
                            "PAYMENT RECEIPT",
                            fontWeight = if (activeTab == 1) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp
                        )
                    },
                    modifier = Modifier.testTag("tab_payment_receipt")
                )
            }

            // Tab Content
            if (activeTab == 0) {
                CustomerReceiptCustomizationTab(
                    config = custConfig,
                    onConfigChange = { custConfig = it },
                    previewBitmap = customerPreviewBitmap,
                    onOpenFullPreview = { showFullPreviewModal = true }
                )
            } else {
                PaymentReceiptCustomizationTab(
                    config = payConfig,
                    onConfigChange = { payConfig = it },
                    previewBitmap = paymentPreviewBitmap,
                    onOpenFullPreview = { showFullPreviewModal = true }
                )
            }
        }
    }

    // Confirmation Dialog: Reset Customer Receipt
    if (showCustomerResetDialog) {
        AlertDialog(
            onDismissRequest = { showCustomerResetDialog = false },
            title = { Text("Reset Customer Receipt?") },
            text = { Text("This will reset all Customer Receipt customization settings back to default. Payment Receipt settings will NOT be changed.") },
            confirmButton = {
                Button(
                    onClick = {
                        showCustomerResetDialog = false
                        viewModel.resetCustomerReceiptConfig()
                        custConfig = CustomerReceiptConfig()
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Customer Receipt reset to default")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Reset Customer Receipt", color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomerResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Confirmation Dialog: Reset Payment Receipt
    if (showPaymentResetDialog) {
        AlertDialog(
            onDismissRequest = { showPaymentResetDialog = false },
            title = { Text("Reset Payment Receipt?") },
            text = { Text("This will reset all Payment Receipt customization settings back to default. Customer Receipt settings will NOT be changed.") },
            confirmButton = {
                Button(
                    onClick = {
                        showPaymentResetDialog = false
                        viewModel.resetPaymentReceiptConfig()
                        payConfig = PaymentReceiptConfig()
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Payment Receipt reset to default")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Reset Payment Receipt", color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPaymentResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Full Screen Thermal Receipt Preview Modal
    if (showFullPreviewModal) {
        Dialog(
            onDismissRequest = { showFullPreviewModal = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF121824)),
                color = Color(0xFF121824)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (activeTab == 0) "Customer Receipt (384-dot Preview)" else "Payment Receipt (384-dot Preview)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color.White
                        )
                        IconButton(onClick = { showFullPreviewModal = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Column(
                            modifier = Modifier
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            ReceiptPaperContainer(
                                bitmap = if (activeTab == 0) customerPreviewBitmap else paymentPreviewBitmap
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Customer Receipt Section Editor & Live Preview
 */
@Composable
fun CustomerReceiptCustomizationTab(
    config: CustomerReceiptConfig,
    onConfigChange: (CustomerReceiptConfig) -> Unit,
    previewBitmap: Bitmap,
    onOpenFullPreview: () -> Unit
) {
    var subTab by remember { mutableIntStateOf(0) } // 0 = Customize, 1 = Live Preview

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = subTab == 0,
                onClick = { subTab = 0 },
                label = { Text("Customize Layout") },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CyanAccent, selectedLabelColor = Color(0xFF090E1A)),
                modifier = Modifier.testTag("cust_subtab_customize")
            )
            FilterChip(
                selected = subTab == 1,
                onClick = { subTab = 1 },
                label = { Text("Live Preview (384-dot)") },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CyanAccent, selectedLabelColor = Color(0xFF090E1A)),
                modifier = Modifier.testTag("cust_subtab_preview")
            )
        }

        if (subTab == 1) {
            // Live Preview Tab
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "384-dot Marklife P50S Continuous Thermal Paper",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    ReceiptPaperContainer(bitmap = previewBitmap)
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        } else {
            // Customize Tab
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Embedded quick preview card
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenFullPreview() },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Preview, contentDescription = null, tint = CyanAccent)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text("Live Receipt Preview", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("Tap to view full 384-dot thermal preview", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text("VIEW", color = CyanAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }

                // SHOP INFORMATION
                item {
                    SectionGroup(title = "SHOP INFORMATION") {
                        OutlinedTextField(
                            value = config.shopName,
                            onValueChange = { onConfigChange(config.copy(shopName = it)) },
                            label = { Text("Shop Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("cust_shop_name_input")
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = config.subtitle,
                            onValueChange = { onConfigChange(config.copy(subtitle = it)) },
                            label = { Text("Subtitle") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("cust_subtitle_input")
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = config.phone,
                            onValueChange = { onConfigChange(config.copy(phone = it)) },
                            label = { Text("Phone Number") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("cust_phone_input")
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = config.address,
                            onValueChange = { onConfigChange(config.copy(address = it)) },
                            label = { Text("Address (Optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("cust_address_input")
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = config.whatsapp,
                            onValueChange = { onConfigChange(config.copy(whatsapp = it)) },
                            label = { Text("WhatsApp Number (Optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("cust_wa_input")
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = config.footer,
                            onValueChange = { onConfigChange(config.copy(footer = it)) },
                            label = { Text("Footer / Thank You Text") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth().testTag("cust_footer_input")
                        )
                    }
                }

                // SHOW / HIDE TOGGLES
                item {
                    SectionGroup(title = "SHOW / HIDE FIELDS") {
                        ToggleRow("Shop Name", config.showShopName) { onConfigChange(config.copy(showShopName = it)) }
                        ToggleRow("Subtitle", config.showSubtitle) { onConfigChange(config.copy(showSubtitle = it)) }
                        ToggleRow("Phone Number", config.showPhone) { onConfigChange(config.copy(showPhone = it)) }
                        ToggleRow("Address", config.showAddress) { onConfigChange(config.copy(showAddress = it)) }
                        ToggleRow("Date & Time", config.showDateTime) { onConfigChange(config.copy(showDateTime = it)) }
                        ToggleRow("Job Number", config.showJobNumber) { onConfigChange(config.copy(showJobNumber = it)) }
                        ToggleRow("Customer Name", config.showCustomerName) { onConfigChange(config.copy(showCustomerName = it)) }
                        ToggleRow("Customer Phone", config.showCustomerPhone) { onConfigChange(config.copy(showCustomerPhone = it)) }
                        ToggleRow("Brand", config.showBrand) { onConfigChange(config.copy(showBrand = it)) }
                        ToggleRow("Model", config.showModel) { onConfigChange(config.copy(showModel = it)) }
                        ToggleRow("IMEI", config.showImei) { onConfigChange(config.copy(showImei = it)) }
                        ToggleRow("Complaint", config.showComplaint) { onConfigChange(config.copy(showComplaint = it)) }
                        ToggleRow("Repair Items", config.showRepairItems) { onConfigChange(config.copy(showRepairItems = it)) }
                        ToggleRow("Total", config.showTotal) { onConfigChange(config.copy(showTotal = it)) }
                        ToggleRow("Paid", config.showPaid) { onConfigChange(config.copy(showPaid = it)) }
                        ToggleRow("Balance", config.showBalance) { onConfigChange(config.copy(showBalance = it)) }
                        ToggleRow("Payment Status", config.showPaymentStatus) { onConfigChange(config.copy(showPaymentStatus = it)) }
                        ToggleRow("Footer / Thank You", config.showFooter) { onConfigChange(config.copy(showFooter = it)) }
                    }
                }

                // TEXT SIZE
                item {
                    SectionGroup(title = "TEXT SIZE") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextSizeOption(
                                label = "Small",
                                selected = config.textSize == ReceiptTextSize.SMALL,
                                modifier = Modifier.weight(1f)
                            ) { onConfigChange(config.copy(textSize = ReceiptTextSize.SMALL)) }

                            TextSizeOption(
                                label = "Normal",
                                selected = config.textSize == ReceiptTextSize.NORMAL,
                                modifier = Modifier.weight(1f)
                            ) { onConfigChange(config.copy(textSize = ReceiptTextSize.NORMAL)) }

                            TextSizeOption(
                                label = "Large",
                                selected = config.textSize == ReceiptTextSize.LARGE,
                                modifier = Modifier.weight(1f)
                            ) { onConfigChange(config.copy(textSize = ReceiptTextSize.LARGE)) }
                        }
                    }
                }

                // BOLD ON / OFF
                item {
                    SectionGroup(title = "BOLD STYLING") {
                        ToggleRow("Shop Name (Bold)", config.boldShopName) { onConfigChange(config.copy(boldShopName = it)) }
                        ToggleRow("Job Number (Bold)", config.boldJobNumber) { onConfigChange(config.copy(boldJobNumber = it)) }
                        ToggleRow("Total (Bold)", config.boldTotal) { onConfigChange(config.copy(boldTotal = it)) }
                        ToggleRow("Paid (Bold)", config.boldPaid) { onConfigChange(config.copy(boldPaid = it)) }
                        ToggleRow("Balance (Bold)", config.boldBalance) { onConfigChange(config.copy(boldBalance = it)) }
                    }
                }

                // ALIGNMENT
                item {
                    SectionGroup(title = "ALIGNMENT") {
                        AlignmentPicker(
                            label = "Header Alignment",
                            selected = config.headerAlignment,
                            onSelect = { onConfigChange(config.copy(headerAlignment = it)) }
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        AlignmentPicker(
                            label = "Body Alignment",
                            selected = config.bodyAlignment,
                            onSelect = { onConfigChange(config.copy(bodyAlignment = it)) }
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        AlignmentPicker(
                            label = "Footer Alignment",
                            selected = config.footerAlignment,
                            onSelect = { onConfigChange(config.copy(footerAlignment = it)) }
                        )
                    }
                }

                // DIVIDER
                item {
                    SectionGroup(title = "DIVIDER STYLE") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DividerOption(
                                label = "Dashed",
                                preview = "-------",
                                selected = config.dividerStyle == ReceiptDividerStyle.DASHED,
                                modifier = Modifier.weight(1f)
                            ) { onConfigChange(config.copy(dividerStyle = ReceiptDividerStyle.DASHED)) }

                            DividerOption(
                                label = "Solid",
                                preview = "━━━━━━━",
                                selected = config.dividerStyle == ReceiptDividerStyle.SOLID,
                                modifier = Modifier.weight(1f)
                            ) { onConfigChange(config.copy(dividerStyle = ReceiptDividerStyle.SOLID)) }

                            DividerOption(
                                label = "None",
                                preview = "(Empty)",
                                selected = config.dividerStyle == ReceiptDividerStyle.NONE,
                                modifier = Modifier.weight(1f)
                            ) { onConfigChange(config.copy(dividerStyle = ReceiptDividerStyle.NONE)) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Payment Receipt Section Editor & Live Preview
 */
@Composable
fun PaymentReceiptCustomizationTab(
    config: PaymentReceiptConfig,
    onConfigChange: (PaymentReceiptConfig) -> Unit,
    previewBitmap: Bitmap,
    onOpenFullPreview: () -> Unit
) {
    var subTab by remember { mutableIntStateOf(0) } // 0 = Customize, 1 = Live Preview

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = subTab == 0,
                onClick = { subTab = 0 },
                label = { Text("Customize Layout") },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CyanAccent, selectedLabelColor = Color(0xFF090E1A)),
                modifier = Modifier.testTag("pay_subtab_customize")
            )
            FilterChip(
                selected = subTab == 1,
                onClick = { subTab = 1 },
                label = { Text("Live Preview (384-dot)") },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CyanAccent, selectedLabelColor = Color(0xFF090E1A)),
                modifier = Modifier.testTag("pay_subtab_preview")
            )
        }

        if (subTab == 1) {
            // Live Preview Tab
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "384-dot Marklife P50S Continuous Thermal Paper",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    ReceiptPaperContainer(bitmap = previewBitmap)
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        } else {
            // Customize Tab
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Embedded quick preview card
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenFullPreview() },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Preview, contentDescription = null, tint = CyanAccent)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text("Live Receipt Preview", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("Tap to view full 384-dot thermal preview", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text("VIEW", color = CyanAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }

                // SHOP INFORMATION
                item {
                    SectionGroup(title = "SHOP INFORMATION") {
                        OutlinedTextField(
                            value = config.shopName,
                            onValueChange = { onConfigChange(config.copy(shopName = it)) },
                            label = { Text("Shop Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("pay_shop_name_input")
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = config.subtitle,
                            onValueChange = { onConfigChange(config.copy(subtitle = it)) },
                            label = { Text("Subtitle") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("pay_subtitle_input")
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = config.phone,
                            onValueChange = { onConfigChange(config.copy(phone = it)) },
                            label = { Text("Phone Number") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("pay_phone_input")
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = config.receiptTitle,
                            onValueChange = { onConfigChange(config.copy(receiptTitle = it)) },
                            label = { Text("Payment Receipt Title") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("pay_title_input")
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = config.address,
                            onValueChange = { onConfigChange(config.copy(address = it)) },
                            label = { Text("Address (Optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("pay_address_input")
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = config.whatsapp,
                            onValueChange = { onConfigChange(config.copy(whatsapp = it)) },
                            label = { Text("WhatsApp Number (Optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("pay_wa_input")
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = config.footer,
                            onValueChange = { onConfigChange(config.copy(footer = it)) },
                            label = { Text("Footer / Thank You Text") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth().testTag("pay_footer_input")
                        )
                    }
                }

                // SHOW / HIDE TOGGLES
                item {
                    SectionGroup(title = "SHOW / HIDE FIELDS") {
                        ToggleRow("Shop Name", config.showShopName) { onConfigChange(config.copy(showShopName = it)) }
                        ToggleRow("Subtitle", config.showSubtitle) { onConfigChange(config.copy(showSubtitle = it)) }
                        ToggleRow("Phone Number", config.showPhone) { onConfigChange(config.copy(showPhone = it)) }
                        ToggleRow("Address", config.showAddress) { onConfigChange(config.copy(showAddress = it)) }
                        ToggleRow("Date & Time", config.showDateTime) { onConfigChange(config.copy(showDateTime = it)) }
                        ToggleRow("Payment Receipt Title", config.showReceiptTitle) { onConfigChange(config.copy(showReceiptTitle = it)) }
                        ToggleRow("Job Number", config.showJobNumber) { onConfigChange(config.copy(showJobNumber = it)) }
                        ToggleRow("Customer Name", config.showCustomerName) { onConfigChange(config.copy(showCustomerName = it)) }
                        ToggleRow("Customer Phone", config.showCustomerPhone) { onConfigChange(config.copy(showCustomerPhone = it)) }
                        ToggleRow("Payment Received", config.showPaymentReceived) { onConfigChange(config.copy(showPaymentReceived = it)) }
                        ToggleRow("Total", config.showTotal) { onConfigChange(config.copy(showTotal = it)) }
                        ToggleRow("Paid", config.showPaid) { onConfigChange(config.copy(showPaid = it)) }
                        ToggleRow("Balance", config.showBalance) { onConfigChange(config.copy(showBalance = it)) }
                        ToggleRow("Payment Status", config.showPaymentStatus) { onConfigChange(config.copy(showPaymentStatus = it)) }
                        ToggleRow("Footer / Thank You", config.showFooter) { onConfigChange(config.copy(showFooter = it)) }
                    }
                }

                // TEXT SIZE
                item {
                    SectionGroup(title = "TEXT SIZE") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextSizeOption(
                                label = "Small",
                                selected = config.textSize == ReceiptTextSize.SMALL,
                                modifier = Modifier.weight(1f)
                            ) { onConfigChange(config.copy(textSize = ReceiptTextSize.SMALL)) }

                            TextSizeOption(
                                label = "Normal",
                                selected = config.textSize == ReceiptTextSize.NORMAL,
                                modifier = Modifier.weight(1f)
                            ) { onConfigChange(config.copy(textSize = ReceiptTextSize.NORMAL)) }

                            TextSizeOption(
                                label = "Large",
                                selected = config.textSize == ReceiptTextSize.LARGE,
                                modifier = Modifier.weight(1f)
                            ) { onConfigChange(config.copy(textSize = ReceiptTextSize.LARGE)) }
                        }
                    }
                }

                // BOLD ON / OFF
                item {
                    SectionGroup(title = "BOLD STYLING") {
                        ToggleRow("Shop Name (Bold)", config.boldShopName) { onConfigChange(config.copy(boldShopName = it)) }
                        ToggleRow("Payment Receipt Title (Bold)", config.boldReceiptTitle) { onConfigChange(config.copy(boldReceiptTitle = it)) }
                        ToggleRow("Job Number (Bold)", config.boldJobNumber) { onConfigChange(config.copy(boldJobNumber = it)) }
                        ToggleRow("Payment Received (Bold)", config.boldPaymentReceived) { onConfigChange(config.copy(boldPaymentReceived = it)) }
                        ToggleRow("Total (Bold)", config.boldTotal) { onConfigChange(config.copy(boldTotal = it)) }
                        ToggleRow("Paid (Bold)", config.boldPaid) { onConfigChange(config.copy(boldPaid = it)) }
                        ToggleRow("Balance (Bold)", config.boldBalance) { onConfigChange(config.copy(boldBalance = it)) }
                    }
                }

                // ALIGNMENT
                item {
                    SectionGroup(title = "ALIGNMENT") {
                        AlignmentPicker(
                            label = "Header Alignment",
                            selected = config.headerAlignment,
                            onSelect = { onConfigChange(config.copy(headerAlignment = it)) }
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        AlignmentPicker(
                            label = "Body Alignment",
                            selected = config.bodyAlignment,
                            onSelect = { onConfigChange(config.copy(bodyAlignment = it)) }
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        AlignmentPicker(
                            label = "Footer Alignment",
                            selected = config.footerAlignment,
                            onSelect = { onConfigChange(config.copy(footerAlignment = it)) }
                        )
                    }
                }

                // DIVIDER
                item {
                    SectionGroup(title = "DIVIDER STYLE") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DividerOption(
                                label = "Dashed",
                                preview = "-------",
                                selected = config.dividerStyle == ReceiptDividerStyle.DASHED,
                                modifier = Modifier.weight(1f)
                            ) { onConfigChange(config.copy(dividerStyle = ReceiptDividerStyle.DASHED)) }

                            DividerOption(
                                label = "Solid",
                                preview = "━━━━━━━",
                                selected = config.dividerStyle == ReceiptDividerStyle.SOLID,
                                modifier = Modifier.weight(1f)
                            ) { onConfigChange(config.copy(dividerStyle = ReceiptDividerStyle.SOLID)) }

                            DividerOption(
                                label = "None",
                                preview = "(Empty)",
                                selected = config.dividerStyle == ReceiptDividerStyle.NONE,
                                modifier = Modifier.weight(1f)
                            ) { onConfigChange(config.copy(dividerStyle = ReceiptDividerStyle.NONE)) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Clean thermal paper style container for rendering the 384-dot receipt bitmap.
 */
@Composable
fun ReceiptPaperContainer(bitmap: Bitmap) {
    Card(
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        modifier = Modifier
            .width(320.dp)
            .border(1.dp, Color(0xFFD0D0D0), RoundedCornerShape(6.dp))
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 12.dp)
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Receipt Thermal Output",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
private fun SectionGroup(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                color = CyanAccent,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 14.sp)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF090E1A),
                checkedTrackColor = CyanAccent
            )
        )
    }
}

@Composable
private fun TextSizeOption(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) CyanAccent else MaterialTheme.colorScheme.outline
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) CyanAccent.copy(alpha = 0.15f) else Color.Transparent
        )
    ) {
        Text(
            text = label,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) CyanAccent else MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun AlignmentPicker(
    label: String,
    selected: ReceiptAlignment,
    onSelect: (ReceiptAlignment) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val options = listOf(
                Triple("Left", ReceiptAlignment.LEFT, Icons.AutoMirrored.Filled.FormatAlignLeft),
                Triple("Center", ReceiptAlignment.CENTER, Icons.Default.FormatAlignCenter),
                Triple("Right", ReceiptAlignment.RIGHT, Icons.AutoMirrored.Filled.FormatAlignRight)
            )

            options.forEach { (text, align, icon) ->
                val isSel = selected == align
                OutlinedButton(
                    onClick = { onSelect(align) },
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        if (isSel) 2.dp else 1.dp,
                        if (isSel) CyanAccent else MaterialTheme.colorScheme.outline
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (isSel) CyanAccent.copy(alpha = 0.15f) else Color.Transparent
                    ),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isSel) CyanAccent else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = text,
                        fontSize = 12.sp,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSel) CyanAccent else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun DividerOption(
    label: String,
    preview: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) CyanAccent else MaterialTheme.colorScheme.outline
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) CyanAccent.copy(alpha = 0.15f) else Color.Transparent
        ),
        contentPadding = PaddingValues(4.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) CyanAccent else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = preview,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = if (selected) CyanAccent else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
