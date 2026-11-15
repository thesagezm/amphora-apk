//
//  PandoraCryptoSmoke.kt
//  core
//
//  A0 skeleton placeholder proving the loop (JVM tests green locally and
//  in CI) and the one crypto fact the whole A1 port depends on: the stock
//  JVM/JCE ships Blowfish-ECB, so the iOS CryptoSwift dependency has no
//  Android equivalent to install. Replaced by the real PandoraCrypto port
//  in A1.
//

package pandoracore

object CoreInfo {
    const val name = "pandoracore"
}
