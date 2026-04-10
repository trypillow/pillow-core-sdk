package com.pillow.mobile.sdk

internal fun pillowJsStringLiteral(value: String): String =
  buildString(value.length + 2) {
    append('"')
    value.forEach { char ->
      when (char) {
        '\\' -> append("\\\\")
        '"' -> append("\\\"")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        '`' -> append("\\`")
        '<' -> append("\\u003c")
        '>' -> append("\\u003e")
        '$' -> append("\\u0024")
        '\u2028' -> append("\\u2028")
        '\u2029' -> append("\\u2029")
        else -> append(char)
      }
    }
    append('"')
  }
