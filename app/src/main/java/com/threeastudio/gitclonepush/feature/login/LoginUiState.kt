package com.threeastudio.gitclonepush.feature.login

enum class LoginPhase { IDLE, PREPARING, AWAITING_AUTHORIZATION, COMPLETING, CANCELLING }

data class LoginUiState(val phase: LoginPhase = LoginPhase.IDLE, val errorMessage: String? = null) {
    val isSigningIn: Boolean get() = phase != LoginPhase.IDLE
    val progressMessage: String? get() = when (phase) {
        LoginPhase.IDLE -> null
        LoginPhase.PREPARING -> "Opening GitHub sign-in…"
        LoginPhase.AWAITING_AUTHORIZATION -> "Complete sign-in in your browser. Waiting for GitHub…"
        LoginPhase.COMPLETING -> "Completing GitHub sign-in and loading your profile…"
        LoginPhase.CANCELLING -> "Cancelling sign-in…"
    }
}
