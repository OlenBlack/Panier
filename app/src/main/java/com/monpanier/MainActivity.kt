package com.monpanier.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*

import androidx.compose.runtime.*

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
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
import kotlinx.coroutines.delay

import java.util.concurrent.Executors
import kotlin.math.abs
import java.util.Locale

private enum class Screen {
    HOME, SCAN, CROP, REVIEW, HISTORY,
    STATS, PRICES, COMPARISON,
    BASKET, SETTINGS, STORES, BUDGET
}

private enum class AppLang { FR, AR }

enum class Money(val label: String) {
    DZD("DZD"),
    CAD("CAD")
}

private data class Draft(
    val product: String,
    val quantity: String,
    val unitPrice: String,
    val total: String
)

/* ACTIVITÉ PRINCIPALE */

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var showSplash by remember {
                mutableStateOf(true)
            }

            LaunchedEffect(Unit) {
                delay(2000)
                showSplash = false
            }

            if (showSplash) {
                SplashScreen()
            } else {
                MonPanierV2()
            }
        }
    }
}

/* ÉCRAN DE DÉMARRAGE */

@Composable
private fun SplashScreen() {
    Image(
        painter = painterResource(
            id = R.drawable.splash_mon_panier
        ),
        contentDescription = "By OlenBlack",
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
    )
}

/* APPLICATION MON PANIER */

@Composable
private fun MonPanierV2() {

    val context = LocalContext.current

    val dao = remember {
        PurchaseDatabase.get(context).purchaseDao()
    }

    val purchases by dao.observeAll()
        .collectAsState(initial = emptyList())

    val stores by dao.observeStores()
        .collectAsState(initial = emptyList())

    val budgets by dao.observeBudgets()
        .collectAsState(initial = emptyList())

    val prefs = remember {
        context.getSharedPreferences(
            "settings",
            Context.MODE_PRIVATE
        )
    }

    var dark by remember {
        mutableStateOf(
            prefs.getBoolean("dark", false)
        )
    }

    var lang by remember {
        mutableStateOf(
            if (prefs.getString("lang", "FR") == "AR")
                AppLang.AR
            else
                AppLang.FR
        )
    }

    var money by remember {
        mutableStateOf(
            if (prefs.getString("money", "DZD") == "CAD")
                Money.CAD
            else
                Money.DZD
        )
    }

    var screen by remember {
        mutableStateOf(Screen.HOME)
    }

    var ocrText by remember { mutableStateOf("") }
    var store by remember { mutableStateOf("") }
    var ticketNumber by remember { mutableStateOf("") }
    var drafts by remember { mutableStateOf(listOf<Draft>()) }
    var notice by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var ocrBusy by remember { mutableStateOf(false) }
    var cropMsg by remember { mutableStateOf("") }

    val snackbar = remember {
        SnackbarHostState()
    }

    LaunchedEffect(notice) {
        if (notice.isNotBlank()) {
            snackbar.showSnackbar(notice)
            notice = ""
        }
    }

    val scope = rememberCoroutineScope()

    val green =
        if (dark) Color(0xFF70D9A0)
        else Color(0xFF078A4B)

    val bg =
        if (dark) Color(0xFF101713)
        else Color(0xFFF4F8F5)

    val card =
        if (dark) Color(0xFF1C2922)
        else Color.White

    val fg =
        if (dark) Color(0xFFEAF4ED)
        else Color(0xFF17221B)

    val isAr = lang == AppLang.AR
    val currencyCode = money.name

    val total = purchases
        .filter { it.currency == currencyCode }
        .sumOf { it.total }

    /* EXPORT EXCEL */

    val createXlsx = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        )
    ) { uri: Uri? ->

        if (uri != null) {
            scope.launch {
                runCatching {
                    context.contentResolver
                        .openOutputStream(uri)?.use {
                            ExcelExporter.export(purchases, it)
                        }
                        ?: error("Impossible d'ouvrir le fichier")
                }.onSuccess {
                    notice = if (isAr)
                        "تم تصدير ملف Excel"
                    else
                        "Fichier Excel exporté"
                }.onFailure {
                    notice = "Erreur export : ${it.message}"
                }
            }
        }
    }

    /* IMPORTATION DEPUIS LA GALERIE */

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->

        if (uri != null) {
            runCatching {
                context.contentResolver
                    .openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                    ?: error("Impossible de lire cette image")
            }.onSuccess { bitmap ->

                if (bitmap != null) {
                    photo = bitmap
                    cropMsg = ""
                    ocrBusy = false
                    screen = Screen.CROP
                } else {
                    notice = "Impossible de lire cette image."
                }

            }.onFailure {
                notice = "Erreur d'importation : ${it.message}"
            }
        }
    }

    /* THÈME DE L'APPLICATION */

    MaterialTheme(
        colorScheme = if (dark) {
            darkColorScheme(
                primary = green,
                background = bg,
                surface = card
            )
        } else {
            lightColorScheme(
                primary = green,
                background = bg,
                surface = card
            )
        }
    ) {

        Scaffold(
            containerColor = bg,

            topBar = {
                TopAppBarV2(
                    isAr,
                    fg,
                    green,
                    { screen = Screen.SETTINGS }
                )
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
                        screen == Screen.COMPARISON,
                        { screen = Screen.COMPARISON },
                        Icons.Default.CompareArrows,
                        if (isAr) "مقارنة" else "Comparer"
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
                SnackbarHost(snackbar)
            }

        ) { pad ->

            when (screen) {

                Screen.HOME -> HomeV2(
                    pad,
                    purchases,
                    money,
                    lang,
                    total,
                    fg,
                    card,
                    { screen = Screen.SCAN },
                    { screen = Screen.HISTORY }
                )

                Screen.SCAN -> ScannerScreen(
                    pad = pad,
                    ar = isAr,
                    fg = fg,
                    green = green,
                    onPhoto = { bmp ->
                        photo = bmp
                        cropMsg = ""
                        ocrBusy = false
                        screen = Screen.CROP
                    },
                    onGallery = {
                        galleryLauncher.launch("image/*")
                    }
                )

                Screen.CROP -> photo?.let { p ->

                    CropScreen(
                        pad,
                        isAr,
                        fg,
                        green,
                        p,
                        ocrBusy,
                        cropMsg,

                        onConfirm = { cropped ->

                            ocrBusy = true
                            cropMsg = ""

                            runOcr(
                                cropped,

                                onDone = { text, words ->

                                    ocrBusy = false
                                    ocrText = text

                                    val columnsResult =
                                        ReceiptParser.parseColumns(words)

                                    val parsedLines =
                                        columnsResult
                                            ?: ReceiptParser.parse(text)

                                    if (columnsResult == null) {
                                        notice = if (isAr)
                                            "لم يتم العثور على الأعمدة: قراءة بسيطة، تحقق من كل سطر"
                                        else
                                            "Colonnes Produit / Qté / P.U non détectées : lecture simple, vérifie chaque ligne."
                                    }

                                    drafts = parsedLines.map {
                                        Draft(
                                            it.product,
                                            fmtQty(it.quantity),
                                            "%.2f".format(
                                                Locale.US,
                                                it.unitPrice
                                            ),
                                            "%.2f".format(
                                                Locale.US,
                                                it.total
                                            )
                                        )
                                    }

                                    if (drafts.isEmpty()) {
                                        drafts = listOf(
                                            Draft("", "1", "", "")
                                        )
                                    }

                                    photo = null
                                    screen = Screen.REVIEW
                                },

                                onFail = { msg ->
                                    ocrBusy = false
                                    cropMsg = msg
                                }
                            )
                        },

                        onRetake = {
                            photo = null
                            screen = Screen.SCAN
                        }
                    )
                }

                Screen.REVIEW -> ReviewScreen(
                    pad = pad,
                    ar = isAr,
                    fg = fg,
                    card = card,
                    stores = stores,

                    onAddStore = { name ->
                        val cleanName = name.trim()

                        if (cleanName.isNotBlank()) {
                            scope.launch {
                                dao.insertStoreIfAbsent(
                                    StoreEntity(name = cleanName)
                                )
                                store = cleanName
                            }
                        }
                    },

                    store = store,
                    onStore = { store = it },
                    ticketNumber = ticketNumber,
                    onTicketNumber = { ticketNumber = it },
                    drafts = drafts,
                    onDrafts = { drafts = it },
                    raw = ocrText,

                    onSave = {
                        val rid = ReceiptParser.receiptId()
                        val number = ticketNumber.trim()
                        val storeName = store.trim().ifBlank {
                            "Magasin"
                        }

                        val rows = drafts.mapNotNull { d ->

                            val q = d.quantity
                                .replace(',', '.')
                                .toDoubleOrNull()

                            val price = d.unitPrice
                                .replace(',', '.')
                                .toDoubleOrNull()

                            val amount = d.total
                                .replace(',', '.')
                                .toDoubleOrNull()

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
                                    store = storeName,
                                    product = d.product,
                                    quantity = q,
                                    unitPrice = price,
                                    total = amount,
                                    currency = currencyCode,
                                    ticketNumber = number,
                                    receiptId = rid
                                )
                            }
                        }

                        scope.launch {
                            if (rows.isNotEmpty()) {

                                dao.insertStoreIfAbsent(
                                    StoreEntity(name = storeName)
                                )

                                dao.insertAll(rows)

                                ticketNumber = ""
                                store = ""

                                notice = if (isAr)
                                    "تم حفظ المشتريات"
                                else
                                    "Achats enregistrés"

                                screen = Screen.HOME

                            } else {
                                notice = if (isAr)
                                    "تحقق من المنتجات والأسعار"
                                else
                                    "Vérifie les produits et les prix."
                            }
                        }
                    },

                    onRescan = {
                        screen = Screen.SCAN
                    }
                )

                Screen.HISTORY -> HistoryV2(
                    pad = pad,
                    rows = purchases,
                    money = money,
                    ar = isAr,
                    fg = fg,
                    card = card,
                    dao = dao,
                    onExport = {
                        createXlsx.launch("MonPanier-achats.xlsx")
                    }
                )

                Screen.STATS -> StatsV2(
                    pad,
                    purchases,
                    money,
                    isAr,
                    fg,
                    card
                )

                Screen.PRICES -> PriceHistoryScreen(
                    pad,
                    purchases,
                    money,
                    isAr,
                    fg,
                    card
                )

                Screen.COMPARISON -> PriceComparisonScreen(
                    purchases = purchases,
                    onBack = {
                        screen = Screen.HOME
                    }
                )

                Screen.BASKET -> SmartBasketScreen(
                    pad,
                    purchases,
                    money,
                    isAr,
                    fg,
                    card
                )

                Screen.SETTINGS -> SettingsV2(
                    pad = pad,
                    dark = dark,
                    onDark = {
                        dark = it
                        prefs.edit()
                            .putBoolean("dark", it)
                            .apply()
                    },
                    money = money,
                    onMoney = {
                        money = it
                        prefs.edit()
                            .putString("money", it.name)
                            .apply()
                    },
                    lang = lang,
                    onLang = {
                        lang = it
                        prefs.edit()
                            .putString("lang", it.name)
                            .apply()
                    },
                    ar = isAr,
                    fg = fg,
                    card = card,
                    onStores = {
                        screen = Screen.STORES
                    },
                    onBudget = {
                        screen = Screen.BUDGET
                    },
                    onComparison = {
                        screen = Screen.COMPARISON
                    }
                )

                Screen.STORES -> StoreDirectoryScreen(
                    pad = pad,
                    stores = stores,
                    fg = fg,
                    card = card,
                    dao = dao,
                    onBack = {
                        screen = Screen.SETTINGS
                    }
                )

                Screen.BUDGET -> BudgetScreen(
                    purchases = purchases,
                    budgets = budgets,
                    dao = dao,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(pad)
                )
            }
        }
    }
}

/* BARRE SUPÉRIEURE */

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
            Text(
                "Mon Panier",
                fontWeight = FontWeight.Bold,
                color = fg
            )
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
                Icon(
                    Icons.Default.Settings,
                    null,
                    tint = fg
                )
            }
        }
    )
}

/* NAVIGATION */

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
        icon = {
            Icon(icon, null)
        },
        label = {
            Text(label)
        }
    )
}

/* ACCUEIL */

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

    val scoped = rows.filter {
        it.currency == money.name
    }

    val sum = scoped.sumOf {
        it.total
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(pad)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {

        item {
            Text(
                if (ar)
                    "مرحباً بك في Mon Panier"
                else
                    "Bienvenue dans Mon Panier",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = fg
            )

            Text(
                if (ar)
                    "تابع مشترياتك ونفقاتك"
                else
                    "Scanne tes tickets et suis tes dépenses.",
                color = fg.copy(alpha = .7f)
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF078A4B)
                ),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Text(
                        if (ar)
                            "إجمالي المشتريات"
                        else
                            "Total des achats",
                        color = Color.White
                    )

                    Text(
                        "${"%.2f".format(Locale.FRANCE, sum)} $symbol",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Text(
                        "${scoped.size} ${
                            if (ar) "منتج مسجل"
                            else "lignes enregistrées"
                        }",
                        color = Color.White
                    )
                }
            }
        }

        item {
            Button(
                onClick = onScan,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Icon(Icons.Default.PhotoCamera, null)
                Spacer(Modifier.width(8.dp))

                Text(
                    if (ar) "مسح إيصال"
                    else "Scanner un ticket"
                )
            }
        }

        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
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
                if (ar) "آخر المشتريات"
                else "Derniers achats",
                fontWeight = FontWeight.Bold,
                fontSize = 19.sp,
                color = fg
            )
        }

        items(scoped.take(5)) {
            PurchaseRowV2(
                it,
                money,
                fg,
                card
            )
        }

        if (rows.isEmpty()) {
            item {
                Text(
                    if (ar)
                        "لا توجد مشتريات بعد. ابدأ بمسح أول إيصال."
                    else
                        "Aucun achat pour le moment. Scanne ton premier ticket.",
                    color = fg.copy(alpha = .65f)
                )
            }
        }

        item {
            TextButton(onClick = onHistory) {
                Text(
                    if (ar) "عرض السجل الكامل"
                    else "Voir tout l'historique"
                )
            }
        }
    }
}

/* PETITES STATISTIQUES DE L'ACCUEIL */

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
        colors = CardDefaults.cardColors(
            containerColor = card
        )
    ) {
        Column(
            Modifier.padding(14.dp)
        ) {
            Text(
                value,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = fg
            )

            Text(
                label,
                color = fg.copy(alpha = .7f)
            )
        }
    }
}

/* SCANNER + IMPORTATION DEPUIS LA GALERIE */

@Composable
private fun ScannerScreen(
    pad: PaddingValues,
    ar: Boolean,
    fg: Color,
    green: Color,
    onPhoto: (Bitmap) -> Unit,
    onGallery: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val executor = remember {
        Executors.newSingleThreadExecutor()
    }

    var capture by remember {
        mutableStateOf<ImageCapture?>(null)
    }

    var message by remember {
        mutableStateOf("")
    }

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
        Modifier
            .fillMaxSize()
            .padding(pad)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        Text(
            if (ar) "امسح إيصال الشراء"
            else "Scanner un ticket",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = fg
        )

        Button(
            onClick = onGallery,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
        ) {
            Icon(Icons.Default.Image, null)
            Spacer(Modifier.width(8.dp))

            Text(
                if (ar)
                    "اختيار إيصال من الهاتف"
                else
                    "Importer un ticket depuis le téléphone"
            )
        }

        Text(
            if (ar)
                "يمكنك اختيار صورة محفوظة مسبقاً في هاتفك."
            else
                "Sélectionne une image de ticket déjà enregistrée dans ton téléphone.",
            color = fg.copy(alpha = .7f)
        )

        HorizontalDivider()

        if (!permission) {

            Text(
                if (ar)
                    "يلزم السماح باستعمال الكاميرا"
                else
                    "L'accès à la caméra est nécessaire pour scanner un nouveau ticket.",
                color = fg
            )

            Button(
                onClick = {
                    request.launch(Manifest.permission.CAMERA)
                }
            ) {
                Text(
                    if (ar) "السماح"
                    else "Autoriser la caméra"
                )
            }

        } else {

            AndroidView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),

                factory = { ctx ->

                    val previewView = PreviewView(ctx)

                    val providerFuture =
                        ProcessCameraProvider.getInstance(ctx)

                    providerFuture.addListener({

                        val provider = providerFuture.get()

                        val preview = Preview.Builder()
                            .build()
                            .also {
                                it.setSurfaceProvider(
                                    previewView.surfaceProvider
                                )
                            }

                        val imageCapture = ImageCapture.Builder()
                            .setCaptureMode(
                                ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                            )
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
                            message = e.localizedMessage
                                ?: "Erreur caméra"
                        }

                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                }
            )

            Button(
                onClick = {

                    val cap = capture ?: return@Button

                    message = if (ar)
                        "جارٍ التقاط الصورة…"
                    else
                        "Capture en cours…"

                    cap.takePicture(
                        executor,

                        object : ImageCapture.OnImageCapturedCallback() {

                            override fun onCaptureSuccess(
                                image: ImageProxy
                            ) {
                                val bmp = try {
                                    imageToBitmap(image)
                                } catch (e: Throwable) {
                                    null
                                }

                                image.close()

                                ContextCompat
                                    .getMainExecutor(context)
                                    .execute {

                                        if (bmp != null) {
                                            onPhoto(bmp)
                                        } else {
                                            message = "Image invalide"
                                        }
                                    }
                            }

                            override fun onError(
                                exception: ImageCaptureException
                            ) {
                                ContextCompat
                                    .getMainExecutor(context)
                                    .execute {
                                        message =
                                            exception.localizedMessage
                                                ?: "Erreur de capture"
                                    }
                            }
                        }
                    )
                },

                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)

            ) {
                Icon(Icons.Default.CameraAlt, null)
                Spacer(Modifier.width(8.dp))

                Text(
                    if (ar) "التقاط الصورة"
                    else "Prendre la photo"
                )
            }

            Text(
                if (ar)
                    "ضع الإيصال داخل الإطار مع إضاءة جيدة."
                else
                    "Place le ticket bien à plat, dans un endroit éclairé.",
                color = fg.copy(alpha = .7f)
            )

            if (message.isNotBlank()) {
                Text(
                    message,
                    color = green
                )
            }
        }
    }
}

/* FORMATAGE DES QUANTITÉS */

private fun fmtQty(q: Double): String =
    if (q % 1.0 == 0.0)
        q.toLong().toString()
    else
        q.toString()

/* CONVERSION DE LA PHOTO */

private fun imageToBitmap(
    image: ImageProxy
): Bitmap {

    var bmp = image.toBitmap()

    val rotation = image.imageInfo.rotationDegrees

    if (rotation != 0) {
        val m = Matrix()
        m.postRotate(rotation.toFloat())

        bmp = Bitmap.createBitmap(
            bmp,
            0,
            0,
            bmp.width,
            bmp.height,
            m,
            true
        )
    }

    val longSide = maxOf(
        bmp.width,
        bmp.height
    )

    if (longSide > 2600) {
        val s = 2600f / longSide

        bmp = Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * s).toInt(),
            (bmp.height * s).toInt(),
            true
        )
    }

    return bmp
}

/* RECONNAISSANCE OCR */

private fun runOcr(
    bitmap: Bitmap,
    onDone: (String, List<OcrWord>) -> Unit,
    onFail: (String) -> Unit
) {
    val input = InputImage.fromBitmap(bitmap, 0)

    TextRecognition
        .getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        .process(input)

        .addOnSuccessListener { result ->

            val words = ArrayList<OcrWord>()

            for (block in result.textBlocks) {
                for (line in block.lines) {
                    for (el in line.elements) {

                        val b = el.boundingBox ?: continue

                        words.add(
                            OcrWord(
                                el.text,
                                b.left.toFloat(),
                                b.top.toFloat(),
                                b.right.toFloat(),
                                b.bottom.toFloat()
                            )
                        )
                    }
                }
            }

            onDone(
                result.text,
                words
            )
        }

        .addOnFailureListener { e ->
            onFail(
                "OCR : " +
                    (e.localizedMessage ?: "erreur")
            )
        }
}

/* ÉCRAN DE CADRAGE */

@Composable
private fun CropScreen(
    pad: PaddingValues,
    ar: Boolean,
    fg: Color,
    green: Color,
    bitmap: Bitmap,
    busy: Boolean,
    message: String,
    onConfirm: (Bitmap) -> Unit,
    onRetake: () -> Unit
) {
    val density = LocalDensity.current
    val handle = with(density) { 40.dp.toPx() }
    val minSize = with(density) { 60.dp.toPx() }

    var viewW by remember { mutableStateOf(0f) }
    var viewH by remember { mutableStateOf(0f) }

    var cl by remember { mutableStateOf(0f) }
    var ct by remember { mutableStateOf(0f) }
    var cr by remember { mutableStateOf(0f) }
    var cb by remember { mutableStateOf(0f) }

    var active by remember { mutableStateOf(0) }

    val scale =
        if (viewW > 0f && viewH > 0f)
            minOf(viewW / bitmap.width, viewH / bitmap.height)
        else 1f

    val imgW = bitmap.width * scale
    val imgH = bitmap.height * scale
    val offX = (viewW - imgW) / 2f
    val offY = (viewH - imgH) / 2f

    LaunchedEffect(viewW, viewH) {
        if (viewW > 0f && viewH > 0f) {
            cl = offX + imgW * 0.03f
            cr = offX + imgW * 0.97f
            ct = offY + imgH * 0.03f
            cb = offY + imgH * 0.97f
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(pad)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            if (ar) "اضبط إطار الإيصال"
            else "Cadrer le ticket",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = fg
        )

        Text(
            if (ar) "حرّك الزوايا لتحيط بجدول المنتجات فقط."
            else "Glisse les coins pour garder le tableau des produits.",
            color = fg.copy(alpha = .7f)
        )

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged {
                    viewW = it.width.toFloat()
                    viewH = it.height.toFloat()
                }
                .pointerInput(viewW, viewH) {
                    detectDragGestures(
                        onDragStart = { p ->
                            active = when {
                                abs(p.x - cl) < handle &&
                                    abs(p.y - ct) < handle -> 1

                                abs(p.x - cr) < handle &&
                                    abs(p.y - ct) < handle -> 2

                                abs(p.x - cl) < handle &&
                                    abs(p.y - cb) < handle -> 3

                                abs(p.x - cr) < handle &&
                                    abs(p.y - cb) < handle -> 4

                                p.x >= cl && p.x <= cr &&
                                    p.y >= ct && p.y <= cb -> 5

                                else -> 0
                            }
                        },
                        onDragEnd = { active = 0 },
                        onDragCancel = { active = 0 },
                        onDrag = { change, d ->
                            change.consume()

                            val minX = offX
                            val maxX = offX + imgW
                            val minY = offY
                            val maxY = offY + imgH

                            when (active) {
                                1 -> {
                                    cl = (cl + d.x)
                                        .coerceIn(minX, cr - minSize)

                                    ct = (ct + d.y)
                                        .coerceIn(minY, cb - minSize)
                                }

                                2 -> {
                                    cr = (cr + d.x)
                                        .coerceIn(cl + minSize, maxX)

                                    ct = (ct + d.y)
                                        .coerceIn(minY, cb - minSize)
                                }

                                3 -> {
                                    cl = (cl + d.x)
                                        .coerceIn(minX, cr - minSize)

                                    cb = (cb + d.y)
                                        .coerceIn(ct + minSize, maxY)
                                }

                                4 -> {
                                    cr = (cr + d.x)
                                        .coerceIn(cl + minSize, maxX)

                                    cb = (cb + d.y)
                                        .coerceIn(ct + minSize, maxY)
                                }

                                5 -> {
                                    val w = cr - cl
                                    val h = cb - ct

                                    val nl = (cl + d.x)
                                        .coerceIn(minX, maxX - w)

                                    val nt = (ct + d.y)
                                        .coerceIn(minY, maxY - h)

                                    cl = nl
                                    ct = nt
                                    cr = nl + w
                                    cb = nt + h
                                }
                            }
                        }
                    )
                }
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )

            Canvas(Modifier.fillMaxSize()) {
                val dim = Color.Black.copy(alpha = 0.55f)

                drawRect(
                    dim,
                    Offset(0f, 0f),
                    Size(size.width, ct)
                )

                drawRect(
                    dim,
                    Offset(0f, cb),
                    Size(size.width, size.height - cb)
                )

                drawRect(
                    dim,
                    Offset(0f, ct),
                    Size(cl, cb - ct)
                )

                drawRect(
                    dim,
                    Offset(cr, ct),
                    Size(size.width - cr, cb - ct)
                )

                drawRect(
                    green,
                    Offset(cl, ct),
                    Size(cr - cl, cb - ct),
                    style = Stroke(width = 3f)
                )

                drawCircle(green, 16f, Offset(cl, ct))
                drawCircle(green, 16f, Offset(cr, ct))
                drawCircle(green, 16f, Offset(cl, cb))
                drawCircle(green, 16f, Offset(cr, cb))
            }
        }

        if (message.isNotBlank()) {
            Text(message, color = Color(0xFFD32F2F))
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onRetake,
                enabled = !busy,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (ar) "إعادة التصوير" else "Reprendre")
            }

            Button(
                onClick = {
                    val x = ((cl - offX) / scale)
                        .toInt()
                        .coerceIn(0, bitmap.width - 1)

                    val y = ((ct - offY) / scale)
                        .toInt()
                        .coerceIn(0, bitmap.height - 1)

                    val w = ((cr - cl) / scale)
                        .toInt()
                        .coerceIn(1, bitmap.width - x)

                    val h = ((cb - ct) / scale)
                        .toInt()
                        .coerceIn(1, bitmap.height - y)

                    onConfirm(
                        Bitmap.createBitmap(
                            bitmap,
                            x,
                            y,
                            w,
                            h
                        )
                    )
                },
                enabled = !busy,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    if (busy)
                        (if (ar) "جارٍ القراءة…" else "Lecture…")
                    else
                        (if (ar) "قراءة الإيصال" else "Lire le ticket")
                )
            }
        }
    }
}

/* ÉCRAN DE VÉRIFICATION OCR */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewScreen(
    pad: PaddingValues,
    ar: Boolean,
    fg: Color,
    card: Color,
    stores: List<StoreEntity>,
    onAddStore: (String) -> Unit,
    store: String,
    onStore: (String) -> Unit,
    ticketNumber: String,
    onTicketNumber: (String) -> Unit,
    drafts: List<Draft>,
    onDrafts: (List<Draft>) -> Unit,
    raw: String,
    onSave: () -> Unit,
    onRescan: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showAddStore by remember { mutableStateOf(false) }
    var newStoreName by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .padding(pad)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            if (ar) "تحقق من المنتجات"
            else "Vérifier les articles",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = fg
        )

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = {
                expanded = !expanded
            }
        ) {
            OutlinedTextField(
                value = store,
                onValueChange = onStore,
                label = {
                    Text(if (ar) "المتجر" else "Magasin")
                },
                placeholder = {
                    Text(if (ar) "اختر متجراً" else "Choisir un magasin")
                },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                singleLine = true
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = {
                    expanded = false
                }
            ) {
                stores.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.name) },
                        onClick = {
                            onStore(item.name)
                            expanded = false
                        }
                    )
                }

                if (stores.isEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (ar) "لا توجد متاجر مسجلة"
                                else "Aucun magasin enregistré"
                            )
                        },
                        onClick = {
                            expanded = false
                        },
                        enabled = false
                    )
                }
            }
        }

        OutlinedButton(
            onClick = {
                newStoreName = ""
                showAddStore = true
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(8.dp))

            Text(
                if (ar) "إضافة متجر جديد"
                else "Ajouter un nouveau magasin"
            )
        }

        OutlinedTextField(
            value = ticketNumber,
            onValueChange = onTicketNumber,
            label = {
                Text(if (ar) "رقم الإيصال" else "Numéro du ticket")
            },
            placeholder = {
                Text("Ex. : TCK-2026-00125")
            },
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

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = card
                    )
                ) {
                    Column(Modifier.padding(10.dp)) {

                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = d.product,
                                onValueChange = { v ->
                                    onDrafts(
                                        drafts.toMutableList().also {
                                            it[i] = d.copy(product = v)
                                        }
                                    )
                                },
                                label = {
                                    Text(if (ar) "المنتج" else "Produit")
                                },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )

                            IconButton(
                                onClick = {
                                    onDrafts(
                                        drafts.toMutableList().also {
                                            it.removeAt(i)
                                        }
                                    )
                                }
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Supprimer ce produit",
                                    tint = Color(0xFFD32F2F)
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedTextField(
                                value = d.quantity,
                                onValueChange = { v ->
                                    onDrafts(
                                        drafts.toMutableList().also {
                                            it[i] = d.copy(quantity = v)
                                        }
                                    )
                                },
                                label = {
                                    Text(if (ar) "الكمية" else "Qté")
                                },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = d.unitPrice,
                                onValueChange = { v ->
                                    onDrafts(
                                        drafts.toMutableList().also {
                                            it[i] = d.copy(unitPrice = v)
                                        }
                                    )
                                },
                                label = {
                                    Text(if (ar) "سعر الوحدة" else "Prix unit.")
                                },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = d.total,
                                onValueChange = { v ->
                                    onDrafts(
                                        drafts.toMutableList().also {
                                            it[i] = d.copy(total = v)
                                        }
                                    )
                                },
                                label = {
                                    Text(if (ar) "المجموع" else "Total")
                                },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                    }
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
            if (ar)
                "ملاحظة: تحقق دائماً من نتائج التعرف الضوئي."
            else
                "Important : l'OCR peut se tromper. Vérifie chaque ligne avant de sauvegarder.",
            fontSize = 11.sp,
            color = fg.copy(alpha = .6f)
        )
    }

    if (showAddStore) {
        AlertDialog(
            onDismissRequest = {
                showAddStore = false
            },
            title = {
                Text(if (ar) "إضافة متجر" else "Ajouter un magasin")
            },
            text = {
                OutlinedTextField(
                    value = newStoreName,
                    onValueChange = {
                        newStoreName = it
                    },
                    label = {
                        Text(if (ar) "اسم المتجر" else "Nom du magasin")
                    },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newStoreName.trim()

                        if (name.isNotBlank()) {
                            onAddStore(name)
                            showAddStore = false
                        }
                    }
                ) {
                    Text(if (ar) "حفظ" else "Ajouter")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAddStore = false
                    }
                ) {
                    Text(if (ar) "إلغاء" else "Annuler")
                }
            }
        )
    }
}

/* HISTORIQUE DES ACHATS */

@Composable
private fun HistoryV2(
    pad: PaddingValues,
    rows: List<PurchaseEntity>,
    money: Money,
    ar: Boolean,
    fg: Color,
    card: Color,
    dao: PurchaseDao,
    onExport: () -> Unit
) {
    var search by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    val query = search.trim()

    val filtered = rows.filter {
        it.currency == money.name &&
            (
                query.isBlank() ||
                    it.ticketNumber.contains(query, ignoreCase = true) ||
                    it.product.contains(query, ignoreCase = true) ||
                    it.store.contains(query, ignoreCase = true)
            )
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(pad)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (ar) "سجل المشتريات"
                else "Historique des achats",
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

        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = {
                Text(
                    if (ar)
                        "البحث برقم الإيصال أو المنتج أو المتجر"
                    else
                        "Rechercher un ticket, produit ou magasin"
                )
            },
            leadingIcon = {
                Icon(Icons.Default.Search, null)
            },
            trailingIcon = {
                if (search.isNotEmpty()) {
                    IconButton(onClick = { search = "" }) {
                        Icon(Icons.Default.Clear, null)
                    }
                }
            }
        )

        Spacer(Modifier.height(10.dp))

        if (message.isNotBlank()) {
            Text(
                message,
                color = Color(0xFF078A4B),
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filtered, key = { it.id }) { purchase ->
                PurchaseManagementRow(
                    purchase = purchase,
                    fg = fg,
                    card = card,

                    onUpdate = { updated ->
                        scope.launch {
                            runCatching {
                                dao.update(updated)
                            }.onSuccess {
                                message = if (ar)
                                    "تم تعديل المشتريات"
                                else
                                    "Achat modifié avec succès"
                            }.onFailure {
                                message = "Erreur : ${it.message}"
                            }
                        }
                    },

                    onDeletePurchase = {
                        scope.launch {
                            runCatching {
                                dao.deletePurchase(purchase.id)
                            }.onSuccess {
                                message = if (ar)
                                    "تم حذف المنتج"
                                else
                                    "Produit supprimé"
                            }.onFailure {
                                message = "Erreur : ${it.message}"
                            }
                        }
                    },

                    onDeleteReceipt = {
                        scope.launch {
                            runCatching {
                                dao.deleteReceipt(purchase.receiptId)
                            }.onSuccess {
                                message = if (ar)
                                    "تم حذف الإيصال بالكامل"
                                else
                                    "Ticket supprimé intégralement"
                            }.onFailure {
                                message = "Erreur : ${it.message}"
                            }
                        }
                    }
                )
            }
        }

        if (filtered.isEmpty()) {
            Text(
                if (ar) "لا توجد نتائج"
                else "Aucun achat trouvé.",
                color = fg.copy(alpha = .7f)
            )
        }
    }
}

/* GESTION D'UN ACHAT */

@Composable
private fun PurchaseManagementRow(
    purchase: PurchaseEntity,
    fg: Color,
    card: Color,
    onUpdate: (PurchaseEntity) -> Unit,
    onDeletePurchase: () -> Unit,
    onDeleteReceipt: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var editDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var deleteReceiptDialog by remember { mutableStateOf(false) }

    var product by remember(purchase.id) {
        mutableStateOf(purchase.product)
    }

    var quantity by remember(purchase.id) {
        mutableStateOf(purchase.quantity.toString())
    }

    var unitPrice by remember(purchase.id) {
        mutableStateOf(purchase.unitPrice.toString())
    }

    var total by remember(purchase.id) {
        mutableStateOf(purchase.total.toString())
    }

    var error by remember { mutableStateOf("") }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = card
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.ShoppingBag,
                null,
                tint = Color(0xFF078A4B)
            )

            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    purchase.product,
                    fontWeight = FontWeight.SemiBold,
                    color = fg
                )

                Text(
                    "${purchase.store} • ${purchase.receiptDate}",
                    fontSize = 11.sp,
                    color = fg.copy(alpha = .7f)
                )

                Text(
                    "Qté : ${purchase.quantity}",
                    fontSize = 11.sp,
                    color = fg.copy(alpha = .7f)
                )

                if (purchase.ticketNumber.isNotBlank()) {
                    Text(
                        "Ticket N° : ${purchase.ticketNumber}",
                        fontSize = 12.sp,
                        color = Color(0xFF078A4B)
                    )
                }

                Text(
                    "${"%.2f".format(Locale.FRANCE, purchase.total)} ${purchase.currency}",
                    fontWeight = FontWeight.Bold,
                    color = fg
                )
            }

            Box {
                IconButton(
                    onClick = {
                        menuExpanded = true
                    }
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = fg
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = {
                        menuExpanded = false
                    }
                ) {
                    DropdownMenuItem(
                        text = {
                            Text("Modifier cet achat")
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Edit, null)
                        },
                        onClick = {
                            menuExpanded = false
                            editDialog = true
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Text("Supprimer ce produit")
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Delete, null)
                        },
                        onClick = {
                            menuExpanded = false
                            deleteDialog = true
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Text("Supprimer tout le ticket")
                        },
                        leadingIcon = {
                            Icon(Icons.Default.DeleteForever, null)
                        },
                        onClick = {
                            menuExpanded = false
                            deleteReceiptDialog = true
                        }
                    )
                }
            }
        }
    }

    if (editDialog) {
        AlertDialog(
            onDismissRequest = {
                editDialog = false
            },
            title = {
                Text("Modifier l'achat")
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = product,
                        onValueChange = { product = it },
                        label = { Text("Produit") },
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = quantity,
                        onValueChange = { quantity = it },
                        label = { Text("Quantité") },
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = unitPrice,
                        onValueChange = { unitPrice = it },
                        label = { Text("Prix unitaire") },
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = total,
                        onValueChange = { total = it },
                        label = { Text("Total") },
                        singleLine = true
                    )

                    if (error.isNotBlank()) {
                        Text(error, color = Color.Red)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val q = quantity
                            .replace(',', '.')
                            .toDoubleOrNull()

                        val price = unitPrice
                            .replace(',', '.')
                            .toDoubleOrNull()

                        val amount = total
                            .replace(',', '.')
                            .toDoubleOrNull()

                        if (
                            product.isBlank() ||
                            q == null || q <= 0 ||
                            price == null || price < 0 ||
                            amount == null || amount < 0
                        ) {
                            error = "Vérifie les informations saisies."
                        } else {
                            onUpdate(
                                purchase.copy(
                                    product = product.trim(),
                                    quantity = q,
                                    unitPrice = price,
                                    total = amount
                                )
                            )

                            editDialog = false
                        }
                    }
                ) {
                    Text("Enregistrer")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        editDialog = false
                    }
                ) {
                    Text("Annuler")
                }
            }
        )
    }

    if (deleteDialog) {
        AlertDialog(
            onDismissRequest = {
                deleteDialog = false
            },
            title = {
                Text("Supprimer ce produit ?")
            },
            text = {
                Text(
                    "Seul cet achat sera supprimé. Les autres produits du ticket seront conservés."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeletePurchase()
                        deleteDialog = false
                    }
                ) {
                    Text(
                        "Supprimer",
                        color = Color.Red
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        deleteDialog = false
                    }
                ) {
                    Text("Annuler")
                }
            }
        )
    }

    if (deleteReceiptDialog) {
        AlertDialog(
            onDismissRequest = {
                deleteReceiptDialog = false
            },
            title = {
                Text("Supprimer tout le ticket ?")
            },
            text = {
                Text(
                    "Tous les produits enregistrés avec ce ticket seront supprimés définitivement."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteReceipt()
                        deleteReceiptDialog = false
                    }
                ) {
                    Text(
                        "Supprimer le ticket",
                        color = Color.Red
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        deleteReceiptDialog = false
                    }
                ) {
                    Text("Annuler")
                }
            }
        )
    }
}

/* AFFICHAGE SIMPLE DES ACHATS */

@Composable
private fun PurchaseRowV2(
    p: PurchaseEntity,
    money: Money,
    fg: Color,
    card: Color
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = card
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.ShoppingBag,
                null,
                tint = Color(0xFF078A4B)
            )

            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    p.product,
                    fontWeight = FontWeight.SemiBold,
                    color = fg
                )

                Text(
                    "${p.store} • ${p.receiptDate} • Qté ${p.quantity}",
                    fontSize = 11.sp,
                    color = fg.copy(alpha = .65f)
                )

                if (p.ticketNumber.isNotBlank()) {
                    Text(
                        "Ticket N° : ${p.ticketNumber}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF078A4B)
                    )
                }
            }

            Text(
                "${"%.2f".format(Locale.FRANCE, p.total)} ${p.currency}",
                fontWeight = FontWeight.Bold,
                color = fg
            )
        }
    }
}

/* STATISTIQUES */

@Composable
private fun StatsV2(
    pad: PaddingValues,
    rows: List<PurchaseEntity>,
    money: Money,
    ar: Boolean,
    fg: Color,
    card: Color
) {
    var period by remember {
        mutableStateOf("month")
    }

    val now = java.util.Calendar.getInstance()

    val filteredCurrency = rows.filter {
        it.currency == money.name
    }

    val periodRows = filteredCurrency.filter { row ->

        val parsed = runCatching {
            java.text.SimpleDateFormat(
                "yyyy-MM-dd",
                Locale.US
            ).parse(row.receiptDate)
        }.getOrNull() ?: return@filter false

        val c = java.util.Calendar.getInstance().apply {
            time = parsed
        }

        when (period) {
            "week" ->
                c.get(java.util.Calendar.YEAR) ==
                    now.get(java.util.Calendar.YEAR) &&
                    c.get(java.util.Calendar.WEEK_OF_YEAR) ==
                    now.get(java.util.Calendar.WEEK_OF_YEAR)

            "month" ->
                c.get(java.util.Calendar.YEAR) ==
                    now.get(java.util.Calendar.YEAR) &&
                    c.get(java.util.Calendar.MONTH) ==
                    now.get(java.util.Calendar.MONTH)

            else -> true
        }
    }

    val total = periodRows.sumOf {
        it.total
    }

    val byProduct = periodRows
        .groupBy { it.product }
        .mapValues { e ->
            e.value.sumOf { it.total }
        }
        .entries
        .sortedByDescending { it.value }
        .take(12)

    Column(
        Modifier
            .fillMaxSize()
            .padding(pad)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            if (ar) "إحصائيات المصاريف"
            else "Statistiques des dépenses",
            fontSize = 23.sp,
            fontWeight = FontWeight.Bold,
            color = fg
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = period == "week",
                onClick = {
                    period = "week"
                },
                label = {
                    Text(if (ar) "هذا الأسبوع" else "Cette semaine")
                }
            )

            FilterChip(
                selected = period == "month",
                onClick = {
                    period = "month"
                },
                label = {
                    Text(if (ar) "هذا الشهر" else "Ce mois")
                }
            )

            FilterChip(
                selected = period == "all",
                onClick = {
                    period = "all"
                },
                label = {
                    Text(if (ar) "الكل" else "Tout")
                }
            )
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = card
            )
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    if (ar) "إجمالي المصاريف"
                    else "Montant des achats",
                    color = fg.copy(alpha = .7f)
                )

                Text(
                    "${"%.2f".format(Locale.FRANCE, total)} ${money.name}",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF078A4B)
                )

                Text(
                    "${periodRows.sumOf { it.quantity }} " +
                        "${if (ar) "منتج" else "articles"} • " +
                        "${periodRows.map { it.receiptId }.distinct().size} tickets",
                    color = fg.copy(alpha = .75f)
                )
            }
        }

        Text(
            if (ar) "المصاريف حسب المنتج"
            else "Dépenses par produit",
            fontWeight = FontWeight.Bold,
            color = fg
        )

        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(byProduct) { (name, amount) ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row {
                        Text(
                            name,
                            Modifier.weight(1f),
                            color = fg
                        )

                        Text(
                            "${"%.2f".format(Locale.FRANCE, amount)} ${money.name}",
                            color = fg
                        )
                    }

                    LinearProgressIndicator(
                        progress = {
                            if (total > 0)
                                (amount / total).toFloat()
                            else
                                0f
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

/* PARAMÈTRES */

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
    card: Color,
    onStores: () -> Unit,
    onBudget: () -> Unit,
    onComparison: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(pad)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            if (ar) "الإعدادات" else "Paramètres",
            fontSize = 23.sp,
            fontWeight = FontWeight.Bold,
            color = fg
        )

        /* GESTION DES MAGASINS */

        Card(
            colors = CardDefaults.cardColors(
                containerColor = card
            )
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    if (ar) "إدارة المتاجر"
                    else "Gestion des magasins",
                    fontWeight = FontWeight.Bold,
                    color = fg
                )

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = onStores,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Store, null)
                    Spacer(Modifier.width(8.dp))

                    Text(
                        if (ar) "دليل المتاجر"
                        else "Répertoire des magasins"
                    )
                }
            }
        }

        /* BUDGET MENSUEL */

        Card(
            colors = CardDefaults.cardColors(
                containerColor = card
            )
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    if (ar) "الميزانية الشهرية"
                    else "Budget mensuel",
                    fontWeight = FontWeight.Bold,
                    color = fg
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    if (ar)
                        "حدد ميزانيتك وتابع مصاريفك الشهرية"
                    else
                        "Définis ton budget et suis tes dépenses chaque mois.",
                    color = fg.copy(alpha = 0.7f)
                )

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = onBudget,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.AccountBalanceWallet,
                        null
                    )

                    Spacer(Modifier.width(8.dp))

                    Text(
                        if (ar) "إدارة الميزانية"
                        else "Gérer mon budget"
                    )
                }
            }
        }

        /* COMPARATEUR DE PRIX */

        Card(
            colors = CardDefaults.cardColors(
                containerColor = card
            )
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    if (ar) "مقارنة الأسعار"
                    else "Comparateur de prix",
                    fontWeight = FontWeight.Bold,
                    color = fg
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    if (ar)
                        "قارن أسعار المنتجات بين المتاجر المختلفة"
                    else
                        "Compare les prix d'un même produit entre les différents magasins.",
                    color = fg.copy(alpha = 0.7f)
                )

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = onComparison,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.CompareArrows,
                        null
                    )

                    Spacer(Modifier.width(8.dp))

                    Text(
                        if (ar) "مقارنة الأسعار"
                        else "Ouvrir le comparateur"
                    )
                }
            }
        }

        /* APPARENCE */

        Card(
            colors = CardDefaults.cardColors(
                containerColor = card
            )
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    if (ar) "المظهر" else "Apparence",
                    fontWeight = FontWeight.Bold,
                    color = fg
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (dark) "Mode Dark" else "Mode coloré",
                        Modifier.weight(1f),
                        color = fg
                    )

                    Switch(
                        dark,
                        onDark
                    )
                }
            }
        }

        /* DEVISE */

        Card(
            colors = CardDefaults.cardColors(
                containerColor = card
            )
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    if (ar) "العملة" else "Monnaie",
                    fontWeight = FontWeight.Bold,
                    color = fg
                )

                Money.values().forEach {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            money == it,
                            {
                                onMoney(it)
                            }
                        )

                        Text(
                            it.label,
                            color = fg
                        )
                    }
                }
            }
        }

        /* LANGUE */

        Card(
            colors = CardDefaults.cardColors(
                containerColor = card
            )
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    if (ar) "اللغة" else "Langue",
                    fontWeight = FontWeight.Bold,
                    color = fg
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        lang == AppLang.FR,
                        {
                            onLang(AppLang.FR)
                        }
                    )

                    Text(
                        "Français",
                        color = fg
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        lang == AppLang.AR,
                        {
                            onLang(AppLang.AR)
                        }
                    )

                    Text(
                        "العربية",
                        color = fg
                    )
                }
            }
        }

        Text(
            if (ar) "الإصدار 2.0.0"
            else "Mon Panier • Version 2.0.0",
            color = fg.copy(alpha = .6f),
            fontSize = 12.sp
        )
    }
}
