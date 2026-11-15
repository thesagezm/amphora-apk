//
//  EnvironmentTest.kt
//  core
//
//  Proves the JVM environment the port relies on: Blowfish-ECB (the
//  legacy-auth cipher) is in the stock JCE — no third-party crypto.
//

package pandoracore

import org.junit.Assert.assertEquals
import org.junit.Test
import javax.crypto.Cipher

class EnvironmentTest {

    @Test
    fun `blowfish ECB cipher is available in the stock JCE`() {
        val cipher = Cipher.getInstance("Blowfish/ECB/NoPadding")
        // The JDK reports the requested transformation verbatim.
        assertEquals("Blowfish/ECB/NoPadding", cipher.algorithm)
        assertEquals(8, cipher.blockSize)
    }

    @Test
    fun `module carries the fixed internal package name`() {
        assertEquals("pandoracore", CoreInfo.name)
    }
}
