@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.pillow.mobile.ios.study

import com.pillow.mobile.audience.runtime.AudienceLogger
import com.pillow.mobile.native.PillowCreateNoAccessoryWebView
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.useContents
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionRecordPermissionDenied
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSBundle
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.UIKit.UIApplication
import platform.UIKit.UIColor
import platform.UIKit.UIKeyboardAnimationDurationUserInfoKey
import platform.UIKit.UIKeyboardWillHideNotification
import platform.UIKit.UIModalPresentationPageSheet
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleLeftMargin
import platform.UIKit.UIViewAutoresizingFlexibleRightMargin
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration

internal class PillowStudyPresenter(
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
  private var controller: PillowStudyViewController? = null

  fun present(): Boolean {
    val rootController = topViewController() ?: return false
    val viewController =
      PillowStudyViewController(
        studyUrl = studyUrl,
        bootstrapScript = createIosPillowStudyBridgeScript(
          campaignHandoffToken = campaignHandoffToken,
          restoredSessionToken = restoredSessionToken,
          forceFreshSession = forceFreshSession,
          audioCapable = isMicrophoneAvailable(),
        ),
        userAgent = userAgent,
        onStudySession = onStudySession,
        onConversationEnded = onConversationEnded,
        onDismiss = onDismiss,
        logger = logger,
      )
    viewController.modalPresentationStyle = UIModalPresentationPageSheet
    controller = viewController
    rootController.presentViewController(viewController, true, null)
    return true
  }

  private fun topViewController(): UIViewController? {
    val application = UIApplication.sharedApplication
    var root: UIViewController? = null
    application.connectedScenes.forEach { scene ->
      val windowScene = scene as? platform.UIKit.UIWindowScene ?: return@forEach
      val keyWindow = windowScene.windows
        .firstOrNull { (it as? UIWindow)?.isKeyWindow() == true } as? UIWindow
      val candidateRoot = (keyWindow ?: windowScene.windows.firstOrNull() as? UIWindow)
        ?.rootViewController
        ?: return@forEach
      if (keyWindow != null) {
        root = candidateRoot
        return@forEach
      }
      if (root == null) {
        root = candidateRoot
      }
    }
    val resolvedRoot = root ?: return null

    var current: UIViewController = resolvedRoot
    while (true) {
      val presented = current.presentedViewController ?: break
      current = presented
    }
    return current
  }

  private fun isMicrophoneAvailable(): Boolean {
    val hasPlistKey = NSBundle.mainBundle.objectForInfoDictionaryKey("NSMicrophoneUsageDescription") != null
    if (!hasPlistKey) return false
    return AVAudioSession.sharedInstance().recordPermission != AVAudioSessionRecordPermissionDenied
  }
}

private class PillowStudyViewController(
  private val studyUrl: String,
  private val bootstrapScript: String,
  private val userAgent: String,
  private val onStudySession: (alias: String, sessionToken: String) -> Unit,
  private val onConversationEnded: (alias: String) -> Unit,
  private val onDismiss: () -> Unit,
  private val logger: AudienceLogger,
) : UIViewController(nibName = null, bundle = null), WKScriptMessageHandlerProtocol, WKNavigationDelegateProtocol {
  private var webView: WKWebView? = null
  private var dragIndicator: UIView? = null
  private val keyboardObservers = mutableListOf<Any>()

  override fun viewDidLoad() {
    super.viewDidLoad()
    view.backgroundColor = UIColor.colorWithWhite(0.985, alpha = 1.0)

    val userContentController = WKUserContentController()
    userContentController.addScriptMessageHandler(this, "pillowStudyBridge")
    userContentController.addUserScript(
      WKUserScript(
        source = bootstrapScript,
        injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
        forMainFrameOnly = true,
      ),
    )

    val configuration = WKWebViewConfiguration()
    configuration.userContentController = userContentController
    configuration.allowsInlineMediaPlayback = true

    val viewFrame = view.bounds
    val currentWebView = PillowCreateNoAccessoryWebView(viewFrame, configuration) as WKWebView
    currentWebView.autoresizingMask =
      UIViewAutoresizingFlexibleWidth.toULong() + UIViewAutoresizingFlexibleHeight.toULong()
    currentWebView.customUserAgent = userAgent
    currentWebView.navigationDelegate = this
    configureScrollView(currentWebView)
    webView = currentWebView
    view.addSubview(currentWebView)
    installChrome()
    installKeyboardObservers()

    NSURL.URLWithString(studyUrl)?.let { url ->
      currentWebView.loadRequest(NSURLRequest.requestWithURL(url))
    }
  }

  override fun viewDidLayoutSubviews() {
    super.viewDidLayoutSubviews()

    val topInset = view.safeAreaInsets.useContents { this.top }
    val width = view.bounds.useContents { size.width }
    val height = view.bounds.useContents { size.height }
    dragIndicator?.setFrame(CGRectMake((width - 36.0) / 2.0, topInset + 8.0, 36.0, 5.0))
  }

  override fun userContentController(
    userContentController: WKUserContentController,
    didReceiveScriptMessage: WKScriptMessage,
  ) {
    val body = didReceiveScriptMessage.body as? Map<*, *> ?: return
    val type = body["type"] as? String ?: return
    val data = body["data"] as? Map<*, *> ?: emptyMap<Any?, Any?>()

    when (type) {
      "pillow:studySession" -> {
        val alias = data["alias"] as? String ?: return
        val sessionToken = data["sessionToken"] as? String ?: return
        onStudySession(alias, sessionToken)
      }
      "pillow:conversationEnded" -> {
        val alias = data["alias"] as? String ?: return
        onConversationEnded(alias)
      }
      else -> logger.debug("Ignoring bridge event $type")
    }
  }

  override fun viewDidDisappear(animated: Boolean) {
    super.viewDidDisappear(animated)
    if (isBeingDismissed()) {
      removeKeyboardObservers()
      // Break the retain cycle: WKUserContentController holds a strong reference
      // to the script message handler (this VC). Without this removal the cycle
      // VC → webView → config → userContentController → VC prevents deallocation.
      webView?.configuration?.userContentController
        ?.removeScriptMessageHandlerForName("pillowStudyBridge")
      webView?.navigationDelegate = null
      webView?.removeFromSuperview()
      webView = null
      dragIndicator?.removeFromSuperview()
      dragIndicator = null
      onDismiss()
    }
  }

  private fun installKeyboardObservers() {
    val nc = NSNotificationCenter.defaultCenter

    val hideToken = nc.addObserverForName(
      name = UIKeyboardWillHideNotification,
      `object` = null,
      queue = NSOperationQueue.mainQueue,
    ) { notification ->
      val info = notification?.userInfo
      val duration = (info?.get(UIKeyboardAnimationDurationUserInfoKey) as? NSNumber)?.doubleValue ?: 0.25
      webView?.evaluateJavaScript(
        "window.__pillowKeyboardWillDismiss && window.__pillowKeyboardWillDismiss($duration)",
        completionHandler = null,
      )
    }

    keyboardObservers.add(hideToken!!)
  }

  private fun removeKeyboardObservers() {
    val nc = NSNotificationCenter.defaultCenter
    keyboardObservers.forEach { nc.removeObserver(it) }
    keyboardObservers.clear()
  }

  private fun installChrome() {
    val indicator = UIView(frame = CGRectMake(0.0, 0.0, 36.0, 5.0))
    indicator.backgroundColor = UIColor.colorWithWhite(0.84, alpha = 1.0)
    indicator.layer.cornerRadius = 2.5
    indicator.autoresizingMask =
      UIViewAutoresizingFlexibleLeftMargin.toULong() + UIViewAutoresizingFlexibleRightMargin.toULong()
    dragIndicator = indicator
    view.addSubview(indicator)
  }

  private fun configureScrollView(webView: WKWebView) {
    val scrollView = webView.scrollView
    scrollView.scrollEnabled = false
    scrollView.bounces = false
    scrollView.setAlwaysBounceVertical(false)
    scrollView.showsVerticalScrollIndicator = false
  }

  @ObjCSignatureOverride
  override fun webView(
    webView: WKWebView,
    didFailProvisionalNavigation: WKNavigation?,
    withError: platform.Foundation.NSError,
  ) {
    logger.error("Study webview failed to load: ${withError.localizedDescription}")
  }
}
