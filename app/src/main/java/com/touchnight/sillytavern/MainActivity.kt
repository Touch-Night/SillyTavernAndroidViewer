package com.touchnight.sillytavern

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private lateinit var windowInsetsController: WindowInsetsControllerCompat
    private var lastColor = Color.TRANSPARENT
    private val statusBarHeight = mutableIntStateOf(0)
    private lateinit var webView: WebView
    private lateinit var sharedPreferences: SharedPreferences
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val urls = listOf(
        "example1:88888",
        "https://www.example2.com",
        "http://example3.com"
    )
    private var currentUrlIndex = 0

    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        filePathCallback?.onReceiveValue(uris.toTypedArray())
        filePathCallback = null
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.all { it.value }) {
            webView.reload()
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            statusBarHeight.intValue = statusBarInsets.top
            insets
        }

        window.statusBarColor = Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        sharedPreferences = getSharedPreferences("AuthPreferences", Context.MODE_PRIVATE)

        setContent {
            WebViewContent()
        }

        setupOnBackPressedCallback()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    @Composable
    private fun WebViewContent() {
        val configuration = LocalConfiguration.current
        val density = LocalDensity.current
        var statusBarColor by remember { mutableStateOf(ComposeColor.Transparent) }
        val animatedStatusBarColor by animateColorAsState(
            targetValue = statusBarColor,
            animationSpec = tween(durationMillis = 300),
            label = "StatusBarColorAnimation"
        )
        val statusBarHeightDp = with(density) { statusBarHeight.intValue.toDp() }

        Column {
            if (configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(statusBarHeightDp)
                        .background(animatedStatusBarColor)
                )
            }
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setupWebView(this) { color ->
                            statusBarColor = ComposeColor(color)

                            // Update status bar text color
                            updateStatusBarTextColor(color)
                        }
                        webView = this
                        loadInitialUrl()

                        // 添加 setOnScrollChangeListener
                        setOnScrollChangeListener { _, _, _, _, _ ->
                            // 使用 postDelayed 来延迟执行，确保滚动已经停止
                            handler.removeCallbacksAndMessages(null) // 移除之前的回调
                            handler.postDelayed({
                                updateStatusBarColor { color ->
                                    statusBarColor = ComposeColor(color)
                                }
                            }, 10) // 10ms 延迟
                        }

                        isClickable = true
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(webView: WebView, onColorExtracted: (Int) -> Unit) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
        }
        webView.overScrollMode = View.OVER_SCROLL_NEVER

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
                val storedUsername = sharedPreferences.getString("username", "")
                val storedPassword = sharedPreferences.getString("password", "")

                if (!storedUsername.isNullOrEmpty() && !storedPassword.isNullOrEmpty()) {
                    handler?.proceed(storedUsername, storedPassword)
                } else {
                    showLoginDialog(handler)
                }
            }

            @RequiresApi(Build.VERSION_CODES.N)
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                updateStatusBarColor(onColorExtracted)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    handleLoadError()
                }
            }

            @RequiresApi(Build.VERSION_CODES.N)
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
                updateStatusBarColor(onColorExtracted)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
                this@MainActivity.filePathCallback = filePathCallback
                fileChooserLauncher.launch("*/*")
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.let { handlePermissionRequest(it) }
            }

            @RequiresApi(Build.VERSION_CODES.N)
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                updateStatusBarColor(onColorExtracted)
            }
        }
    }

    private fun calculateLuminance(color: Int): Double {
        val red = Color.red(color) / 255.0
        val green = Color.green(color) / 255.0
        val blue = Color.blue(color) / 255.0
        return 0.299 * red + 0.587 * green + 0.114 * blue
    }

    private fun shouldUseLightStatusBarText(color: Int): Boolean {
        val luminance = calculateLuminance(color)
        return luminance < 0.5
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun updateStatusBarColor(onColorExtracted: (Int) -> Unit) {
        extractColorFromScreen(window) { color ->
            if (isColorSignificantlyDifferent(color, lastColor)) {
                onColorExtracted(color)
                lastColor = color

                // Update status bar text color
                updateStatusBarTextColor(color)
            }
        }
    }

    private fun updateStatusBarTextColor(color: Int) {
        windowInsetsController.isAppearanceLightStatusBars = !shouldUseLightStatusBarText(color)
    }

    private fun isColorSignificantlyDifferent(color1: Int, color2: Int): Boolean {
        val threshold = 10
        return abs(Color.red(color1) - Color.red(color2)) > threshold ||
                abs(Color.green(color1) - Color.green(color2)) > threshold ||
                abs(Color.blue(color1) - Color.blue(color2)) > threshold
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun extractColorFromScreen(window: Window, callback: (Int) -> Unit) {
        val rootView = window.decorView.findViewById<View>(android.R.id.content)
        rootView.post {
            // 获取状态栏高度
            val statusBarHeight = WindowInsetsCompat.toWindowInsetsCompat(rootView.rootWindowInsets)
                .getInsets(WindowInsetsCompat.Type.statusBars()).top

            // 创建一个位图，宽度为屏幕宽度，高度为1像素
            val bitmap = Bitmap.createBitmap(rootView.width, 1, Bitmap.Config.ARGB_8888)

            // 将状态栏下方的一行像素绘制到位图中
            val canvas = android.graphics.Canvas(bitmap)
            canvas.translate(0f, -statusBarHeight.toFloat())
            rootView.draw(canvas)

            val colorMap = mutableMapOf<Int, Int>()
            var dominantColor = Color.TRANSPARENT
            var maxCount = 0

            // 统计颜色出现次数
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, 0)
                val count = colorMap.getOrDefault(pixel, 0) + 1
                colorMap[pixel] = count

                if (count > maxCount) {
                    maxCount = count
                    dominantColor = pixel
                }
            }

            callback(dominantColor)
            bitmap.recycle()
        }
    }

    private fun setupOnBackPressedCallback() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    private fun loadInitialUrl() {
        webView.loadUrl(urls[currentUrlIndex])
    }

    private fun handleLoadError() {
        if (currentUrlIndex < urls.size - 1) {
            currentUrlIndex++
            webView.loadUrl(urls[currentUrlIndex])
        } else {
            showErrorDialog()
        }
    }

    private fun showErrorDialog() {
        val errorMessages = urls.mapIndexed { index, url ->
            "URL $index: $url\nError: Unable to connect"
        }.joinToString("\n\n")

        AlertDialog.Builder(this)
            .setTitle("Connection Error")
            .setMessage("Failed to connect to all URLs:\n\n$errorMessages")
            .setPositiveButton(getString(R.string.OK)) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showLoginDialog(handler: HttpAuthHandler?) {
        val view = layoutInflater.inflate(R.layout.dialog_login, null)
        val usernameInput = view.findViewById<android.widget.EditText>(R.id.usernameInput)
        val passwordInput = view.findViewById<android.widget.EditText>(R.id.passwordInput)

        AlertDialog.Builder(this)
            .setView(view)
            .setTitle(getString(R.string.Login))
            .setPositiveButton(getString(R.string.OK)) { _, _ ->
                val username = usernameInput.text.toString()
                val password = passwordInput.text.toString()
                handler?.proceed(username, password)
                sharedPreferences.edit().apply {
                    putString("username", username)
                    putString("password", password)
                    apply()
                }
            }
            .setNegativeButton(getString(R.string.Cancel)) { _, _ -> handler?.cancel() }
            .show()
    }

    private fun handlePermissionRequest(request: PermissionRequest) {
        val permissions = request.resources.map { resourceToPermission(it) }.toTypedArray()
        val notGrantedPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGrantedPermissions.isEmpty()) {
            request.grant(request.resources)
        } else {
            permissionLauncher.launch(notGrantedPermissions.toTypedArray())
        }
    }

    private fun resourceToPermission(resource: String): String {
        return when (resource) {
            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
            else -> throw IllegalArgumentException("Unknown resource: $resource")
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
            windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        } else {
            windowInsetsController.show(WindowInsetsCompat.Type.statusBars())
            windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }
    }
}