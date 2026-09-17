package com.shilapi.xcertplay.mfi

import com.shilapi.xcertplay.transport.I2cTransport
import com.shilapi.xcertplay.transport.I2cTransportException
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** Transport-independent MFi presence check for the documented address candidates. */
class MfiSelfCheck(
    private val transport: I2cTransport,
) {
    fun run(): MfiSelfCheckResult {
        val discovery = MfiDeviceScanner(transport).scan()
        val chip = discovery.chip ?: return MfiSelfCheckResult(discovery, null)
        val client = MfiAuthenticationClient(transport, chip.address7Bit)
        return MfiSelfCheckResult(
            discovery = discovery,
            chip = MfiSelfCheckChip(
                address7Bit = chip.address7Bit,
                deviceVersion = chip.deviceVersion,
                protocolMajor = try {
                    MfiProtocolMajorResult.Value(
                        chip.address7Bit,
                        client.protocolMajor(),
                    )
                } catch (error: MfiException) {
                    MfiProtocolMajorResult.MfiFailure(chip.address7Bit, error)
                } catch (error: I2cTransportException) {
                    MfiProtocolMajorResult.TransportFailure(chip.address7Bit, error)
                },
                certificate = inspectCertificate(client),
            ),
        )
    }

    private fun inspectCertificate(client: MfiAuthenticationClient): MfiCertificateResult {
        return try {
        val payload = client.readCertificate()
        val certificates = CertificateFactory.getInstance("X.509")
            .generateCertificates(ByteArrayInputStream(payload))
            .filterIsInstance<X509Certificate>()
        val leaf = certificates.firstOrNull()
            ?: return MfiCertificateResult.Invalid("Certificate package contains no X.509 certificate")
        MfiCertificateResult.Value(
            packageLength = payload.size,
            packageSha256 = MessageDigest.getInstance("SHA-256").digest(payload).toHex(),
            certificateCount = certificates.size,
            subject = leaf.subjectX500Principal.name,
            issuer = leaf.issuerX500Principal.name,
            serialHex = leaf.serialNumber.toString(16).uppercase(),
            notBeforeMillis = leaf.notBefore.time,
            notAfterMillis = leaf.notAfter.time,
            signatureAlgorithm = leaf.sigAlgName,
            publicKeyAlgorithm = leaf.publicKey.algorithm,
            publicKeyBits = when (val key = leaf.publicKey) {
                is ECPublicKey -> key.params.curve.field.fieldSize
                is RSAPublicKey -> key.modulus.bitLength()
                else -> key.encoded.size * 8
            },
        )
        } catch (error: MfiException) {
            MfiCertificateResult.Failure(error.message ?: error.javaClass.simpleName)
        } catch (error: I2cTransportException) {
            MfiCertificateResult.Failure(error.message ?: error.javaClass.simpleName)
        } catch (error: Exception) {
            MfiCertificateResult.Invalid(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }
}

data class MfiSelfCheckResult(
    val discovery: MfiDiscoveryResult,
    val chip: MfiSelfCheckChip?,
)

data class MfiSelfCheckChip(
    val address7Bit: Int,
    val deviceVersion: Int,
    val protocolMajor: MfiProtocolMajorResult,
    val certificate: MfiCertificateResult,
)

sealed class MfiCertificateResult {
    data class Value(
        val packageLength: Int,
        val packageSha256: String,
        val certificateCount: Int,
        val subject: String,
        val issuer: String,
        val serialHex: String,
        val notBeforeMillis: Long,
        val notAfterMillis: Long,
        val signatureAlgorithm: String,
        val publicKeyAlgorithm: String,
        val publicKeyBits: Int,
    ) : MfiCertificateResult()

    data class Failure(val message: String) : MfiCertificateResult()

    data class Invalid(val message: String) : MfiCertificateResult()
}

sealed class MfiProtocolMajorResult {
    abstract val address7Bit: Int

    data class Value(override val address7Bit: Int, val major: Int) : MfiProtocolMajorResult()

    data class MfiFailure(override val address7Bit: Int, val error: MfiException) : MfiProtocolMajorResult()

    data class TransportFailure(
        override val address7Bit: Int,
        val error: I2cTransportException,
    ) : MfiProtocolMajorResult()
}
