package com.pillow.mobile.android.study

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.pillow.mobile.audience.runtime.AudienceLogger
import org.json.JSONException
import org.json.JSONObject

internal class PillowStudyPresenter(
  private val activity: Activity,
  private val alias: String,
  private val studyUrl: String,
  private val campaignHandoffToken: String,
  private val restoredSessionToken: String?,
  private val forceFreshSession: Boolean,
  private val userAgent: String,
  private val onStudySession: (alias: String, sessionToken: String) -> Unit,
  private val onConversationEnded: (alias: String) -> Unit,
  private val onDismiss: () -> Unit,
  private val logger: AudienceLogger,
) {
  private var dialog: Dialog? = null
  private var rootView: FrameLayout? = null
  private var scrimView: View? = null
  private var sheetContainer: FrameLayout? = null
  private var webView: WebView? = null
  private var dismissHandled: Boolean = false
  private var dismissInFlight: Boolean = false
  private var bootstrapScriptHandler: ScriptHandler? = null
  private var pendingPermissionRequest: PermissionRequest? = null

  fun handlePermissionResult(permissions: Array<String>, grantResults: IntArray) {
    val pending = pendingPermissionRequest ?: return
    pendingPermissionRequest = null

    val granted = pending.resources.filter { resource ->
      when (resource) {
        PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
          permissions.indexOf(android.Manifest.permission.RECORD_AUDIO).let { index ->
            index >= 0 && grantResults[index] == PackageManager.PERMISSION_GRANTED
          }
        PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
          permissions.indexOf(android.Manifest.permission.CAMERA).let { index ->
            index >= 0 && grantResults[index] == PackageManager.PERMISSION_GRANTED
          }
        else -> false
      }
    }.toTypedArray()

    if (granted.isNotEmpty()) {
      pending.grant(granted)
    } else {
      pending.deny()
    }
  }

  fun present(): Boolean {
    if (activity.isFinishing || activity.isDestroyedCompat()) {
      logger.warn("Cannot present study because the Activity is finishing")
      return false
    }

    val currentDialog = Dialog(activity, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
    currentDialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    currentDialog.setCancelable(false)
    currentDialog.setCanceledOnTouchOutside(false)
    currentDialog.setOnKeyListener { _, keyCode, event ->
      if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
        requestDismiss()
        true
      } else {
        false
      }
    }
    currentDialog.setOnDismissListener {
      disposeWebView()
      handleDismiss()
    }

    val contentView = buildContentView(currentDialog)
    currentDialog.setContentView(contentView)
    currentDialog.window?.let(::configureWindow)
    currentDialog.show()
    dialog = currentDialog
    startEnterAnimation()
    return true
  }

  private fun buildContentView(dialog: Dialog): View {
    val root = FrameLayout(activity).apply {
      layoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
      )
      // Allow the sheet to draw below the root's bounds during drag-to-dismiss
      clipChildren = false
      clipToPadding = false
    }
    rootView = root

    val currentScrimView = View(activity).apply {
      setBackgroundColor(Color.parseColor("#66000000"))
      alpha = 0f
      layoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
      )
      setOnClickListener {
        requestDismiss()
      }
    }
    scrimView = currentScrimView
    root.addView(currentScrimView)

    val currentSheetContainer = FrameLayout(activity).apply {
      background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadii = floatArrayOf(
          dp(28).toFloat(), dp(28).toFloat(),
          dp(28).toFloat(), dp(28).toFloat(),
          0f, 0f,
          0f, 0f,
        )
        setColor(Color.WHITE)
      }
      clipToOutline = true
      elevation = dp(12).toFloat()
      isClickable = true
      layoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
        Gravity.BOTTOM,
      )
    }
    sheetContainer = currentSheetContainer
    root.addView(currentSheetContainer)

    val contentContainer = FrameLayout(activity).apply {
      layoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
      )
      setBackgroundColor(Color.WHITE)
    }
    currentSheetContainer.addView(contentContainer)

    val currentWebView = createWebView()
    webView = currentWebView
    contentContainer.addView(
      currentWebView,
      FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
      ),
    )

    val chromeView = createChrome()
    currentSheetContainer.addView(
      chromeView,
      FrameLayout.LayoutParams(
        dp(36),
        dp(5),
        Gravity.TOP or Gravity.CENTER_HORIZONTAL,
      ).apply {
        topMargin = dp(12)
      },
    )

    val dragZone = createDragZone()
    currentSheetContainer.addView(dragZone)
    installDragToDismiss(dragZone, currentSheetContainer, currentScrimView)

    installInsets(root, currentSheetContainer, contentContainer, chromeView, dragZone)
    currentWebView.loadUrl(studyUrl)

    return root
  }

  private fun installInsets(
    root: FrameLayout,
    sheetContainer: FrameLayout,
    contentContainer: FrameLayout,
    chromeView: View,
    dragZone: View,
  ) {
    root.setOnApplyWindowInsetsListener { _, insets ->
      val (topInset, bottomInset) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val systemBarsInsets = insets.getInsets(WindowInsets.Type.systemBars())
        val cutoutInsets = insets.getInsets(WindowInsets.Type.displayCutout())
        maxOf(systemBarsInsets.top, cutoutInsets.top) to systemBarsInsets.bottom
      } else {
        @Suppress("DEPRECATION")
        insets.systemWindowInsetTop to insets.systemWindowInsetBottom
      }
      (sheetContainer.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
        params.topMargin = topInset + dp(8)
        params.bottomMargin = 0
        sheetContainer.layoutParams = params
      }
      contentContainer.setPadding(
        0,
        dp(CHROME_TOP_PADDING_DP),
        0,
        bottomInset,
      )
      (chromeView.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
        params.topMargin = dp(8)
        chromeView.layoutParams = params
      }
      (dragZone.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
        params.height = dp(DRAG_ZONE_HEIGHT_DP)
        dragZone.layoutParams = params
      }
      insets
    }
    root.requestApplyInsets()
  }

  @Suppress("DEPRECATION")
  private fun configureWindow(window: Window) {
    window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    window.setLayout(
      ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.MATCH_PARENT,
    )
    window.setWindowAnimations(0)
    // Prevent the system from resizing the WebView when the keyboard opens.
    // The webapp JS (useMobileKeyboard + visualViewport) manages the layout
    // itself, matching iOS WKWebView behavior.
    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
    window.statusBarColor = Color.TRANSPARENT
    window.navigationBarColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      window.isStatusBarContrastEnforced = false
      window.isNavigationBarContrastEnforced = false
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      window.setDecorFitsSystemWindows(false)
      window.insetsController?.setSystemBarsAppearance(
        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
          WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
          WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
      )
    } else {
      @Suppress("DEPRECATION")
      window.decorView.systemUiVisibility =
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
        View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }
  }

  private fun createChrome(): View =
    View(activity).apply {
      background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(2.5f).toFloat()
        setColor(Color.parseColor("#D6D6D6"))
      }
    }

  /** Transparent full-width touch target covering the sheet header (drag handle area). */
  private fun createDragZone(): View =
    View(activity).apply {
      setBackgroundColor(Color.TRANSPARENT)
      layoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        dp(DRAG_ZONE_HEIGHT_DP),
        Gravity.TOP,
      )
    }

  /** Installs a vertical drag gesture on [dragHandle] that slides the sheet down to dismiss.
   *  Uses only [View.setTranslationY] — no layoutParams changes — to avoid triggering
   *  a re-layout of the WebView during the gesture. */
  @SuppressLint("ClickableViewAccessibility")
  private fun installDragToDismiss(
    dragHandle: View,
    sheet: FrameLayout,
    scrim: View,
  ) {
    var startRawY = 0f
    var startTimeMs = 0L

    dragHandle.setOnTouchListener { _, event ->
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          if (dismissInFlight) return@setOnTouchListener false
          startRawY = event.rawY
          startTimeMs = System.currentTimeMillis()
          dragHandle.parent?.requestDisallowInterceptTouchEvent(true)
          setDragFlag(true)
          true
        }

        MotionEvent.ACTION_MOVE -> {
          val dy = (event.rawY - startRawY).coerceAtLeast(0f)
          sheet.translationY = dy
          val fraction = (dy / sheet.height.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
          scrim.alpha = 1f - fraction
          true
        }

        MotionEvent.ACTION_UP -> {
          setDragFlag(false)
          val dy = (event.rawY - startRawY).coerceAtLeast(0f)
          val elapsedMs = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(1)
          val velocityPxPerSec = (dy / elapsedMs) * 1000f

          if (dy > dp(DRAG_DISMISS_THRESHOLD_DP) || velocityPxPerSec > dp(DRAG_VELOCITY_THRESHOLD_DP)) {
            dismissViaDrag(sheet, scrim)
          } else {
            snapBack(sheet, scrim)
          }
          true
        }

        MotionEvent.ACTION_CANCEL -> {
          setDragFlag(false)
          snapBack(sheet, scrim)
          true
        }

        else -> false
      }
    }
  }

  /** Tell the webapp to pause/resume viewport-based keyboard handling. */
  private fun setDragFlag(active: Boolean) {
    webView?.evaluateJavascript(
      "window.__PILLOW_DRAG_IN_PROGRESS__=${if (active) "true" else "false"}",
      null,
    )
  }

  /** Animate the sheet off-screen and dismiss the dialog. */
  private fun dismissViaDrag(sheet: FrameLayout, scrim: View) {
    if (dismissInFlight) return
    dismissInFlight = true

    val remaining = sheet.height - sheet.translationY
    val durationMs = ((remaining / sheet.height) * 280).toLong().coerceIn(120, 280)

    scrim.animate()
      .alpha(0f)
      .setDuration(durationMs)
      .start()

    sheet.animate()
      .translationY(sheet.height.toFloat())
      .setDuration(durationMs)
      .setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f))
      .withEndAction { dialog?.dismiss() }
      .start()
  }

  /** Snap the sheet back to its resting position. */
  private fun snapBack(sheet: FrameLayout, scrim: View) {
    sheet.animate()
      .translationY(0f)
      .setDuration(250L)
      .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
      .start()
    scrim.animate()
      .alpha(1f)
      .setDuration(250L)
      .start()
  }

  private fun startEnterAnimation() {
    val currentSheetContainer = sheetContainer ?: return
    val currentScrimView = scrimView ?: return

    currentSheetContainer.post {
      val travelDistance = maxOf(dp(36), currentSheetContainer.height / 12)
      currentSheetContainer.translationY = travelDistance.toFloat()
      currentSheetContainer.alpha = 0.98f
      currentScrimView.alpha = 0f

      currentScrimView.animate()
        .alpha(1f)
        .setDuration(220L)
        .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
        .start()

      currentSheetContainer.animate()
        .translationY(0f)
        .alpha(1f)
        .setDuration(280L)
        .setInterpolator(android.view.animation.PathInterpolator(0f, 0f, 0f, 1f))
        .start()
    }
  }

  private fun requestDismiss() {
    if (dismissInFlight) {
      return
    }

    val currentDialog = dialog
    val currentSheetContainer = sheetContainer
    val currentScrimView = scrimView
    if (currentDialog == null || currentSheetContainer == null || currentScrimView == null) {
      dialog?.dismiss()
      return
    }

    dismissInFlight = true
    val travelDistance = maxOf(dp(28), currentSheetContainer.height / 14).toFloat()

    currentScrimView.animate()
      .alpha(0f)
      .setDuration(180L)
      .setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f))
      .start()

    currentSheetContainer.animate()
      .translationY(travelDistance)
      .alpha(0.995f)
      .setDuration(200L)
      .setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f))
      .withEndAction {
        currentDialog.dismiss()
      }
      .start()
  }

  @SuppressLint("SetJavaScriptEnabled")
  private fun createWebView(): WebView =
    WebView(activity).apply {
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      settings.loadsImagesAutomatically = true
      settings.mediaPlaybackRequiresUserGesture = false
      settings.userAgentString = userAgent
      setBackgroundColor(Color.WHITE)
      addJavascriptInterface(
        PillowStudyBridge { payload -> handleBridgeMessage(payload) },
        BRIDGE_NAME,
      )
      installDocumentStartScript(this)
      webChromeClient = object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
          val alreadyGranted = request.resources.filter { resource ->
            when (resource) {
              PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                  PackageManager.PERMISSION_GRANTED
              PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                activity.checkSelfPermission(android.Manifest.permission.CAMERA) ==
                  PackageManager.PERMISSION_GRANTED
              else -> false
            }
          }.toTypedArray()

          if (alreadyGranted.isNotEmpty()) {
            request.grant(alreadyGranted)
            return
          }

          // Permission not yet granted — hold the request and ask the user at runtime.
          val needed = mutableListOf<String>()
          for (resource in request.resources) {
            when (resource) {
              PermissionRequest.RESOURCE_AUDIO_CAPTURE -> needed += android.Manifest.permission.RECORD_AUDIO
              PermissionRequest.RESOURCE_VIDEO_CAPTURE -> needed += android.Manifest.permission.CAMERA
            }
          }
          if (needed.isEmpty()) {
            request.deny()
            return
          }
          pendingPermissionRequest = request
          activity.requestPermissions(needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
          logger.debug(
            "Study webview console: ${consoleMessage.message()} @${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}",
          )
          return true
        }
      }
      webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
          if (bootstrapScriptHandler == null) {
            injectBootstrapScript()
          }
        }

        override fun onReceivedError(
          view: WebView?,
          request: WebResourceRequest?,
          error: WebResourceError?,
        ) {
          if (request?.isForMainFrame == true) {
            logger.error("Study webview failed to load: ${error?.description}")
          }
        }
      }
    }

  private fun installDocumentStartScript(webView: WebView) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
      logger.warn("Document-start script injection is unavailable; falling back to onPageFinished bridge setup")
      return
    }
    bootstrapScriptHandler = WebViewCompat.addDocumentStartJavaScript(
      webView,
      createAndroidPillowStudyBridgeScript(
        campaignHandoffToken = campaignHandoffToken,
        restoredSessionToken = restoredSessionToken,
        forceFreshSession = forceFreshSession,
        audioCapable = hasManifestPermission(android.Manifest.permission.RECORD_AUDIO),
        bridgeName = BRIDGE_NAME,
      ),
      setOf(resolveAllowedOriginRule()),
    )
  }

  private fun injectBootstrapScript() {
    val bootstrapScript = createAndroidPillowStudyBridgeScript(
      campaignHandoffToken = campaignHandoffToken,
      restoredSessionToken = restoredSessionToken,
      forceFreshSession = forceFreshSession,
      audioCapable = hasManifestPermission(android.Manifest.permission.RECORD_AUDIO),
      bridgeName = BRIDGE_NAME,
    )
    webView?.evaluateJavascript(bootstrapScript, null)
  }

  private fun handleBridgeMessage(payload: String) {
    val body = try {
      JSONObject(payload)
    } catch (_: JSONException) {
      logger.debug("Ignoring non-JSON study bridge payload")
      return
    }
    val type = body.optString("type").trim()
    if (type.isEmpty()) {
      return
    }
    val data = body.optJSONObject("data")

    when (type) {
      "pillow:studySession" -> {
        val eventAlias = data?.optString("alias")?.trim().orEmpty()
        val sessionToken = data?.optString("sessionToken")?.trim().orEmpty()
        if (eventAlias.isNotEmpty() && sessionToken.isNotEmpty()) {
          onStudySession(eventAlias, sessionToken)
        }
      }
      "pillow:conversationEnded" -> {
        val eventAlias = data?.optString("alias")?.trim().orEmpty()
        if (eventAlias.isNotEmpty()) {
          onConversationEnded(eventAlias)
        }
      }
      else -> logger.debug("Ignoring bridge event $type")
    }
  }

  private fun hasManifestPermission(permission: String): Boolean =
    runCatching {
      val packageInfo = activity.packageManager.getPackageInfo(
        activity.packageName,
        PackageManager.GET_PERMISSIONS,
      )
      packageInfo.requestedPermissions?.contains(permission) == true
    }.getOrDefault(false)

  private fun disposeWebView() {
    bootstrapScriptHandler?.remove()
    bootstrapScriptHandler = null
    // Release any pending permission request so it doesn't hold internal
    // Chrome/WebView state after the WebView is destroyed.
    pendingPermissionRequest?.deny()
    pendingPermissionRequest = null
    webView?.webChromeClient = null
    webView?.webViewClient = WebViewClient()
    webView?.removeJavascriptInterface(BRIDGE_NAME)
    webView?.stopLoading()
    webView?.loadUrl("about:blank")
    webView?.clearHistory()
    webView?.removeAllViews()
    webView?.destroy()
    webView = null
    rootView?.setOnApplyWindowInsetsListener(null)
    sheetContainer = null
    scrimView = null
    rootView = null
    dialog = null
  }

  private fun handleDismiss() {
    if (dismissHandled) {
      return
    }
    dismissHandled = true
    onDismiss()
  }

  private fun dp(value: Int): Int =
    TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP,
      value.toFloat(),
      activity.resources.displayMetrics,
    ).toInt()

  private fun dp(value: Float): Int =
    TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP,
      value,
      activity.resources.displayMetrics,
    ).toInt()

  private fun resolveAllowedOriginRule(): String {
    val uri = android.net.Uri.parse(studyUrl)
    val scheme = uri.scheme?.trim().orEmpty()
    val host = uri.host?.trim().orEmpty()
    require(scheme.isNotEmpty() && host.isNotEmpty()) {
      "Cannot resolve origin from study URL: scheme or host is missing"
    }
    return if (uri.port == -1) {
      "$scheme://$host"
    } else {
      "$scheme://$host:${uri.port}"
    }
  }

  private companion object {
    const val BRIDGE_NAME: String = "PillowStudyBridge"
    const val PERMISSION_REQUEST_CODE: Int = 48101
    /** Top padding for the content container (keeps content below the chrome handle). */
    const val CHROME_TOP_PADDING_DP = 4
    /** Height of the transparent drag-to-dismiss touch target at the top of the sheet (dp). */
    const val DRAG_ZONE_HEIGHT_DP = 32
    /** Minimum drag distance to trigger dismiss (dp). */
    const val DRAG_DISMISS_THRESHOLD_DP = 120
    /** Minimum fling velocity to trigger dismiss (dp/s). */
    const val DRAG_VELOCITY_THRESHOLD_DP = 600
  }
}

private fun Activity.isDestroyedCompat(): Boolean =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
    isDestroyed
  } else {
    false
  }

private class PillowStudyBridge(
  private val onMessage: (String) -> Unit,
) {
  @JavascriptInterface
  fun postMessage(payload: String) {
    onMessage(payload)
  }
}
