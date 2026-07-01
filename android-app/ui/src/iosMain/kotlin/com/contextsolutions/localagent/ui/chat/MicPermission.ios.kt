package com.contextsolutions.localagent.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS [MicPermission] actual (Phase 2): dictation via `SFSpeechRecognizer` + the mic.
 * [available] is true when a recognizer exists for the current locale; [granted]
 * reflects whether BOTH the microphone (`AVAudioSession`) and speech-recognition
 * (`SFSpeechRecognizer`) authorizations are currently held; [request] prompts for
 * both (mic first, then speech) and reports the combined grant on the main thread.
 *
 * Requires the `NSMicrophoneUsageDescription` + `NSSpeechRecognitionUsageDescription`
 * Info.plist keys — iOS crashes the prompt without them.
 */
@Composable
actual fun rememberMicPermission(): MicPermission = remember {
    object : MicPermission {
        // Speech recognition + mic capture are available on all supported iPhones; the
        // actual usability is gated by [granted] (the runtime authorizations).
        override val available: Boolean = true

        override val granted: Boolean
            get() = micGranted() && speechGranted()

        override fun request(onResult: (Boolean) -> Unit) {
            AVAudioSession.sharedInstance().requestRecordPermission { micOk ->
                if (!micOk) {
                    mainThread { onResult(false) }
                    return@requestRecordPermission
                }
                SFSpeechRecognizer.requestAuthorization { status ->
                    val ok = status == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized
                    mainThread { onResult(ok) }
                }
            }
        }
    }
}

private fun micGranted(): Boolean =
    AVAudioSession.sharedInstance().recordPermission == AVAudioSessionRecordPermissionGranted

private fun speechGranted(): Boolean =
    currentSpeechStatus() == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized

private fun currentSpeechStatus(): SFSpeechRecognizerAuthorizationStatus =
    SFSpeechRecognizer.authorizationStatus()

private fun mainThread(block: () -> Unit) {
    dispatch_async(dispatch_get_main_queue()) { block() }
}
