package com.caravanfire.calmqr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.caravanfire.calmqr.data.AppDatabase
import com.caravanfire.calmqr.navigation.AppNavigation
import com.mudita.mmd.ThemeMMD

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = AppDatabase.getInstance(this)
        val savedCodeDao = database.savedCodeDao()

        setContent {
            ThemeMMD {
                val navController = rememberNavController()
                AppNavigation(
                    navController = navController,
                    savedCodeDao = savedCodeDao
                )
            }
        }
    }
}
