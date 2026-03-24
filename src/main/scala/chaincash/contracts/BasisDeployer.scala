package chaincash.contracts

import org.ergoplatform.ErgoAddressEncoder
import org.ergoplatform.appkit.{ErgoValue, NetworkType}
import scorex.crypto.encode.Base16
import sigmastate.AvlTreeFlags
import sigmastate.Values.{AvlTreeConstant, GroupElementConstant}
import sigmastate.serialization.{GroupElementSerializer, ValueSerializer}
import special.sigma.AvlTree
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.PlasmaMap


/**
 * Utility for deploying Basis reserve contract on Ergo blockchain mainnet
 * Similar to DexySpec deployment pattern
 */
object BasisDeployer extends App {

  /**
   * Alice's public key derived from her Ergo address
   * In production, this would come from the wallet, not a hardcoded secret
   */
  val exampleOwnerKey: GroupElementConstant = {
    val alicePubKey = ParticipantKeys.alicePublicKey
    GroupElementConstant(alicePubKey)
  }

  // Example values - these should be replaced with actual values
  val exampleTrackerNftId = "8b1ab583bb085ecbd8fa9bc2fd59784afcdfce5496eb146bb3dd04664b56822a"
  val exampleReserveTokenId = "006e552382033bc8a362435bab079705cde40bec63cd5f96450e6bd70dc81409"

  // Network configuration
  val networkType = NetworkType.MAINNET
  val networkPrefix = networkType.networkPrefix
  val ergoAddressEncoder = new ErgoAddressEncoder(networkPrefix)

  // Basis contract configuration
  val basisContractScript = Constants.readContract("offchain/basis.es", Map.empty)

  val basisErgoTree = Constants.compile(basisContractScript)
  val basisAddress = Constants.getAddressFromErgoTree(basisErgoTree)

  val chainCashPlasmaParameters = PlasmaParameters(32, None)
  val InsertUpdate = AvlTreeFlags(insertAllowed = true, updateAllowed = true, removeAllowed = false)
  def emptyPlasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](InsertUpdate, chainCashPlasmaParameters)
  val emptyTreeErgoValue: ErgoValue[AvlTree] = emptyPlasmaMap.ergoValue
  val emptyTree: AvlTree = emptyTreeErgoValue.getValue

  /**
   * Creates deployment request for Basis reserve contract
   * @param ownerPublicKey GroupElement of the reserve owner
   * @param trackerNftId NFT token ID identifying the tracker (bytes)
   * @param reserveTokenId Singleton token ID for the reserve
   * @param initialCollateral Initial ERG collateral in nanoERG
   * @return JSON string for deployment request
   */
  def createBasisDeploymentRequest(
    ownerPublicKey: GroupElementConstant,
    trackerNftId: String,
    reserveTokenId: String,
    initialCollateral: Long = 100000000L // 0.1 ERG
  ): String = {

    // Encode registers
    val ownerKeyEncoded = Base16.encode(ValueSerializer.serialize(ownerPublicKey))
    val emptyTreeEncoded = Base16.encode(ValueSerializer.serialize(AvlTreeConstant(emptyTree)))
    val trackerNftBytes = Base16.decode(trackerNftId).get
    val trackerNftEncoded = Base16.encode(ValueSerializer.serialize(trackerNftBytes))

    s"""
      |[
      |  {
      |    "address": "${basisAddress.toString}",
      |    "value": $initialCollateral,
      |    "assets": [
      |      {
      |        "tokenId": "$reserveTokenId",
      |        "amount": 1
      |      }
      |    ],
      |    "registers": {
      |      "R4": "$ownerKeyEncoded",
      |      "R5": "$emptyTreeEncoded",
      |      "R6": "$trackerNftEncoded"
      |    }
      |  }
      |]
      |""".stripMargin
  }

  /**
   * Creates scan request for monitoring Basis reserve
   * @param reserveTokenId Singleton token ID for the reserve
   * @return JSON string for scan request
   */
  def createBasisScanRequest(reserveTokenId: String): String = {
    s"""
      |{
      |  "scanName": "Basis Reserve",
      |  "walletInteraction": "shared",
      |  "removeOffchain": true,
      |  "trackingRule": {
      |    "predicate": "containsAsset",
      |    "assetId": "$reserveTokenId"
      |  }
      |}
      |""".stripMargin
  }

  /**
   * Prints deployment information for Basis contract
   */
  def printDeploymentInfo(): Unit = {
    println("=== Basis Reserve Contract Deployment Information ===")
    println()

    println(s"Contract Address: ${basisAddress.toString}")
    println(s"Network: ${networkType.name}")
    println(s"Network Prefix: $networkPrefix")
    println()

    println("=== Alice's Key Information ===")
    println(s"Alice Address: ${ParticipantKeys.aliceAddress}")
    println(s"Alice Public Key (hex): ${ParticipantKeys.alicePublicKeyHex}")
    println()

    println("Contract Script:")
    println(basisContractScript)
    println()

    println("Deployment Instructions:")
    println("1. Issue a singleton NFT token for the reserve")
    println("2. Issue an NFT token for the tracker")
    println("3. Use createBasisDeploymentRequest() with owner public key, tracker NFT ID, and reserve NFT ID")
    println("4. Submit the deployment transaction to the Ergo blockchain")
    println("5. Use createBasisScanRequest() to monitor the reserve")
    println()
  }

  /**
   * Main method for testing and deployment
   */
  printDeploymentInfo()

  // Example usage
  println("=== Example Deployment Request ===")

  println("Example Scan Request:")
  println(createBasisScanRequest(exampleReserveTokenId))
  println()

  println("Example Deployment Request:")
  println(createBasisDeploymentRequest(exampleOwnerKey, exampleTrackerNftId, exampleReserveTokenId))
  println()

}

/**
 * Companion object for Basis contract constants and utilities
 */
object BasisConstants {
  
  // Action codes for Basis contract
  val REDEEM_ACTION: Byte = 0
  val TOP_UP_ACTION: Byte = 1

  // Minimum top-up amount (0.1 ERG)
  val MIN_TOP_UP_AMOUNT: Long = 100000000L

  // Emergency redemption time (3 days in blocks, assuming ~2.5 min per block)
  val EMERGENCY_REDEMPTION_TIME_IN_BLOCKS: Int = 3 * 720
}