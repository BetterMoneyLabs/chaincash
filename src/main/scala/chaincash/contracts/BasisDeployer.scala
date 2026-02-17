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
   * Creates a GroupElementConstant from a hex-encoded public key
   * @param hexPublicKey Hex string representing the public key (compressed or uncompressed)
   * @return GroupElementConstant for use in Ergo contracts
   */
  def createOwnerKeyFromHex(hexPublicKey: String): GroupElementConstant = {
    val publicKeyBytes = Base16.decode(hexPublicKey).get
    val groupElement = GroupElementSerializer.fromBytes(publicKeyBytes)
    GroupElementConstant(groupElement)
  }

  /**
   * Example owner key using the specified public key
   */
  val exampleOwnerKey: GroupElementConstant = {
    createOwnerKeyFromHex("025ffe0e1a282fc0249320946af4209eb2dd7f250c16946fdd615533092e054bca") // Alice
  }

  // Example values - these should be replaced with actual values
  val exampleTrackerNftId = "dbfbbaf91a98c22204de3745e1986463620dcf3525ad566c6924cf9e976f86f8"
  val exampleReserveTokenId = "c7510cba80a9b7113e53968f7ff42ad250be2808fef1d36b71a89b0d644178c2"

  // Network configuration
  val networkType = NetworkType.MAINNET
  val networkPrefix = networkType.networkPrefix
  val ergoAddressEncoder = new ErgoAddressEncoder(networkPrefix)

  // Basis contract configuration
  val basisContractScript = Constants.readContract("offchain/basis.es", Map.empty)

  println("basis script: " + basisContractScript)

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
    initialCollateral: Long = 1000000000L // 1 ERG
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
  
  // Minimum top-up amount (1 ERG)
  val MIN_TOP_UP_AMOUNT: Long = 1000000000L
  
  // Emergency redemption time (3 days in blocks, assuming ~2.5 min per block)
  val EMERGENCY_REDEMPTION_TIME_IN_BLOCKS: Int = 3 * 720
  
  // Fee percentage for redemption (2%)
  val REDEMPTION_FEE_PERCENTAGE: Int = 2
  
  /**
   * Calculates redemption fee
   * @param amount Amount to redeem
   * @return Fee amount
   */
  def calculateRedemptionFee(amount: Long): Long = {
    (amount * REDEMPTION_FEE_PERCENTAGE) / 100
  }
  
  /**
   * Calculates net redemption amount after fees
   * @param amount Amount to redeem
   * @return Net amount after fees
   */
  def calculateNetRedemption(amount: Long): Long = {
    amount - calculateRedemptionFee(amount)
  }
}