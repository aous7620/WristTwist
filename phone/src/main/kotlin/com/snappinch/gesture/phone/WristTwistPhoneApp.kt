package com.snappinch.gesture.phone

import android.app.Application
import com.google.android.material.color.DynamicColors

class WristTwistPhoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}

