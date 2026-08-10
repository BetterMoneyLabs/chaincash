package chaincash.contracts

import chaincash.offchain.SigUtils._
import org.ergoplatform.ErgoAddressEncoder
import org.ergoplatform.P2PKAddress
import scorex.crypto.encode.Base16
import sigma.crypto.CryptoConstants
import sigma.serialization.GroupElementSerializer
import sigma.GroupElement

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
    import sigma.ast._
    ergoAddress match {
      case p2pk: P2PKAddress =>
        GroupElementSerializer.toBytes(p2pk.pubkey.value)
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
    GroupElementSerializer.fromBytes(extractPublicKeyBytesFromAddress(address))
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
    CryptoConstants.dlogGroup.generator.exp(secretKey.bigInteger)
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
  def deriveErgoTreeFromContract(ergoTree: sigma.ast.ErgoTree): String = {
    Base16.encode(ergoTree.bytes)
  }
}
