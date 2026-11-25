package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.data.ai.configureAppCheck
import com.example.ui.DialogueLensScreen
import com.example.ui.state.DialogueLensViewModel
import com.example.ui.theme.DialogueLensTheme

class MainActivity : ComponentActivity() {
    private val viewModel: DialogueLensViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure Firebase App Check with launch intent before rendering UI or creating ViewModels
        configureAppCheck(this, intent)

        enableEdgeToEdge()
        setContent {
            DialogueLensTheme {
                DialogueLensScreen(viewModel = viewModel)
            }
        }
        dispatchIncomingIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppVisible()
    }

    override fun onStop() {
        if (!isChangingConfigurations) viewModel.onAppHidden()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatchIncomingIntent(intent)
    }

    private fun dispatchIncomingIntent(incoming: Intent?) {
        if (incoming == null) return
        when (incoming.action) {
            Intent.ACTION_SEND -> {
                val sharedText = incoming.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                if (sharedText.isNotBlank()) {
                    viewModel.handleSharedIncomingText(sharedText)
                }
            }
            Intent.ACTION_VIEW -> {
                val viewUrl = incoming.dataString.orEmpty()
                if (viewUrl.isNotBlank()) {
                    viewModel.handleSharedIncomingText(viewUrl)
                }
            }
        }
    }
}
