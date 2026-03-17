package com.streamvault.app.ui.theme

import com.streamvault.app.ui.design.AppSpacing
import com.streamvault.app.ui.design.LocalAppSpacing

typealias Spacing = AppSpacing

val LocalSpacing = LocalAppSpacing

fun defaultSpacing(): Spacing = AppSpacing()
