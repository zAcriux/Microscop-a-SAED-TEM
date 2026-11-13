/*
 * TEM Lab
 * Copyright © 2026 F. Mares-Briones. All rights reserved.
 */
package com.temlab.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import com.temlab.app.data.AppViewModel
import com.temlab.app.screens.TemLabApp
import com.temlab.app.ui.Lang
import com.temlab.app.ui.LocalTr
import com.temlab.app.ui.TemLabTheme
import com.temlab.app.ui.Tr
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val spanish = when (vm.lang) { Lang.ES -> true; Lang.EN -> false; Lang.SYSTEM -> Locale.getDefault().language == "es" }
            TemLabTheme(vm.themeMode) {
                CompositionLocalProvider(LocalTr provides Tr(spanish)) { TemLabApp(vm) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_VIEW) i.data?.let { vm.openUris(listOf(it)) }
    }
}
