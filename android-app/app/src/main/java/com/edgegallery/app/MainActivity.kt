package com.edgegallery.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.edgegallery.app.ui.EdgeGalleryApp
import com.edgegallery.app.ui.EdgeGalleryTheme

/** Android entry point. All changing screen state lives in the ViewModel. */
class MainActivity : ComponentActivity() {
    private val viewModel: EdgeGalleryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EdgeGalleryTheme {
                EdgeGalleryApp(viewModel)
            }
        }
    }
}
