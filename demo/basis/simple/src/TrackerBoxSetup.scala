package chaincash.contracts

import chaincash.contracts.Constants.chainCashPlasmaParameters
import com.google.common.primitives.Longs
import scorex.crypto.hash.Blake2b256
import scorex.util.encode.Base16
import sigmastate.AvlTreeFlags
import sigmastate.Values.AvlTreeConstant
import sigmastate.serialization.ValueSerializer
import special.sigma.AvlTree
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.PlasmaMap

/**
 * Utility to create tracker box setup JSON for /wallet/payment/send API
 *
 * ## What is a Tracker Box?
 *
 * A tracker box is an on-chain box that holds:
 * - Tracker's public key (R4 register)
 * - AVL tree digest of all debt relationships (R5 register)
 * - Tracker NFT token (identifies this as the official tracker)
 *
 * The tracker box serves as a commitment to the offchain debt state.
 * When the tracker goes offline, users can redeem against the last
 * committed state in this box.
 *
 * ## Tracker Tree Structure
 *
 * The AVL tree in the tracker box stores debt relationships:
 * - Key: Blake2b256(payerPublicKey || payeePublicKey) (32 bytes)
 * - Value: totalDebt as Long (8 bytes, big-endian)
 *
 * This allows efficient lookup and proof generation for any debt pair.
 *
 * ## How This Utility Works
 *
 * 1. Creates an AVL tree with initial debt entries (for demo: Alice->Bob)
 * 2. Generates JSON for creating tracker box via Ergo node API
 * 3. Outputs can be submitted to /wallet/payment/send endpoint
 *
 * ## Usage Flow
 *
 * 1. Run this utility to generate tracker box JSON
 * 2. Submit JSON to Ergo node to create tracker box
 * 3. Tracker box is scanned with scanId=36
 * 4. Tracker service uses this box for state commitments
 *
 * Reference code for tracker tree calculation:
 * - BasisNoteRedeemer.generateTrackerAvlProof() - generates proofs for redemption
 * - BasisSpec.mkTrackerTreeAndProof() - test code for tree creation
 * - basis.es contract - on-chain verification of tracker proofs
 *
 * Usage:
 *   sbt "runMain chaincash.contracts.TrackerBoxSetup"
 *
 * The output JSON can be submitted to:
 *   POST /wallet/payment/send
 *
 * See contracts/offchain/tracker.md for detailed tracker architecture.
 */
object TrackerBoxSetup extends App {

  // Tracker contract parameters (must match basis.es contract)
  val InsertOnly = AvlTreeFlags(insertAllowed = true, updateAllowed = false, removeAllowed = false)

  // Participant keys from ParticipantKeys object
  val alicePublicKeyHex = ParticipantKeys.alicePublicKeyHex
  val bobPublicKeyHex = ParticipantKeys.bobPublicKeyHex
  val trackerPublicKeyHex = ParticipantKeys.trackerPublicKeyHex

  // Tracker NFT ID (this must be issued beforehand)
  // Use the actual tracker NFT from your setup
  val trackerNftId = "8b1ab583bb085ecbd8fa9bc2fd59784afcdfce5496eb146bb3dd04664b56822a"

  // Debt amount (example: 50,000,000 nanoERG for testing)
  val totalDebt = 50000000L

  /**
   * Creates the tracker AVL tree with a single Alice->Bob debt entry.
   *
   * ## Tree Structure
   *
   * The tracker tree stores debt relationships as key-value pairs:
   * - Key: Blake2b256(payerPublicKey || payeePublicKey) (32 bytes)
   * - Value: totalDebt as Long (8 bytes, big-endian)
   *
   * ## Steps
   *
   * 1. Create empty PlasmaMap with InsertOnly flags
   * 2. Compute debt key = Blake2b256(alicePubKey || bobPubKey)
   * 3. Insert (debtKey, totalDebt) into the map
   * 4. Get the ergoValue (AvlTree) which contains the digest
   *
   * The resulting tree can be used to:
   * - Initialize a new tracker box
   * - Generate proofs for redemption (see BasisNoteRedeemer.generateTrackerAvlProof)
   * - Verify debt existence on-chain
   *
   * Reference: BasisNoteRedeemer.generateTrackerAvlProof() 
   *
   * @param alicePubKeyHex Alice's public key in hex (payer/debtor)
   * @param bobPubKeyHex Bob's public key in hex (payee/creditor)
   * @param totalDebt Total debt amount in nanoERG
   * @return AvlTree containing the debt entry
   */
  def createTrackerTree(alicePubKeyHex: String, bobPubKeyHex: String, totalDebt: Long): AvlTree = {
    // Create empty PlasmaMap
    val plasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](InsertOnly, chainCashPlasmaParameters)

    // Decode public keys from hex
    val alicePubKeyBytes = Base16.decode(alicePubKeyHex).get
    val bobPubKeyBytes = Base16.decode(bobPubKeyHex).get

    // Create the debt key: Blake2b256(alicePubKey || bobPubKey)
    val debtKey = Blake2b256(alicePubKeyBytes ++ bobPubKeyBytes)

    // Insert the debt record into the tree
    plasmaMap.insert((debtKey, Longs.toByteArray(totalDebt)))

    // Return the AvlTree (this contains the digest of the single entry)
    plasmaMap.ergoValue.getValue
  }

  /**
   * Creates the tracker box setup JSON for /wallet/payment/send API
   * 
   * @param trackerPubKeyHex Tracker's public key in hex (with 03 compression prefix)
   * @param trackerTree The AVL tree containing debt entries
   * @param trackerNftId The tracker NFT token ID
   * @param trackerValue The ERG value for the tracker box (default: 0.01 ERG)
   * @return JSON string for payment request
   */
  def createTrackerBoxSetupJson(
    trackerPubKeyHex: String,
    trackerTree: AvlTree,
    trackerNftId: String,
    trackerValue: Long = 10000000L // 0.01 ERG
  ): String = {
    // Encode the tracker public key as GroupElement
    val trackerPubKeyEncoded = Base16.encode(ValueSerializer.serialize(
      sigmastate.Values.GroupElementConstant(
        sigmastate.serialization.GroupElementSerializer.fromBytes(
          Base16.decode(trackerPubKeyHex).get
        )
      )
    ))

    // Encode the AVL tree
    val trackerTreeEncoded = Base16.encode(ValueSerializer.serialize(
      AvlTreeConstant(trackerTree)
    ))

    // Create the payment request JSON
    s"""[
       |  {
       |    "address": "9f7ZXamnfaDZL7EWLKLuBZgWMuHCusQYK6yow2d7p2eES9oRRRe",
       |    "value": $trackerValue,
       |    "assets": [
       |      {
       |        "tokenId": "$trackerNftId",
       |        "amount": 1
       |      }
       |    ],
       |    "registers": {
       |      "R4": "$trackerPubKeyEncoded",
       |      "R5": "$trackerTreeEncoded"
       |    }
       |  }
       |]""".stripMargin
  }

  /**
   * Prints detailed information about the tracker tree
   */
  def printTrackerTreeInfo(alicePubKeyHex: String, bobPubKeyHex: String, totalDebt: Long): Unit = {
    val alicePubKeyBytes = Base16.decode(alicePubKeyHex).get
    val bobPubKeyBytes = Base16.decode(bobPubKeyHex).get
    val debtKey = Blake2b256(alicePubKeyBytes ++ bobPubKeyBytes)

    println("=== Tracker Tree Setup Information ===")
    println()
    println("Debt Entry:")
    println(s"  Alice (payer) public key:  $alicePublicKeyHex")
    println(s"  Bob (payee) public key:    $bobPublicKeyHex")
    println(s"  Debt key (Blake2b256):     ${Base16.encode(debtKey)}")
    println(s"  Total debt:                $totalDebt nanoERG")
    println(s"  Total debt (serialized):   ${Base16.encode(Longs.toByteArray(totalDebt))}")
    println()
  }

  // Main execution
  println("=== Tracker Box Setup Generator ===")
  println()

  // Print debt entry information
  printTrackerTreeInfo(alicePublicKeyHex, bobPublicKeyHex, totalDebt)

  // Create the tracker tree with single Alice->Bob entry
  val trackerTree = createTrackerTree(alicePublicKeyHex, bobPublicKeyHex, totalDebt)

  println("Tracker AVL Tree:")
  println(s"  Tree digest: ${Base16.encode(trackerTree.digest.toArray)}")
  println(s"  Key length:  32 bytes (Blake2b256)")
  println(s"  Value length: 8 bytes (Long)")
  println()

  // Generate the tracker box setup JSON
  val trackerBoxJson = createTrackerBoxSetupJson(
    trackerPublicKeyHex,
    trackerTree,
    trackerNftId
  )

  println("=== Tracker Box Setup JSON for /wallet/payment/send ===")
  println()
  println(trackerBoxJson)
  println()

  println("=== Usage Instructions ===")
  println()
  println("1. Submit this JSON to the Ergo node:")
  println("   curl -X POST http://localhost:9053/wallet/payment/send \\")
  println("     -H 'Content-Type: application/json' \\")
  println("     -H 'api_key: <your-api-key>' \\")
  println("     -d '<paste-json-above>'")
  println()
  println("2. The tracker box will be created with scanId=36 (if scan is configured)")
  println()
  println("3. Verify the tracker box was created:")
  println("   curl -X GET 'http://localhost:9053/scan/unspentBoxes/36?limit=1' \\")
  println("     -H 'api_key: <your-api-key>'")
  println()

  // Also save to file
  val outputFile = "tracker_box_setup.json"
  val writer = new java.io.FileWriter(outputFile)
  try {
    writer.write(trackerBoxJson)
    println(s"Tracker box setup saved to: $outputFile")
  } finally {
    writer.close()
  }
}
