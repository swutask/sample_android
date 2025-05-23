package com.chateo.chatcorner.ui

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.chateo.chatcorner.R

class SettingActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setting)
        supportActionBar?.hide()
    }
}