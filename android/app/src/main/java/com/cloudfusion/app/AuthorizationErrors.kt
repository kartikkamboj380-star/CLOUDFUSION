package com.cloudfusion.app

/** No raw Intent contents or tokens in user-facing diagnostics. */
internal fun googleAuthorizationError(code: Int): String = when (code) {
    7 -> "Google authorization network error (code 7). Check connectivity and try again."
    8 -> "Google Play services reported an internal authorization error (code 8). This code alone does not identify the cause. Retry once; if it persists, compare Settings → Google developer setup with the existing Android OAuth client (installed package and SHA-1). Do not create a new project or remove accounts just for this error."
    10 -> "Google developer configuration error (code 10). Match this installed APK's package and SHA-1 with the Android OAuth client in Settings; enable Drive API in that same project."
    16, 12501 -> "Google consent was cancelled or did not finish (code $code). If you did not cancel, check the project's OAuth audience/test users and this APK's SHA-1. No account was connected."
    12500 -> "Google sign-in failed (code 12500). Check the OAuth client, consent screen, test users and Google Play services."
    else -> "Google Play services authorization failed (code $code). Check the Android OAuth package, signing SHA-1, Drive API and test-user setup."
}
