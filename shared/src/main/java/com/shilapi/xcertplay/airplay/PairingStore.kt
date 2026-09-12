package com.shilapi.xcertplay.airplay

/** In-memory store of paired controllers keyed by their long-term Ed25519 public key. */
class PairingStore {
    private val entries = HashMap<String, ByteArray>()

    fun save(identifier: String, longTermPublicKey: ByteArray) {
        entries[identifier] = longTermPublicKey.copyOf()
    }

    fun get(identifier: String): ByteArray? = entries[identifier]?.copyOf()

    fun clear() = entries.clear()
}
