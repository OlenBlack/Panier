
package com.monpanier.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Screen { HOME, SCAN, REVIEW, HISTORY, STATS, PRICES, BASKET, SETTINGS }
private enum class AppLang { FR, AR }
enum class Money(val label: String) { DZD("DZD"), CAD("CAD") }
private data class Draft(val product: String, val quantity: String, val unitPrice: String, val total: String)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val crashFile = java.io.File(filesDir, "startup-crash.txt")

        // Enregistrer les erreurs non interceptées
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                crashFile.writeText(
                    android.util.Log.getStackTraceString(error)
                )
            }

            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }

        // Vérifier si un ancien rapport existe
        val previousCrash = runCatching {
            if (crashFile.exists()) crashFile.readText() else null
        }.getOrNull()

        if (!previousCrash.isNullOrBlank()) {
            setContent {
                MaterialTheme {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Text(
                            "Rapport d'erreur Mon Panier",
                            style = MaterialTheme.typography.titleLarge
                        )

                        Spacer(Modifier.height(12.dp))

                        SelectionContainer(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            Text(
                                previousCrash,
                                Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                            )
                        }

                        Button(
                            onClick = {
                                crashFile.delete()
                                recreate()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Effacer le rapport et relancer")
                        }
                    }
                }
            }
        } else {
            setContent {
                MonPanierV2()
            }
        }
    }
}

@Composable
private fun MonPanierV2() {
    val context = LocalContext.current
    val dao = remember { PurchaseDatabase.get(context).purchaseDao() }
    val purchases by dao.observeAll().collectAsState(initial = emptyList())

    val prefs = remember {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    var dark by remember { mutableStateOf(prefs.getBoolean("dark", false)) }
    var lang by remember {
        mutableStateOf(
            if (prefs.getString("lang", "FR") == "AR") AppLang.AR else AppLang.FR
        )
    }
    var money by remember {
        mutableStateOf(
            if (prefs.getString("money", "DZD") == "CAD") Money.CAD else Money.DZD
        )
    }

    var screen by remember { mutableStateOf(Screen.HOME) }
    var ocrText by remember { mutableStateOf("") }
    var store by remember { mutableStateOf("") }
    var drafts by remember { mutableStateOf(listOf<Draft>()) }
    var notice by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    val green = if (dark) Color(0xFF70D9A0) else Color(0xFF078A4B)
    val bg = if (dark) Color(0xFF101713) else Color(0xFFF4F8F5)
    val card = if (dark) Color(0xFF1C2922) else Color.White
    val fg = if (dark) Color(0xFFEAF4ED) else Color(0xFF17221B)

    val isAr = lang == AppLang.AR
    val currencyCode = money.name
    val total = purchases.filter { it.currency == currencyCode }.sumOf { it.total }

    val createXlsx = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        )
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        ExcelExporter.export(purchases, it)
                    } ?: error("Impossible d'ouvrir le fichier")
                }.onSuccess {
                    notice = if (isAr) "تم تصدير ملف Excel" else "Fichier Excel exporté"
                }.onFailure {
                    notice = "Erreur export : ${it.message}"
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = if (dark) {
            darkColorScheme(primary = green, background = bg, surface = card)
        } else {
            lightColorScheme(primary = green, background = bg, surface = card)
        }
    ) {
        Scaffold(
            containerColor = bg,
            topBar = {
                TopAppBarV2(isAr, fg, green) {
                    screen = Screen.SETTINGS
                }
            },
            bottomBar = {
                NavigationBar(containerColor = card) {
                    NavItem(
                        screen == Screen.HOME,
                        { screen = Screen.HOME },
                        Icons.Default.Home,
                        if (isAr) "الرئيسية" else "Accueil"
                    )
                    NavItem(
                        screen == Screen.STATS,
                        { screen = Screen.STATS },
                        Icons.Default.BarChart,
                        if (isAr) "إحصائيات" else "Stats"
                    )
                    NavItem(
                        screen == Screen.HISTORY,
                        { screen = Screen.HISTORY },
                        Icons.Default.ReceiptLong,
                        if (isAr) "السجل" else "Achats"
                    )
                    NavItem(
                        screen == Screen.BASKET,
                        { screen = Screen.BASKET },
                        Icons.Default.ShoppingCart,
                        if (isAr) "السلة" else "Panier"
                    )
                    NavItem(
                        screen == Screen.PRICES,
                        { screen = Screen.PRICES },
                        Icons.Default.ShowChart,
                        if (isAr) "الأسعار" else "Prix"
                    )
                    NavItem(
                        screen == Screen.SETTINGS,
                        { screen = Screen.SETTINGS },
                        Icons.Default.Settings,
                        if (isAr) "إعدادات" else "Réglages"
                    )
                }
            },
            snackbarHost = {
                SnackbarHost(remember { SnackbarHostState() })
            }
        ) { pad ->

            when (screen) {
                Screen.HOME -> HomeV2(
                    pad, purchases, money, lang, total, fg, card,
                    { screen = Screen.SCAN },
                    { screen = Screen.HISTORY }
                )

                Screen.SCAN -> ScannerScreen(
                    pad, isAr, fg, green,
                    onText = { text ->
                        ocrText = text
                        drafts = ReceiptParser.parse(text).map {
                            Draft(
                                it.product,
                                it.quantity.toString(),
                                "%.2f".format(Locale.US, it.unitPrice),
                                "%.2f".format(Locale.US, it.total)
                            )
                        }
                        if (drafts.isEmpty()) {
                            drafts = listOf(Draft("", "1", "", ""))
                        }
                        screen = Screen.REVIEW
                    }
                )

                Screen.REVIEW -> ReviewScreen(
                    pad, isAr, fg, card, store,
                    { store = it },
                    drafts,
                    { drafts = it },
                    ocrText,
                    onSave = {
                        val rid = ReceiptParser.receiptId()

                        val rows = drafts.mapNotNull { d ->
                            val q = d.quantity.replace(',', '.').toDoubleOrNull()
                            val price = d.unitPrice.replace(',', '.').toDoubleOrNull()
                            val amount = d.total.replace(',', '.').toDoubleOrNull()

                            if (
                                d.product.isBlank() ||
                                q == null ||
                                price == null ||
                                amount == null
                            ) {
                                null
                            } else {
                                PurchaseEntity(
                                    receiptDate = ReceiptParser.today(),
                                    store = store.ifBlank { "Magasin" },
                                    product = d.product,
                                    quantity = q,
                                    unitPrice = price,
                                    total = amount,
                                    currency = currencyCode,
                                    receiptId = rid
                                )
                            }
                        }

                        scope.launch {
                            if (rows.isNotEmpty()) {
                                dao.insertAll(rows)
                                notice = if (isAr) "تم حفظ المشتريات" else "Achats enregistrés"
                                screen = Screen.HOME
                            }
                        }
                    },
                    { screen = Screen.SCAN }
                )

                Screen.HISTORY -> HistoryV2(
                    pad, purchases, money, isAr, fg, card,
                    onExport = {
                        createXlsx.launch("MonPanier-achats.xlsx")
                    }
                )

                Screen.STATS -> StatsV2(pad, purchases, money, isAr, fg, card)

                Screen.PRICES -> PriceHistoryScreen(
                    pad, purchases, money, isAr, fg, card
                )

                Screen.BASKET -> SmartBasketScreen(
                    pad, purchases, money, isAr, fg, card
                )

                Screen.SETTINGS -> SettingsV2(
                    pad,
                    dark,
                    {
                        dark = it
                        prefs.edit().putBoolean("dark", it).apply()
                    },
                    money,
                    {
                        money = it
                        prefs.edit().putString("money", it.name).apply()
                    },
                    lang,
                    {
                        lang = it
                        prefs.edit().putString("lang", it.name).apply()
                    },
                    isAr,
                    fg,
                    card
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopAppBarV2(
    ar: Boolean,
    fg: Color,
    green: Color,
    onSettings: () -> Unit
) {
    CenterAlignedTopAppBar(
        title = {
            Text("Mon Panier", fontWeight = FontWeight.Bold, color = fg)
        },
        navigationIcon = {
            Icon(
                Icons.Default.ShoppingCart,
                null,
                tint = green,
                modifier = Modifier.padding(start = 14.dp)
            )
        },
        actions = {
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, null, tint = fg)
            }
        }
    )
}

@Composable
private fun RowScope.NavItem(
    selected: Boolean,
    click: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String
) {
    NavigationBarItem(
        selected = selected,
        onClick = click,
        icon = { Icon(icon, null) },
        label = { Text(label) }
    )
}

@Composable
private fun HomeV2(
    pad: PaddingValues,
    rows: List<PurchaseEntity>,
    money: Money,
    lang: AppLang,
    total: Double,
    fg: Color,
    card: Color,
    onScan: () -> Unit,
    onHistory: () -> Unit
) {
    val ar = lang == AppLang.AR
    val symbol = if (money == Money.DZD) "DA" else "CAD"
    val scoped = rows.filter { it.currency == money.name }
    val sum = scoped.sumOf { it.total }

    LazyColumn(
        Modifier.fillMaxSize().padding(pad).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                if (ar) "مرحباً بك في Mon Panier" else "Bienvenue dans Mon Panier",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = fg
            )
            Text(
                if (ar) "تابع مشترياتك ونفقاتك" else "Scanne tes tickets et suis tes dépenses.",
                color = fg.copy(alpha = .7f)
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF078A4B)),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text(
                        if (ar) "إجمالي المشتريات" else "Total des achats",
                        color = Color.White
                    )
                    Text(
                        "${"%.2f".format(Locale.FRANCE, sum)} $symbol",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        "${scoped.size} ${if (ar) "منتج مسجل" else "lignes enregistrées"}",
                        color = Color.White
                    )
                }
            }
        }

        item {
            Button(
                onClick = onScan,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Icon(Icons.Default.PhotoCamera, null)
                Spacer(Modifier.width(8.dp))
                Text(if (ar) "مسح إيصال" else "Scanner un ticket")
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallMetric(
                    Modifier.weight(1f),
                    if (ar) "المنتجات" else "Articles",
                    scoped.sumOf { it.quantity }.toInt().toString(),
                    card,
                    fg
                )
                SmallMetric(
                    Modifier.weight(1f),
                    if (ar) "الإيصالات" else "Tickets",
                    scoped.map { it.receiptId }.distinct().size.toString(),
                    card,
                    fg
                )
            }
        }

        item {
            Text(
                if (ar) "آخر المشتريات" else "Derniers achats",
                fontWeight = FontWeight.Bold,
                fontSize = 19.sp,
                color = fg
            )
        }

        items(scoped.take(5)) {
            PurchaseRowV2(it, money, fg, card)
        }

        if (rows.isEmpty()) {
            item {
                Text(
                    if (ar) "لا توجد مشتريات بعد. ابدأ بمسح أول إيصال."
                    else "Aucun achat pour le moment. Scanne ton premier ticket.",
                    color = fg.copy(alpha = .65f)
                )
            }
        }

        item {
            TextButton(onClick = onHistory) {
                Text(if (ar) "عرض السجل الكامل" else "Voir tout l'historique")
            }
        }
    }
}

@Composable
private fun SmallMetric(
    modifier: Modifier,
    label: String,
    value: String,
    card: Color,
    fg: Color
) {
    Card(
        modifier,
        colors = CardDefaults.cardColors(containerColor = card)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = fg)
            Text(label, color = fg.copy(alpha = .7f))
        }
    }
}

@Composable
private fun ScannerScreen(
    pad: PaddingValues,
    ar: Boolean,
    fg: Color,
    green: Color,
    onText: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }

    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var message by remember { mutableStateOf("") }

    var permission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        permission = it
    }

    Column(
        Modifier.fillMaxSize().padding(pad).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            if (ar) "امسح إيصال الشراء" else "Scanner un ticket",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = fg
        )

        if (!permission) {
            Text(
                if (ar) "يلزم السماح باستعمال الكاميرا"
                else "L'accès à la caméra est nécessaire.",
                color = fg
            )

            Button(onClick = { request.launch(Manifest.permission.CAMERA) }) {
                Text(if (ar) "السماح" else "Autoriser la caméra")
            }
        } else {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)

                    providerFuture.addListener({
                        val provider = providerFuture.get()

                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val imageCapture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .build()

                        capture = imageCapture

                        try {
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageCapture
                            )
                        } catch (e: Exception) {
                            message = e.localizedMessage ?: "Erreur caméra"
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                }
            )

            Button(
                onClick = {
                    val cap = capture ?: return@Button

                    message = if (ar) "جارٍ تحليل الإيصال…" else "Lecture du ticket en cours…"

                    cap.takePicture(
                        executor,
                        object : ImageCapture.OnImageCapturedCallback() {

                            override fun onCaptureSuccess(image: ImageProxy) {
                                val media = image.image

                                if (media == null) {
                                    image.close()
                                    message = "Image invalide"
                                    return
                                }

                                val input = InputImage.fromMediaImage(
                                    media,
                                    image.imageInfo.rotationDegrees
                                )

                                TextRecognition.getClient(
                                    TextRecognizerOptions.DEFAULT_OPTIONS
                                ).process(input)
                                    .addOnSuccessListener { result ->
                                        image.close()
                                        onText(result.text)
                                    }
                                    .addOnFailureListener { e ->
                                        image.close()
                                        message = "OCR : ${e.localizedMessage}"
                                    }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                message = exception.localizedMessage ?: "Erreur de capture"
                            }
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) {
                Icon(Icons.Default.CameraAlt, null)
                Spacer(Modifier.width(8.dp))
                Text(if (ar) "التقاط وقراءة" else "Photographier et lire le ticket")
            }

            Text(
                if (ar) "ضع الإيصال داخل الإطار مع إضاءة جيدة."
                else "Place le ticket bien à plat, dans un endroit éclairé.",
                color = fg.copy(alpha = .7f)
            )

            if (message.isNotBlank()) {
                Text(message, color = green)
            }
        }
    }
}

@Composable
private fun ReviewScreen(
    pad: PaddingValues,
    ar: Boolean,
    fg: Color,
    card: Color,
    store: String,
    onStore: (String) -> Unit,
    drafts: List<Draft>,
    onDrafts: (List<Draft>) -> Unit,
    raw: String,
    onSave: () -> Unit,
    onRescan: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(pad).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            if (ar) "تحقق من المنتجات" else "Vérifier les articles",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = fg
        )

        OutlinedTextField(
            value = store,
            onValueChange = onStore,
            label = { Text(if (ar) "المتجر" else "Magasin") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Text(
            if (ar) "صحح المعلومات قبل الحفظ"
            else "Corrige les lignes détectées avant l'enregistrement.",
            color = fg.copy(alpha = .7f)
        )

        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(drafts.size) { i ->
                val d = drafts[i]

                Card(colors = CardDefaults.cardColors(containerColor = card)) {
                    Column(Modifier.padding(10.dp)) {
                        OutlinedTextField(
                            d.product,
                            { v ->
                                onDrafts(
                                    drafts.toMutableList().also {
                                        it[i] = d.copy(product = v)
                                    }
                                )
                            },
                            label = { Text(if (ar) "المنتج" else "Produit") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                d.quantity,
                                { v ->
                                    onDrafts(
                                        drafts.toMutableList().also {
                                            it[i] = d.copy(quantity = v)
                                        }
                                    )
                                },
                                label = { Text(if (ar) "الكمية" else "Qté") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )

                            OutlinedTextField(
                                d.unitPrice,
                                { v ->
                                    onDrafts(
                                        drafts.toMutableList().also {
                                            it[i] = d.copy(unitPrice = v)
                                        }
                                    )
                                },
                                label = { Text(if (ar) "سعر الوحدة" else "Prix unit.") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )

                            OutlinedTextField(
                                d.total,
                                { v ->
                                    onDrafts(
                                        drafts.toMutableList().also {
                                            it[i] = d.copy(total = v)
                                        }
                                    )
                                },
                                label = { Text(if (ar) "المجموع" else "Total") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onRescan,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (ar) "إعادة المسح" else "Recommencer")
            }

            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (ar) "حفظ" else "Enregistrer")
            }
        }

        Text(
            if (ar) "ملاحظة: تحقق دائماً من نتائج التعرف الضوئي."
            else "Important : l'OCR peut se tromper. Vérifie chaque ligne avant de sauvegarder.",
            fontSize = 11.sp,
            color = fg.copy(alpha = .6f)
        )
    }
}

@Composable
private fun HistoryV2(
    pad: PaddingValues,
    rows: List<PurchaseEntity>,
    money: Money,
    ar: Boolean,
    fg: Color,
    card: Color,
    onExport: () -> Unit
) {
    val filtered = rows.filter { it.currency == money.name }

    Column(
        Modifier.fillMaxSize().padding(pad).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (ar) "سجل المشتريات" else "Historique des achats",
                Modifier.weight(1f),
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                color = fg
            )

            Button(onClick = onExport) {
                Icon(Icons.Default.FileDownload, null)
                Spacer(Modifier.width(4.dp))
                Text("Excel")
            }
        }

        Spacer(Modifier.height(10.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filtered) {
                PurchaseRowV2(it, money, fg, card)
            }
        }

        if (filtered.isEmpty()) {
            Text(
                if (ar) "لا توجد بيانات للتصدير" else "Aucun achat dans cette devise.",
                color = fg.copy(alpha = .7f)
            )
        }
    }
}

@Composable
private fun PurchaseRowV2(
    p: PurchaseEntity,
    money: Money,
    fg: Color,
    card: Color
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = card),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.ShoppingBag, null, tint = Color(0xFF078A4B))
            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Text(p.product, fontWeight = FontWeight.SemiBold, color = fg)
                Text(
                    "${p.store} • ${p.receiptDate} • Qté ${p.quantity}",
                    fontSize = 11.sp,
                    color = fg.copy(alpha = .65f)
                )
            }

            Text(
                "${"%.2f".format(Locale.FRANCE, p.total)} ${p.currency}",
                fontWeight = FontWeight.Bold,
                color = fg
            )
        }
    }
}

@Composable
private fun StatsV2(
    pad: PaddingValues,
    rows: List<PurchaseEntity>,
    money: Money,
    ar: Boolean,
    fg: Color,
    card: Color
) {
    var period by remember { mutableStateOf("month") }

    val now = java.util.Calendar.getInstance()
    val filteredCurrency = rows.filter { it.currency == money.name }

    val periodRows = filteredCurrency.filter { row ->
        val parsed = runCatching {
            java.text.SimpleDateFormat(
                "yyyy-MM-dd",
                java.util.Locale.US
            ).parse(row.receiptDate)
        }.getOrNull() ?: return@filter false

        val c = java.util.Calendar.getInstance().apply {
            time = parsed
        }

        when (period) {
            "week" ->
                c.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
                c.get(java.util.Calendar.WEEK_OF_YEAR) == now.get(java.util.Calendar.WEEK_OF_YEAR)

            "month" ->
                c.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
                c.get(java.util.Calendar.MONTH) == now.get(java.util.Calendar.MONTH)

            else -> true
        }
    }

    val total = periodRows.sumOf { it.total }

    val byProduct = periodRows
        .groupBy { it.product }
        .mapValues { e -> e.value.sumOf { it.total } }
        .entries
        .sortedByDescending { it.value }
        .take(12)

    Column(
        Modifier.fillMaxSize().padding(pad).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            if (ar) "إحصائيات المصاريف" else "Statistiques des dépenses",
            fontSize = 23.sp,
            fontWeight = FontWeight.Bold,
            color = fg
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = period == "week",
                onClick = { period = "week" },
                label = { Text(if (ar) "هذا الأسبوع" else "Cette semaine") }
            )
            FilterChip(
                selected = period == "month",
                onClick = { period = "month" },
                label = { Text(if (ar) "هذا الشهر" else "Ce mois") }
            )
            FilterChip(
                selected = period == "all",
                onClick = { period = "all" },
                label = { Text(if (ar) "الكل" else "Tout") }
            )
        }

        Card(colors = CardDefaults.cardColors(containerColor = card)) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    if (ar) "إجمالي المصاريف" else "Montant des achats",
                    color = fg.copy(alpha = .7f)
                )
                Text(
                    "${"%.2f".format(Locale.FRANCE, total)} ${money.name}",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF078A4B)
                )
                Text(
                    "${periodRows.sumOf { it.quantity }} ${if (ar) "منتج" else "articles"} • ${periodRows.map { it.receiptId }.distinct().size} tickets",
                    color = fg.copy(alpha = .75f)
                )
            }
        }

        Text(
            if (ar) "المصاريف حسب المنتج" else "Dépenses par produit",
            fontWeight = FontWeight.Bold,
            color = fg
        )

        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(byProduct) { (name, amount) ->
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Row {
                        Text(name, Modifier.weight(1f), color = fg)
                        Text(
                            "${"%.2f".format(Locale.FRANCE, amount)} ${money.name}",
                            color = fg
                        )
                    }

                    LinearProgressIndicator(
                        progress = {
                            if (total > 0) (amount / total).toFloat() else 0f
                        },
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF078A4B)
                    )
                }
            }

            if (periodRows.isEmpty()) {
                item {
                    Text(
                        if (ar) "لا توجد مشتريات لهذه الفترة"
                        else "Aucun achat pour cette période.",
                        color = fg.copy(alpha = .7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsV2(
    pad: PaddingValues,
    dark: Boolean,
    onDark: (Boolean) -> Unit,
    money: Money,
    onMoney: (Money) -> Unit,
    lang: AppLang,
    onLang: (AppLang) -> Unit,
    ar: Boolean,
    fg: Color,
    card: Color
) {
    Column(
        Modifier.fillMaxSize().padding(pad).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            if (ar) "الإعدادات" else "Paramètres",
            fontSize = 23.sp,
            fontWeight = FontWeight.Bold,
            color = fg
        )

        Card(colors = CardDefaults.cardColors(containerColor = card)) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    if (ar) "المظهر" else "Apparence",
                    fontWeight = FontWeight.Bold,
                    color = fg
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (dark) "Mode Dark" else "Mode coloré",
                        Modifier.weight(1f),
                        color = fg
                    )
                    Switch(dark, onDark)
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = card)) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    if (ar) "العملة" else "Monnaie",
                    fontWeight = FontWeight.Bold,
                    color = fg
                )

                Money.values().forEach {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(money == it, { onMoney(it) })
                        Text(it.label, color = fg)
                    }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = card)) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    if (ar) "اللغة" else "Langue",
                    fontWeight = FontWeight.Bold,
                    color = fg
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(lang == AppLang.FR, { onLang(AppLang.FR) })
                    Text("Français", color = fg)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(lang == AppLang.AR, { onLang(AppLang.AR) })
                    Text("العربية", color = fg)
                }
            }
        }

        Text(
            if (ar) "الإصدار 2.0.0" else "Mon Panier • Version 2.0.0",
            color = fg.copy(alpha = .6f),
            fontSize = 12.sp
        )
    }
}
