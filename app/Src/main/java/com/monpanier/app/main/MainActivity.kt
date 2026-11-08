package com.monpanier.app

import android.os.Bundle
import android.widget.TextView
import android.view.Gravity
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val texte = TextView(this).apply {
            text = "MON PANIER\n\nTest de démarrage réussi !"
            textSize = 24f
            gravity = Gravity.CENTER
        }

        setContentView(texte)
    }
}
