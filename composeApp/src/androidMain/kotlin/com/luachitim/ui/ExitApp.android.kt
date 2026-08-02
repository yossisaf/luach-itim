package com.luachitim.ui

import android.os.Process

actual fun exitApp() {
    Process.killProcess(Process.myPid())
}
