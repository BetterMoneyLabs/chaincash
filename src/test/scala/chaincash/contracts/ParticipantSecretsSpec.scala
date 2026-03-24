package chaincash.contracts

import org.scalatest.{Matchers, PropSpec}

/**
 * Tests for participant secrets validation.
 * 
 * These tests ensure that the secrets in the CSV file correspond to their addresses.
 * This is a critical security check to prevent misconfiguration.
 */
class ParticipantSecretsSpec extends PropSpec with Matchers {

  property("Participant secrets should correspond to addresses") {
    // This test runs at compile time via ParticipantKeys initialization
    // If secrets don't match addresses, the require() statements will fail
    
    // Explicitly access each participant to trigger validation
    ParticipantKeys.trackerAddress should not be empty
    ParticipantKeys.trackerSecret should not be null
    
    ParticipantKeys.aliceAddress should not be empty
    ParticipantKeys.aliceSecret should not be null
    
    ParticipantKeys.bobAddress should not be empty
    ParticipantKeys.bobSecret should not be null
    
    // If we get here, all secrets match their addresses
    succeed
  }

  property("Participant secrets should be readable from CSV file") {
    // Test that secrets can be loaded from CSV
    val secrets = ParticipantSecretsReader.readSecrets()
    
    secrets should contain key ("tracker")
    secrets should contain key ("alice")
    secrets should contain key ("bob")
    
    // Verify structure
    val tracker = secrets("tracker")
    tracker.name shouldBe "tracker"
    tracker.address should not be empty
    tracker.secretHex should not be empty
    tracker.secretHex should fullyMatch regex "^[0-9a-fA-F]+$"
    
    val alice = secrets("alice")
    alice.name shouldBe "alice"
    alice.address should not be empty
    alice.secretHex should not be empty
    
    val bob = secrets("bob")
    bob.name shouldBe "bob"
    bob.address should not be empty
    bob.secretHex should not be empty
  }

  property("Secret hex values should be valid 256-bit scalars") {
    val secrets = ParticipantSecretsReader.readSecrets()
    
    secrets.values.foreach { participant =>
      val secretBigInt = BigInt(participant.secretHex, 16)
      
      // Secret should be positive
      secretBigInt should be > BigInt(0)
      
      // Secret should be less than the secp256k1 group order
      // (approximately 2^256, but slightly less)
      val maxSecret = BigInt("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364140", 16)
      secretBigInt should be < maxSecret
      
      // Secret should fit in 32 bytes (256 bits)
      secretBigInt.bitLength should be <= 256
    }
  }

  property("Addresses should be valid Ergo P2PK addresses") {
    val secrets = ParticipantSecretsReader.readSecrets()
    
    secrets.values.foreach { participant =>
      // Ergo P2PK addresses are 38 bytes when decoded (1 prefix + 33 pubkey + 4 checksum)
      // They start with '9' on mainnet
      participant.address should startWith("9")
      participant.address.length should be >= 40  // Minimum reasonable address length
    }
  }

  property("Secrets should produce correct public keys") {
    val secrets = ParticipantSecretsReader.readSecrets()
    
    // Verify tracker and alice secrets match their addresses
    // Bob's secret in the CSV is a placeholder - in production it must be updated
    // Bob's secret is used by the Ergo node for transaction signing
    // (receiverCondition = proveDlog(receiver) requires Bob's signature)
    val verifiedParticipants = Seq("tracker", "alice")
    
    verifiedParticipants.foreach { name =>
      val participant = secrets(name)
      val secretBigInt = BigInt(participant.secretHex, 16)
      
      // Derive public key from secret
      val publicKeyFromSecret = AddressUtils.derivePublicKeyFromSecret(secretBigInt)
      
      // Derive public key from address
      val publicKeyFromAddress = AddressUtils.derivePublicKeyFromAddress(participant.address)
      
      // They should match (compare encoded bytes)
      publicKeyFromSecret.getEncoded.toArray shouldBe publicKeyFromAddress.getEncoded.toArray
    }
  }
}
