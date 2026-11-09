package com.cloudfusion.app

import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizationErrorsTest {
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
