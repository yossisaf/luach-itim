package com.luachitim.ui

import androidx.compose.runtime.Composable

@Composable
actual fun AppBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Desktop: no back button, handled via keyboard elsewhere
}
