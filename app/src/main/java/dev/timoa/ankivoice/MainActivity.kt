package dev.timoa.ankivoice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ichi2.anki.FlashCardsContract
import dev.timoa.ankivoice.ui.SettingsScreen
import dev.timoa.ankivoice.ui.StudyScreen
import dev.timoa.ankivoice.ui.StudyViewModel
import dev.timoa.ankivoice.ui.theme.AnkiVoiceTheme
import dev.timoa.ankivoice.voice.VoiceCoordinator

class MainActivity : ComponentActivity() {
    private lateinit var voice: VoiceCoordinator

    private var micGranted by mutableStateOf(false)
    private var ankiGranted by mutableStateOf(false)

    private val micLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        micGranted = ok
    }

    private val ankiLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        ankiGranted = ok
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voice = VoiceCoordinator(this)
        refreshPermissions()
        enableEdgeToEdge()
        setContent {
            AnkiVoiceTheme {
                val nav = rememberNavController()
                val studyVm: StudyViewModel = viewModel()
                studyVm.bindVoice(voice)

                NavHost(navController = nav, startDestination = "study") {
                    composable("study") {
                        StudyScreen(
                            viewModel = studyVm,
                            hasMicPermission = micGranted,
                            hasAnkiPermission = ankiGranted,
                            onOpenSettings = { nav.navigate("settings") },
                            onRequestMic = {
                                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            onRequestAnki = {
                                ankiLauncher.launch(FlashCardsContract.READ_WRITE_PERMISSION)
                            },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            onBack = { nav.popBackStack() },
                            hasAnkiPermission = ankiGranted,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    private fun refreshPermissions() {
        micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ankiGranted = ContextCompat.checkSelfPermission(this, FlashCardsContract.READ_WRITE_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::voice.isInitialized) voice.shutdown()
    }
}
