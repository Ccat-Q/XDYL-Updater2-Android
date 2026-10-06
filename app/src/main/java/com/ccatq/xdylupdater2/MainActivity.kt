package com.ccatq.xdylupdater2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.ccatq.xdylupdater2.ui.AppViewModel
import com.ccatq.xdylupdater2.ui.StarWaveRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as StarWaveApplication).graph
        val model = ViewModelProvider(this, AppViewModel.factory(graph))[AppViewModel::class.java]
        setContent { StarWaveRoot(model) }
    }
}
