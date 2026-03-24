package chaincash.contracts

import org.ergoplatform.ErgoAddressEncoder
import org.ergoplatform.P2PKAddress
import scorex.crypto.encode.{Base16, Base58}
import sigmastate.basics.CryptoConstants
import sigmastate.eval._
import sigmastate.serialization.GroupElementSerializer
import special.sigma.GroupElement

/**
 * Object for deriving public keys from Ergo addresses.
 *
 * P2PK addresses encode the public key directly in base58 format.
 * This utility extracts the public key from the address bytes.
 */
object AddressUtils {

  val MainnetEncoder: ErgoAddressEncoder = ErgoAddressEncoder.Mainnet

  /**
   * Extracts public key bytes from a P2PK address string.
   *
   * Uses ErgoAddressEncoder to parse the address properly instead of
   * manual byte-level operations.
   *
   * @param address Ergo P2PK address string
   * @return Raw public key bytes (33 bytes, compressed format)
   */
  def extractPublicKeyBytesFromAddress(address: String): Array[Byte] = {
    // Parse address using ErgoAddressEncoder which handles checksums and prefixes correctly
    val ergoAddress = MainnetEncoder.fromString(address).get
    
    // Get the proposition (public key) from the address
    // ErgoAddress stores the underlying proposition which contains the public key
    import sigmastate.Values._
    ergoAddress match {
      case p2pk: P2PKAddress =>
        p2pk.pubkey.value.getEncoded.toArray
      case other =>
        throw new IllegalArgumentException(s"Only P2PK addresses are supported, got: ${other.getClass.getSimpleName}")
    }
  }

  /**
   * Creates a GroupElement from an Ergo P2PK address string.
   * 
   * @param address Ergo P2PK address string (e.g., "9gk3HcH1x94ggvz7REMcoGRSBRGhEBgQuS9xx3bj51tQUSNt37m")
   * @return GroupElement representing the public key
   */
  def derivePublicKeyFromAddress(address: String): GroupElement = {
    val pubkeyBytes = extractPublicKeyBytesFromAddress(address)
    val ecPoint = GroupElementSerializer.fromBytes(pubkeyBytes)
    CGroupElement(ecPoint)
  }

  /**
   * Gets hex-encoded public key from an Ergo address.
   * 
   * @param address Ergo P2PK address string
   * @return Hex-encoded public key
   */
  def derivePublicKeyHex(address: String): String = {
    val pubkey = derivePublicKeyFromAddress(address)
    Base16.encode(pubkey.getEncoded.toArray)
  }

  /**
   * Derives public key from a secret key.
   * 
   * @param secretKey The secret scalar
   * @return GroupElement representing the public key
   */
  def derivePublicKeyFromSecret(secretKey: BigInt): GroupElement = {
    val g = CryptoConstants.dlogGroup.generator
    CGroupElement(g.exp(secretKey.bigInteger))
  }

  /**
   * Gets hex-encoded public key from a secret key.
   * 
   * @param secretKey The secret scalar
   * @return Hex-encoded public key
   */
  def derivePublicKeyHexFromSecret(secretKey: BigInt): String = {
    val pubkey = derivePublicKeyFromSecret(secretKey)
    Base16.encode(pubkey.getEncoded.toArray)
  }

  /**
   * Verifies that a secret key corresponds to an address.
   *
   * @param address Ergo P2PK address string
   * @param secretKey The secret scalar to verify
   * @return true if the secret key's public key matches the address
   */
  def verifySecretMatchesAddress(address: String, secretKey: BigInt): Boolean = {
    val addressPubKeyHex = derivePublicKeyHex(address)
    val secretPubKeyHex = derivePublicKeyHexFromSecret(secretKey)
    addressPubKeyHex == secretPubKeyHex
  }

  /**
   * Derives ergoTree bytes from a P2PK address string.
   *
   * @param address Ergo P2PK address string
   * @return Hex-encoded ergoTree bytes
   */
  def deriveErgoTreeFromAddress(address: String): String = {
    val ergoTree = ErgoAddressEncoder.Mainnet.fromString(address).get.script
    Base16.encode(ergoTree.bytes)
  }

  /**
   * Derives ergoTree bytes from a contract ErgoTree.
   *
   * @param ergoTree The compiled ErgoTree
   * @return Hex-encoded ergoTree bytes
   */
  def deriveErgoTreeFromContract(ergoTree: sigmastate.Values.ErgoTree): String = {
    Base16.encode(ergoTree.bytes)
  }
}

/**
 * Constants for known participants in the Basis system.
 *
 * Public keys are derived from Ergo addresses at runtime.
 * Secrets are loaded from secrets/participants.csv (or participants.local.csv).
 * The secrets file is git-ignored and must be created from participants.csv.template.
 */
object ParticipantKeys {

  // Load secrets from CSV file
  private val secrets = ParticipantSecretsReader.readSecrets()

  private def getSecret(name: String): BigInt = {
    secrets.get(name) match {
      case Some(p) => BigInt(p.secretHex, 16)
      case None => throw new IllegalArgumentException(
        s"Secret for '$name' not found in secrets file. " +
        s"Available participants: ${secrets.keys.mkString(", ")}"
      )
    }
  }

  private def getAddress(name: String): String = {
    secrets.get(name) match {
      case Some(p) => p.address
      case None => throw new IllegalArgumentException(
        s"Address for '$name' not found in secrets file. " +
        s"Available participants: ${secrets.keys.mkString(", ")}"
      )
    }
  }

  /**
   * Tracker's Ergo address and secret (mainnet)
   * The secret is verified to correspond to the address.
   */
  val trackerAddress: String = getAddress("tracker")
  val trackerSecret: BigInt = getSecret("tracker")

  // Verify that tracker's secret corresponds to its address
  require(
    AddressUtils.verifySecretMatchesAddress(trackerAddress, trackerSecret),
    s"Tracker's secret does not correspond to address $trackerAddress"
  )

  /**
   * Alice's (reserve owner) Ergo address and secret (mainnet)
   * The secret is verified to correspond to the address.
   */
  val aliceAddress: String = getAddress("alice")
  val aliceSecret: BigInt = getSecret("alice")

  // Verify that Alice's secret corresponds to her address
  require(
    AddressUtils.verifySecretMatchesAddress(aliceAddress, aliceSecret),
    s"Alice's secret does not correspond to address $aliceAddress"
  )

  /**
   * Bob's Ergo address and secret (for testing/demo purposes only)
   *
   * Note: The secret is NOT used for signing (Bob is the payee/receiver).
   * It's kept for reference only. Public key is derived from the address.
   */
  val bobAddress: String = getAddress("bob")
  val bobSecret: BigInt = getSecret("bob")

  /**
   * Tracker's public key derived from address
   */
  lazy val trackerPublicKey: GroupElement = 
    AddressUtils.derivePublicKeyFromAddress(trackerAddress)

  /**
   * Alice's public key derived from address
   */
  lazy val alicePublicKey: GroupElement = 
    AddressUtils.derivePublicKeyFromAddress(aliceAddress)

  /**
   * Bob's public key derived from address
   */
  lazy val bobPublicKey: GroupElement =
    AddressUtils.derivePublicKeyFromAddress(bobAddress)

  /**
   * Bob's ergoTree derived from address (P2PK)
   */
  lazy val bobErgoTree: String = AddressUtils.deriveErgoTreeFromAddress(bobAddress)

  /**
   * Reserve (Basis) ergoTree derived from compiled contract
   */
  lazy val reserveErgoTree: String = AddressUtils.deriveErgoTreeFromContract(Constants.basisErgoTree)

  /**
   * Reserve (Basis) address derived from ergoTree
   */
  lazy val reserveAddress: String = Constants.basisAddress.toString

  /**
   * Tracker's public key as hex string
   */
  lazy val trackerPublicKeyHex: String = 
    Base16.encode(trackerPublicKey.getEncoded.toArray)

  /**
   * Alice's public key as hex string
   */
  lazy val alicePublicKeyHex: String = 
    Base16.encode(alicePublicKey.getEncoded.toArray)

  /**
   * Bob's public key as hex string
   */
  lazy val bobPublicKeyHex: String = 
    Base16.encode(bobPublicKey.getEncoded.toArray)

  /**
   * Print all participant information
   */
  def printParticipantInfo(): Unit = {
    println("=== Participant Keys ===")
    println()
    println("Tracker:")
    println(s"  Address: $trackerAddress")
    println(s"  Public Key: $trackerPublicKeyHex")
    println(s"  Secret: ${trackerSecret.toString(16)}")
    println(s"  (Secret verified to match address)")
    println()
    println("Alice (Reserve Owner):")
    println(s"  Address: $aliceAddress")
    println(s"  Public Key: $alicePublicKeyHex")
    println(s"  Secret: ${aliceSecret.toString(16)}")
    println(s"  (Secret verified to match address)")
    println()
    println("Bob (Payee):")
    println(s"  Address: $bobAddress")
    println(s"  Secret: ${bobSecret.toString(16)}")
    println(s"  Public Key: $bobPublicKeyHex")
    println()
  }
}
