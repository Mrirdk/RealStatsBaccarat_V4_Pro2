package com.stats.baccaratoverlay

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

class AutoRobotOverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var robotView: View
    private lateinit var roadScannerView: View
    private lateinit var robotParams: WindowManager.LayoutParams
    private lateinit var roadScannerParams: WindowManager.LayoutParams

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val handler = Handler(Looper.getMainLooper())

    private val history = mutableListOf<String>()
    private var lastPredictedSide: String = "WAIT"
    private var toolWins = 0
    private var toolLosses = 0
    private var lastSourceTag = "Bảng Cầu"

    private var isAutoScanning = true
    private var isRoadLocked = false
    private var gridCols = 12
    private var isOcrBusy = false

    private var lastOcrCooldownMs = 0L
    private var stableGridFrames = 0
    private var pendingGridSequence: List<String>? = null

    private val scanLoopRunnable = object : Runnable {
        override fun run() {
            if (isAutoScanning) {
                runVisionPipeline()
            }
            handler.postDelayed(this, 850L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAndroid14Foreground()
        setupOverlayViews()
        initMediaProjection(resultCode, resultData)

        handler.removeCallbacks(scanLoopRunnable)
        handler.postDelayed(scanLoopRunnable, 900L)

        return START_NOT_STICKY
    }

    private fun startAndroid14Foreground() {
        val channelId = "autorobot_v4_pro_channel"
        val channel = NotificationChannel(
            channelId,
            "AutoRobot Baccarat V4 Pro",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("🤖 AutoRobot V4 Pro đang cào bảng cầu")
            .setContentText("Đọc trực tiếp chuỗi Con/Cái/Hòa từ Bảng Cầu & Banner Thắng")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                4004,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(4004, notification)
        }
    }

    private fun initMediaProjection(resultCode: Int, resultData: Intent) {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpManager.getMediaProjection(resultCode, resultData)
        mediaProjection = projection

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { stopSelf() }
            }
        }, handler)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "AutoRobotV4ProCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            handler
        )
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun setupOverlayViews() {
        if (::windowManager.isInitialized) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        robotView = LayoutInflater.from(this).inflate(R.layout.robot_overlay_layout, null)
        robotParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 95
        }
        windowManager.addView(robotView, robotParams)

        roadScannerView = LayoutInflater.from(this).inflate(R.layout.road_scanner_layout, null)
        roadScannerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels - 520).coerceAtLeast(16) / 2
            y = (resources.displayMetrics.heightPixels * 0.66).toInt()
        }
        windowManager.addView(roadScannerView, roadScannerParams)

        attachDragAndTapToRobot()
        attachDragAndResizeToRoadScanner()
        bindButtons()
        refreshHudDisplay()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragAndTapToRobot() {
        val robotImg = robotView.findViewById<ImageView>(R.id.ivRobotCutout)
        val drawer = robotView.findViewById<LinearLayout>(R.id.detailDrawerPanel)
        val toggleArrow = robotView.findViewById<TextView>(R.id.btnToggleDrawer)

        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f
        var isDragging = false

        robotImg.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = robotParams.x
                    initY = robotParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) {
                        isDragging = true
                        robotParams.x = initX + dx
                        robotParams.y = initY + dy
                        windowManager.updateViewLayout(robotView, robotParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        if (drawer.visibility == View.VISIBLE) {
                            drawer.visibility = View.GONE
                            toggleArrow.text = "▼"
                        } else {
                            drawer.visibility = View.VISIBLE
                            toggleArrow.text = "▲"
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragAndResizeToRoadScanner() {
        val gridFrame = roadScannerView.findViewById<FrameLayout>(R.id.roadGridFrame)
        val resizeHandle = roadScannerView.findViewById<TextView>(R.id.btnResizeHandle)

        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f

        gridFrame.setOnTouchListener { _, event ->
            if (isRoadLocked) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = roadScannerParams.x
                    initY = roadScannerParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    roadScannerParams.x = initX + (event.rawX - touchX).toInt()
                    roadScannerParams.y = initY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(roadScannerView, roadScannerParams)
                    true
                }
                else -> false
            }
        }

        var startW = 0
        var startH = 0
        var rTouchX = 0f
        var rTouchY = 0f

        resizeHandle.setOnTouchListener { _, event ->
            if (isRoadLocked) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startW = gridFrame.width
                    startH = gridFrame.height
                    rTouchX = event.rawX
                    rTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newW = (startW + (event.rawX - rTouchX).toInt()).coerceIn(260, 960)
                    val newH = (startH + (event.rawY - rTouchY).toInt()).coerceIn(110, 420)
                    val lp = gridFrame.layoutParams
                    lp.width = newW
                    lp.height = newH
                    gridFrame.layoutParams = lp
                    true
                }
                else -> false
            }
        }
    }

    private fun bindButtons() {
        val drawer = robotView.findViewById<LinearLayout>(R.id.detailDrawerPanel)
        val toggleArrow = robotView.findViewById<TextView>(R.id.btnToggleDrawer)
        val btnCloseService = robotView.findViewById<TextView>(R.id.btnCloseService)

        val btnOpenRoadScanner = robotView.findViewById<Button>(R.id.btnOpenRoadScanner)
        val btnTogglePause = robotView.findViewById<Button>(R.id.btnTogglePause)
        val btnResetTable = robotView.findViewById<Button>(R.id.btnResetTable)

        val btnAddP = robotView.findViewById<Button>(R.id.btnAddP)
        val btnAddB = robotView.findViewById<Button>(R.id.btnAddB)
        val btnAddT = robotView.findViewById<Button>(R.id.btnAddT)
        val btnUndoLast = robotView.findViewById<Button>(R.id.btnUndoLast)

        val btnLockAndCrawl = roadScannerView.findViewById<Button>(R.id.btnLockAndCrawl)
        val btnToggleCols = roadScannerView.findViewById<Button>(R.id.btnToggleCols)
        val btnHideRoadBox = roadScannerView.findViewById<Button>(R.id.btnHideRoadBox)
        val tvRoadHint = roadScannerView.findViewById<TextView>(R.id.tvRoadHint)
        val resizeHandle = roadScannerView.findViewById<TextView>(R.id.btnResizeHandle)

        toggleArrow.setOnClickListener {
            if (drawer.visibility == View.VISIBLE) {
                drawer.visibility = View.GONE
                toggleArrow.text = "▼"
            } else {
                drawer.visibility = View.VISIBLE
                toggleArrow.text = "▲"
            }
        }

        btnCloseService.setOnClickListener { stopSelf() }

        btnTogglePause.setOnClickListener {
            isAutoScanning = !isAutoScanning
            btnTogglePause.text = if (isAutoScanning) "⏸ Tạm dừng" else "▶️ Tiếp tục"
        }

        btnResetTable.setOnClickListener {
            history.clear()
            toolWins = 0
            toolLosses = 0
            lastPredictedSide = "WAIT"
            lastOcrCooldownMs = 0L
            stableGridFrames = 0
            pendingGridSequence = null
            lastSourceTag = "Bàn mới"
            refreshHudDisplay()
        }

        btnOpenRoadScanner.setOnClickListener {
            roadScannerView.visibility =
                if (roadScannerView.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        btnHideRoadBox.setOnClickListener {
            roadScannerView.visibility = View.GONE
        }

        btnToggleCols.setOnClickListener {
            gridCols = when (gridCols) {
                12 -> 14
                14 -> 10
                else -> 12
            }
            btnToggleCols.text = "6x$gridCols"
            if (isRoadLocked) {
                crawlBeadPlateGrid(forceImmediate = true)
            }
        }

        btnLockAndCrawl.setOnClickListener {
            isRoadLocked = !isRoadLocked
            if (isRoadLocked) {
                btnLockAndCrawl.text = "🔓 Mở khóa khung"
                tvRoadHint.text = "🔒 ĐANG AUTO CÀO BẢNG CẦU (6x$gridCols)"
                resizeHandle.visibility = View.GONE
                roadScannerParams.flags =
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                windowManager.updateViewLayout(roadScannerView, roadScannerParams)
                crawlBeadPlateGrid(forceImmediate = true)
            } else {
                btnLockAndCrawl.text = "🔒 Khóa & Cào Data"
                tvRoadHint.text = "🎯 KHỚP KHUNG LÊN BẢNG CẦU (6 HÀNG)"
                resizeHandle.visibility = View.VISIBLE
                roadScannerParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                windowManager.updateViewLayout(roadScannerView, roadScannerParams)
            }
        }

        btnAddP.setOnClickListener { appendSingleOutcome("P", "Sửa tay") }
        btnAddB.setOnClickListener { appendSingleOutcome("B", "Sửa tay") }
        btnAddT.setOnClickListener { appendSingleOutcome("T", "Sửa tay") }
        btnUndoLast.setOnClickListener {
            if (history.isNotEmpty()) {
                history.removeAt(history.lastIndex)
                lastSourceTag = "Xóa 1"
                refreshHudDisplay()
            }
        }
    }

    private fun acquireScreenBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return null

        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val rawBitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            rawBitmap.copyPixelsFromBuffer(buffer)
            if (rawBitmap.width != image.width) {
                val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, image.width, image.height)
                rawBitmap.recycle()
                cropped
            } else {
                rawBitmap
            }
        } finally {
            image.close()
        }
    }

    private fun runVisionPipeline() {
        val bitmap = acquireScreenBitmap() ?: return

        try {
            if (isRoadLocked && roadScannerView.visibility == View.VISIBLE) {
                crawlBeadPlateFromBitmap(bitmap, forceImmediate = false)
            }

            if (!isOcrBusy && System.currentTimeMillis() > lastOcrCooldownMs) {
                runStrictBannerOcr(bitmap)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun crawlBeadPlateGrid(forceImmediate: Boolean) {
        val bitmap = acquireScreenBitmap() ?: return
        try {
            crawlBeadPlateFromBitmap(bitmap, forceImmediate)
        } finally {
            bitmap.recycle()
        }
    }

    private fun crawlBeadPlateFromBitmap(bitmap: Bitmap, forceImmediate: Boolean) {
        val gridFrame = roadScannerView.findViewById<FrameLayout>(R.id.roadGridFrame)
        val loc = IntArray(2)
        gridFrame.getLocationOnScreen(loc)

        val left = loc[0].coerceIn(0, bitmap.width - 10)
        val top = loc[1].coerceIn(0, bitmap.height - 10)
        val width = gridFrame.width.coerceIn(20, bitmap.width - left)
        val height = gridFrame.height.coerceIn(20, bitmap.height - top)

        val rows = 6
        val cols = gridCols
        val cellW = width.toFloat() / cols
        val cellH = height.toFloat() / rows

        val scannedCells = mutableListOf<String>()
        val hsv = FloatArray(3)

        for (c in 0 until cols) {
            var emptyInCol = 0
            for (r in 0 until rows) {
                val cx = (left + (c + 0.5f) * cellW).toInt()
                val cy = (top + (r + 0.5f) * cellH).toInt()
                val rx = (cellW * 0.28f).toInt().coerceAtLeast(2)
                val ry = (cellH * 0.28f).toInt().coerceAtLeast(2)

                var blueVotes = 0
                var redVotes = 0
                var greenVotes = 0
                var totalDots = 0

                for (dy in -ry..ry step 2) {
                    for (dx in -rx..rx step 2) {
                        val pxX = (cx + dx).coerceIn(0, bitmap.width - 1)
                        val pxY = (cy + dy).coerceIn(0, bitmap.height - 1)
                        Color.colorToHSV(bitmap.getPixel(pxX, pxY), hsv)
                        val h = hsv[0]
                        val s = hsv[1]
                        val v = hsv[2]
                        totalDots++

                        if (s >= 0.48f && v >= 0.35f) {
                            when {
                                h in 198f..248f -> blueVotes++
                                h <= 14f || h >= 342f -> redVotes++
                                h in 85f..155f -> greenVotes++
                            }
                        }
                    }
                }

                val minVotes = (totalDots * 0.22).toInt().coerceAtLeast(3)
                val outcome = when {
                    redVotes >= minVotes && redVotes > blueVotes * 1.4 && redVotes > greenVotes * 1.4 -> "B"
                    blueVotes >= minVotes && blueVotes > redVotes * 1.4 && blueVotes > greenVotes * 1.4 -> "P"
                    greenVotes >= minVotes && greenVotes > redVotes * 1.4 && greenVotes > blueVotes * 1.4 -> "T"
                    else -> null
                }

                if (outcome != null) {
                    scannedCells.add(outcome)
                } else {
                    emptyInCol++
                }
            }
            if (emptyInCol == rows) break
        }

        if (scannedCells.isEmpty()) {
            if (forceImmediate) {
                Toast.makeText(
                    this,
                    "Chưa thấy chấm cầu trong khung! Hãy kéo khung đè khít lên Bảng Cầu 6 hàng.",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        if (!forceImmediate) {
            if (scannedCells == pendingGridSequence) {
                stableGridFrames++
            } else {
                pendingGridSequence = scannedCells
                stableGridFrames = 1
                return
            }
            if (stableGridFrames < 2) return
        }

        if (scannedCells != history) {
            val prevSize = history.size
            if (scannedCells.size == prevSize + 1 && scannedCells.dropLast(1) == history) {
                val newest = scannedCells.last()
                if ((newest == "P" || newest == "B") && (lastPredictedSide == "P" || lastPredictedSide == "B")) {
                    if (newest == lastPredictedSide) toolWins++ else toolLosses++
                }
            } else if (scannedCells.size < prevSize) {
                toolWins = 0
                toolLosses = 0
                lastPredictedSide = "WAIT"
            }

            history.clear()
            history.addAll(scannedCells)
            lastOcrCooldownMs = System.currentTimeMillis() + 6_000L
            lastSourceTag = "Cào Bảng Cầu"
            vibrateFeedback()
            refreshHudDisplay()

            if (forceImmediate) {
                Toast.makeText(
                    this,
                    "✅ Đã khóa & cào chuẩn ${scannedCells.size} ván từ Bảng Cầu!",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun runStrictBannerOcr(fullBitmap: Bitmap) {
        val scanTop = (fullBitmap.height * 0.18).toInt()
        val scanBottom = (fullBitmap.height * 0.56).toInt()
        val scanLeft = (fullBitmap.width * 0.15).toInt()
        val scanRight = (fullBitmap.width * 0.85).toInt()

        val cropW = (scanRight - scanLeft).coerceAtLeast(32)
        val cropH = (scanBottom - scanTop).coerceAtLeast(32)
        val crop = Bitmap.createBitmap(fullBitmap, scanLeft, scanTop, cropW, cropH)

        val hudLoc = IntArray(2)
        robotView.getLocationOnScreen(hudLoc)
        val hL = (hudLoc[0] - scanLeft - 10).coerceIn(0, cropW)
        val hT = (hudLoc[1] - scanTop - 10).coerceIn(0, cropH)
        val hR = (hudLoc[0] + robotView.width - scanLeft + 10).coerceIn(0, cropW)
        val hB = (hudLoc[1] + robotView.height - scanTop + 10).coerceIn(0, cropH)
        if (hR > hL && hB > hT) {
            for (y in hT until hB) {
                for (x in hL until hR) {
                    crop.setPixel(x, y, Color.BLACK)
                }
            }
        }

        isOcrBusy = true
        textRecognizer.process(InputImage.fromBitmap(crop, 0))
            .addOnSuccessListener { visionText ->
                if (!isRoadLocked) {
                    val strictOutcome = parseStrictResultBanner(visionText.text)
                    if (strictOutcome != null && System.currentTimeMillis() > lastOcrCooldownMs) {
                        lastOcrCooldownMs = System.currentTimeMillis() + 9_500L
                        appendSingleOutcome(strictOutcome, "OCR Banner")
                    }
                }
            }
            .addOnCompleteListener {
                crop.recycle()
                isOcrBusy = false
            }
    }

    private fun parseStrictResultBanner(rawText: String): String? {
        if (rawText.isBlank()) return null
        val clean = Normalizer.normalize(rawText, Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .uppercase(Locale.US)

        val lines = clean.lines().map { it.trim() }
        for (line in lines) {
            if (line == "TIE WIN" || line == "TIE WINS" || line == "HOA THANG" || line == "KET QUA HOA") {
                return "T"
            }
            if (line == "PLAYER WIN" || line == "PLAYER WINS" || line == "CON THANG" || line == "TAY CON THANG") {
                return "P"
            }
            if (line == "BANKER WIN" || line == "BANKER WINS" || line == "CAI THANG" || line == "NHA CAI THANG") {
                return "B"
            }
        }
        return null
    }

    private fun appendSingleOutcome(outcome: String, tag: String) {
        if ((outcome == "P" || outcome == "B") && (lastPredictedSide == "P" || lastPredictedSide == "B")) {
            if (outcome == lastPredictedSide) toolWins++ else toolLosses++
        }
        history.add(outcome)
        lastSourceTag = tag
        vibrateFeedback()
        refreshHudDisplay()
    }

    private fun refreshHudDisplay() {
        val report = BaccaratProEngineV4.analyze(history, toolWins, toolLosses, lastSourceTag)
        lastPredictedSide = report.recommendedSide

        val tvSideMain = robotView.findViewById<TextView>(R.id.tvSideCalloutMain)
        val tvSideSub = robotView.findViewById<TextView>(R.id.tvSideCalloutSub)
        val tvSideBridge = robotView.findViewById<TextView>(R.id.tvSideBridgeTag)

        val tvLastEvent = robotView.findViewById<TextView>(R.id.tvLastDetectedEvent)
        val tvDetail = robotView.findViewById<TextView>(R.id.tvBridgeAnalysisDetail)
        val tvWinLoss = robotView.findViewById<TextView>(R.id.tvToolWinLossStats)
        val tvTape = robotView.findViewById<TextView>(R.id.tvHistoryTape)

        tvSideMain.text = report.sideMainText
        tvSideSub.text = report.sideSubText
        tvSideBridge.text = report.sideBridgeTag

        tvLastEvent.text = report.lastEventText
        tvDetail.text = report.bridgeDetailText
        tvWinLoss.text = report.winLossText
        tvTape.text = report.historyTapeText

        when (report.recommendedSide) {
            "P" -> tvSideMain.setTextColor(Color.parseColor("#40C4FF"))
            "B" -> tvSideMain.setTextColor(Color.parseColor("#FF5252"))
            else -> tvSideMain.setTextColor(Color.parseColor("#69F0AE"))
        }
    }

    private fun vibrateFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(65, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(65, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(65)
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(scanLoopRunnable)
        textRecognizer.close()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        try {
            if (::robotView.isInitialized) windowManager.removeView(robotView)
        } catch (_: Exception) {
        }
        try {
            if (::roadScannerView.isInitialized) windowManager.removeView(roadScannerView)
        } catch (_: Exception) {
        }
    }
}
