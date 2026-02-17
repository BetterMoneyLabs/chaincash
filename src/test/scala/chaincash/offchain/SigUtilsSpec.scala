package chaincash.offchain

import org.scalatest.{Matchers, PropSpec}
import scorex.crypto.hash.Blake2b256
import sigmastate.basics.CryptoConstants
import sigmastate.eval._
import special.sigma.GroupElement

/**
 * Tests for SigUtils sign/verify roundtrip
 */
class SigUtilsSpec extends PropSpec with Matchers {

  val g = CryptoConstants.dlogGroup.generator

  property("SigUtils sign/verify should work for simple message") {
    val message = "Hello, World!".getBytes
    val secretKey = SigUtils.randBigInt
    val publicKey = g.exp(secretKey.bigInteger)

    val (a, z) = SigUtils.sign(message, secretKey)
    val isValid = SigUtils.verify(message, publicKey, a, z)

    isValid shouldBe true
  }

  property("SigUtils sign/verify should work for empty message") {
    val message = Array.emptyByteArray
    val secretKey = SigUtils.randBigInt
    val publicKey = g.exp(secretKey.bigInteger)

    val (a, z) = SigUtils.sign(message, secretKey)
    val isValid = SigUtils.verify(message, publicKey, a, z)

    isValid shouldBe true
  }

  property("SigUtils sign/verify should work for large message") {
    val message = Array.fill(10000)(42: Byte)
    val secretKey = SigUtils.randBigInt
    val publicKey = g.exp(secretKey.bigInteger)

    val (a, z) = SigUtils.sign(message, secretKey)
    val isValid = SigUtils.verify(message, publicKey, a, z)

    isValid shouldBe true
  }

  property("SigUtils verify should fail for wrong message") {
    val message1 = "Message 1".getBytes
    val message2 = "Message 2".getBytes
    val secretKey = SigUtils.randBigInt
    val publicKey = g.exp(secretKey.bigInteger)

    val (a, z) = SigUtils.sign(message1, secretKey)
    val isValid = SigUtils.verify(message2, publicKey, a, z)

    isValid shouldBe false
  }

  property("SigUtils verify should fail for wrong public key") {
    val message = "Test message".getBytes
    val secretKey1 = SigUtils.randBigInt
    val secretKey2 = SigUtils.randBigInt
    val publicKey1 = g.exp(secretKey1.bigInteger)
    val publicKey2 = g.exp(secretKey2.bigInteger)

    val (a, z) = SigUtils.sign(message, secretKey1)
    val isValid = SigUtils.verify(message, publicKey2, a, z)

    isValid shouldBe false
  }

  property("SigUtils verify should fail for tampered signature (a)") {
    val message = "Test message".getBytes
    val secretKey = SigUtils.randBigInt
    val publicKey = g.exp(secretKey.bigInteger)

    val (a, z) = SigUtils.sign(message, secretKey)
    val tamperedA = g.exp((z + 1).bigInteger) // Create invalid point
    val isValid = SigUtils.verify(message, publicKey, tamperedA, z)

    isValid shouldBe false
  }

  property("SigUtils verify should fail for tampered signature (z)") {
    val message = "Test message".getBytes
    val secretKey = SigUtils.randBigInt
    val publicKey = g.exp(secretKey.bigInteger)

    val (a, z) = SigUtils.sign(message, secretKey)
    val tamperedZ = z + 1
    val isValid = SigUtils.verify(message, publicKey, a, tamperedZ)

    isValid shouldBe false
  }

  property("SigUtils should produce verifiable signatures for same message and key") {
    val message = "Deterministic test".getBytes
    val secretKey = BigInt("1234567890123456789012345678901234567890")
    val publicKey = g.exp(secretKey.bigInteger)

    val (a1, z1) = SigUtils.sign(message, secretKey)
    val (a2, z2) = SigUtils.sign(message, secretKey)

    // Signatures are probabilistic (different r values), so a and z will differ
    // But both should verify correctly
    SigUtils.verify(message, publicKey, a1, z1) shouldBe true
    SigUtils.verify(message, publicKey, a2, z2) shouldBe true
    
    // The signatures should be different (probabilistic)
    (a1, z1) should not be ((a2, z2))
  }

  property("SigUtils should work with binary data containing all byte values") {
    val message = (0 to 255).map(_.toByte).toArray
    val secretKey = SigUtils.randBigInt
    val publicKey = g.exp(secretKey.bigInteger)

    val (a, z) = SigUtils.sign(message, secretKey)
    val isValid = SigUtils.verify(message, publicKey, a, z)

    isValid shouldBe true
  }

  property("SigUtils should work with Blake2b256 hash as message") {
    val data = "Some data to hash".getBytes
    val message = Blake2b256(data)
    val secretKey = SigUtils.randBigInt
    val publicKey = g.exp(secretKey.bigInteger)

    val (a, z) = SigUtils.sign(message, secretKey)
    val isValid = SigUtils.verify(message, publicKey, a, z)

    isValid shouldBe true
  }

  property("SigUtils multiple signatures with same key should all verify") {
    val secretKey = SigUtils.randBigInt
    val publicKey = g.exp(secretKey.bigInteger)
    val messages = Seq(
      "Message 1".getBytes,
      "Message 2".getBytes,
      "Message 3".getBytes,
      Array.emptyByteArray,
      Array.fill(1000)(99: Byte)
    )

    val signatures = messages.map(msg => SigUtils.sign(msg, secretKey))
    val results = messages.zip(signatures).map {
      case (msg, (a, z)) => SigUtils.verify(msg, publicKey, a, z)
    }

    results.forall(identity) shouldBe true
  }

  property("SigUtils should handle sequential sign/verify operations") {
    val secretKey = SigUtils.randBigInt
    val publicKey = g.exp(secretKey.bigInteger)

    // Sign and verify 100 times
    val results = (1 to 100).map { i =>
      val message = s"Message $i".getBytes
      val (a, z) = SigUtils.sign(message, secretKey)
      SigUtils.verify(message, publicKey, a, z)
    }

    results.forall(identity) shouldBe true
  }

}
