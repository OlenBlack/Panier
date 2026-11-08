package com.monpanier.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import android.view.Gravity

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)

            val texte = TextView(this).apply {
                text = "MON PANIER\n\nActivité Android native démarrée !"
                textSize = 24f
                gravity = Gravity.CENTER
            }

            setContentView(texte)

        } catch (e: Throwable) {
            val erreur = TextView(this).apply {
                text = "ERREUR AU DÉMARRAGE :\n\n${e.javaClass.name}\n\n${e.message}"
                textSize = 16f
                setPadding(24, 24, 24, 24)
            }
            setContentView(erreur)
        }
    }
}
