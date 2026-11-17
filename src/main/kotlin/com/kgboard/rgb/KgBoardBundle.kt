package com.kgboard.rgb

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey

object KgBoardBundle : DynamicBundle(KgBoardBundle::class.java, "messages.KgBoardBundle") {
    fun message(@PropertyKey(resourceBundle = "messages.KgBoardBundle") key: String, vararg params: Any): String =
        getMessage(key, *params)
}
