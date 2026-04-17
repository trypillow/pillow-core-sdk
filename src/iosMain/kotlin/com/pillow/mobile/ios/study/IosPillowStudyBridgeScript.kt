package com.pillow.mobile.ios.study

import com.pillow.mobile.sdk.pillowJsJsonLiteral
import com.pillow.mobile.sdk.pillowJsStringLiteral
import kotlinx.serialization.json.JsonObject

internal fun createIosPillowStudyBridgeScript(
  campaignHandoffToken: String,
  restoredSessionToken: String?,
  forceFreshSession: Boolean,
  audioCapable: Boolean,
  webDisplay: JsonObject?,
): String {
  val restoredTokenValue = restoredSessionToken?.let(::pillowJsStringLiteral) ?: "null"
  val displayInstructionsEntry = webDisplay?.let {
    ",\n        displayInstructions: ${pillowJsJsonLiteral(it)}"
  } ?: ""
  return """
    (function() {
      window.__PILLOW_MOBILE_CONTEXT__ = {
        campaignHandoffToken: ${pillowJsStringLiteral(campaignHandoffToken)},
        restoredSessionToken: $restoredTokenValue,
        forceFreshSession: $forceFreshSession,
        platform: 'ios',
        capabilities: { audio: $audioCapable }$displayInstructionsEntry
      };

      function forwardToNative(message) {
        if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.pillowStudyBridge) {
          try {
            window.webkit.messageHandlers.pillowStudyBridge.postMessage(message);
          } catch (error) {
            console.error('[PillowSDK] Failed to forward study message', error);
          }
        }
      }

      const originalWindowPostMessage = window.postMessage ? window.postMessage.bind(window) : null;
      window.postMessage = function(message, targetOrigin, transfer) {
        forwardToNative(message);
        return originalWindowPostMessage ? originalWindowPostMessage(message, targetOrigin, transfer) : undefined;
      };

      if (window.parent && window.parent !== window && window.parent.postMessage) {
        const originalParentPostMessage = window.parent.postMessage.bind(window.parent);
        window.parent.postMessage = function(message, targetOrigin, transfer) {
          forwardToNative(message);
          return originalParentPostMessage(message, targetOrigin, transfer);
        };
      }
    })();
  """.trimIndent()
}
