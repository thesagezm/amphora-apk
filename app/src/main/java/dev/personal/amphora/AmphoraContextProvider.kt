//
//  AmphoraContextProvider.kt
//  app
//
//  Application-scoped context holder — one process-wide context the
//  store layer can reach without leaking an Activity. Initialized by
//  `AmphoraApp` before anything reads it.
//

package dev.personal.amphora

import android.content.Context

object AmphoraContextProvider {
    @Volatile
    lateinit var context: Context
        private set

    fun install(context: Context) {
        this.context = context.applicationContext
    }
}
