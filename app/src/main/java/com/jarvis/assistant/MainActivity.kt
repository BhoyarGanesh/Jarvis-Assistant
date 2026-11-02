package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text("Jarvis") }
    }

    companion object {
        const val ACTION_LISTEN = "com.jarvis.assistant.action.LISTEN"
        fun listenIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).setAction(ACTION_LISTEN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
