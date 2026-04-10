package com.pillow.mobile.android.study

import com.pillow.mobile.sdk.pillowJsStringLiteral

internal fun createAndroidPillowStudyBridgeScript(
  campaignHandoffToken: String,
  restoredSessionToken: String?,
  forceFreshSession: Boolean,
  audioCapable: Boolean,
  bridgeName: String,
): String {
  val restoredTokenValue = restoredSessionToken?.let(::pillowJsStringLiteral) ?: "null"
  return """
    (function() {
      window.__PILLOW_MOBILE_CONTEXT__ = {
        campaignHandoffToken: ${pillowJsStringLiteral(campaignHandoffToken)},
        restoredSessionToken: $restoredTokenValue,
        forceFreshSession: $forceFreshSession,
        platform: 'android',
        capabilities: { audio: $audioCapable }
      };

      function serializeMessage(message) {
        if (typeof message === 'string') {
          return message;
        }
        try {
          return JSON.stringify(message);
        } catch (error) {
          console.error('[PillowSDK] Failed to serialize study message', error);
          return null;
        }
      }

      function forwardToNative(message) {
        if (!window.$bridgeName || !window.$bridgeName.postMessage) {
          return;
        }
        var payload = serializeMessage(message);
        if (!payload) {
          return;
        }
        try {
          window.$bridgeName.postMessage(payload);
        } catch (error) {
          console.error('[PillowSDK] Failed to forward study message', error);
        }
      }

      var originalWindowPostMessage = window.postMessage ? window.postMessage.bind(window) : null;
      window.postMessage = function(message, targetOrigin, transfer) {
        forwardToNative(message);
        return originalWindowPostMessage ? originalWindowPostMessage(message, targetOrigin, transfer) : undefined;
      };

      if (window.parent && window.parent !== window && window.parent.postMessage) {
        var originalParentPostMessage = window.parent.postMessage.bind(window.parent);
        window.parent.postMessage = function(message, targetOrigin, transfer) {
          forwardToNative(message);
          return originalParentPostMessage(message, targetOrigin, transfer);
        };
      }
    })();
  """.trimIndent()
}
