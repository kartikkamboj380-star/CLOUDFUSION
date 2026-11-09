package com.cloudfusion.app

import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizationErrorsTest {
    @Test fun internalErrorDoesNotPretendToDiagnoseOrFixOAuthConfiguration() {
        val message = googleAuthorizationError(8)
        assertTrue(message.contains("internal authorization error (code 8)"))
        assertTrue(message.contains("does not identify the cause"))
        assertTrue(message.contains("existing Android OAuth client"))
        assertTrue(message.contains("Do not create a new project or remove accounts"))
    }
    @Test fun developerErrorPointsToInstalledCertificate() {
        assertTrue(googleAuthorizationError(10).contains("installed APK"))
        assertTrue(googleAuthorizationError(10).contains("SHA-1"))
    }
    @Test fun cancellationIsNotMislabelledAsSuccessfulAuthorization() {
        assertTrue(googleAuthorizationError(16).contains("did not finish"))
        assertTrue(googleAuthorizationError(12501).contains("No account was connected"))
    }
    @Test fun networkAndUnknownErrorsRetainCodes() {
        assertTrue(googleAuthorizationError(7).contains("network"))
        assertTrue(googleAuthorizationError(999).contains("999"))
    }
}
