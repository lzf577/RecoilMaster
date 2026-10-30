package com.automacro.app

import android.app.Application
import android.os.Build
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku

class MacroApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("")
                Log.d("MacroApplication", "HiddenApiBypass exemptions added")
            } catch (e: Throwable) {
                Log.e("MacroApplication", "Failed to add HiddenApiBypass", e)
            }
        }
        try {
            Shizuku.addBinderReceivedListenerSticky {
                Log.d("MacroApplication", "Shizuku binder received!")
            }
            Shizuku.addBinderDeadListener {
                Log.d("MacroApplication", "Shizuku binder died!")
            }
        } catch (e: Exception) {
            Log.e("MacroApplication", "Failed to add Shizuku listeners", e)
        }
    }
}
