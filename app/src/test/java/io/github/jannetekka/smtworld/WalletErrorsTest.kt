package io.github.jannetekka.smtworld

import com.solana.mobilewalletadapter.clientlib.protocol.JsonRpc20Client
import io.github.jannetekka.smtworld.solana.WalletErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ExecutionException

class WalletErrorsTest {
    // MWA clientlib-ktx 2.0.8's own failure strings for the wallet hand-off.
    private val timedOut = "Timed out waiting for local association to be ready"
    private val failed = "Failed establishing local association with wallet"
    private val interrupted = "Interrupted while waiting for local association to be ready"
    private val cancelled = "Local association was cancelled before connected"

    @Test fun walletThatNeverConnectsSaysUnlockIt() {
        for (m in listOf(timedOut, failed)) {
            assertTrue(WalletErrors.isAssociationFailure(m))
            assertTrue(WalletErrors.friendly(m).startsWith("Couldn't reach the wallet"))
        }
    }

    @Test fun backingOutOfTheWalletIsACancel() {
        for (m in listOf(interrupted, cancelled, "User did not authorize signing"))
            assertEquals("Cancelled in the wallet. Nothing was sent.", WalletErrors.friendly(m))
    }

    @Test fun unknownErrorsPassThrough() {
        assertEquals("Remote exception", WalletErrors.friendly("Remote exception"))
        assertFalse(WalletErrors.isAssociationFailure("Remote exception"))
    }

    @Test fun detailKeepsTheLibrarysWordsAndTheCause() {
        val e = ExecutionException("Scenario closed while connecting", IllegalStateException("Scenario closed while connecting"))
        assertEquals("$failed · Scenario closed while connecting", WalletErrors.detail(failed, e))
        assertEquals(timedOut, WalletErrors.detail(timedOut, null))
        assertEquals("$timedOut · TimeoutException", WalletErrors.detail(timedOut, java.util.concurrent.TimeoutException()))
    }

    // What Jannet's phone showed on 12 Oct (v0.3.1): Solflare answered -1 inside MWA's
    // "Failed establishing local association" wrapper.
    private fun walletSaid(code: Int, text: String) =
        ExecutionException(JsonRpc20Client.JsonRpc20RemoteException(code, text, null))

    @Test fun aRefusedSavedSessionIsAnAuthFailureNotAReachFailure() {
        val e = walletSaid(-1, "authorization request failed")
        assertTrue(WalletErrors.isAuthFailure(failed, e))
        assertTrue(WalletErrors.friendly(failed, e).startsWith("The wallet didn't authorize this app"))
        assertTrue(WalletErrors.isAuthFailure("Auth token invalid", null))
        assertFalse(WalletErrors.isAuthFailure(failed, null))
        assertFalse(WalletErrors.isAuthFailure(timedOut, java.util.concurrent.TimeoutException()))
        assertTrue(WalletErrors.detail(failed, e).contains("authorization request failed"))
    }

    @Test fun aWalletOnTheWrongClusterSaysSwitchToDevnet() {
        assertEquals("Your wallet isn't on Devnet. Switch it to Devnet, then tap Clock in again.",
            WalletErrors.friendly(failed, walletSaid(-7, "cluster not supported")))
    }
}
