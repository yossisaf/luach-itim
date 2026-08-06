package com.luachitim.ui

import androidx.compose.runtime.Composable

@Composable
expect fun FilePicker(
    show: Boolean,
    fileExtensions: List<String>,
    onError: () -> Unit = {},
    onFileSelected: (String?) -> Unit
)
