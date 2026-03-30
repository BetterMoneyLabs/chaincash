package chaincash.contracts

import chaincash.offchain.SigUtils
import com.google.common.primitives.Longs
import scorex.crypto.encode.Base16
import scorex.crypto.hash.Blake2b256
import sigmastate.basics.CryptoConstants
import sigmastate.eval._
import sigmastate.serialization.GroupElementSerializer
import special.sigma.GroupElement

/**
 * Utility for creating Basis IOU notes with tracker signature.
 *
 * Uses Alice's address and secret (verified to match), Bob's secret for demo.
 * The tracker signature is included for normal redemption (without waiting for emergency period).
 *
 * Usage:
 *   sbt "runMain chaincash.contracts.BasisNoteCreator [amount_nanoERG]"
 */
object BasisNoteCreator extends App {

  val g: GroupElement = CryptoConstants.dlogGroup.generator

  // Alice's keys - public and secret from ParticipantKeys (verified to match)
  val alicePublicKey: GroupElement = ParticipantKeys.alicePublicKey
  val aliceSecret: BigInt = ParticipantKeys.aliceSecret

  // Bob's secret key (payee)
  val bobSecret: BigInt = ParticipantKeys.bobSecret
  val bobPublicKey: GroupElement = ParticipantKeys.bobPublicKey

  // Tracker's keys (verified to match tracker address)
  val trackerPublicKey: GroupElement = ParticipantKeys.trackerPublicKey
  val trackerSecret: BigInt = ParticipantKeys.trackerSecret

  case class IOUNote(
    payerKey: GroupElement,
    payeeKey: GroupElement,
    totalDebt: Long,
    timestamp: Long,
    signatureA: GroupElement,
    signatureZ: BigInt,
    message: Array[Byte]
  )

  case class TrackerSignature(
    signatureA: GroupElement,
    signatureZ: BigInt
  )

  def createNoteMessage(payerKey: GroupElement, payeeKey: GroupElement, totalDebt: Long, timestamp: Long): Array[Byte] = {
    Blake2b256(payerKey.getEncoded.toArray ++ payeeKey.getEncoded.toArray) ++ Longs.toByteArray(totalDebt) ++ Longs.toByteArray(timestamp)
  }

  def createNote(payerSecret: BigInt, payeeKey: GroupElement, totalDebt: Long, timestamp: Long): IOUNote = {
    val payerKey = g.exp(payerSecret.bigInteger)
    val message = createNoteMessage(payerKey, payeeKey, totalDebt, timestamp)
    val (a, z) = SigUtils.sign(message, payerSecret)
    IOUNote(payerKey, payeeKey, totalDebt, timestamp, a, z, message)
  }

  def createTrackerSignature(message: Array[Byte]): TrackerSignature = {
    val (a, z) = SigUtils.sign(message, trackerSecret)
    TrackerSignature(a, z)
  }

  def verifyNote(note: IOUNote): Boolean = {
    val message = createNoteMessage(note.payerKey, note.payeeKey, note.totalDebt, note.timestamp)
    SigUtils.verify(message, note.payerKey, note.signatureA, note.signatureZ)
  }

  def verifyTrackerSignature(message: Array[Byte], trackerSig: TrackerSignature): Boolean = {
    SigUtils.verify(message, trackerPublicKey, trackerSig.signatureA, trackerSig.signatureZ)
  }

  def formatNoteAsJson(note: IOUNote, trackerSig: TrackerSignature): String = {
    val payerKeyHex = Base16.encode(note.payerKey.getEncoded.toArray)
    val payeeKeyHex = Base16.encode(note.payeeKey.getEncoded.toArray)
    val sigAHex = Base16.encode(GroupElementSerializer.toBytes(note.signatureA))
    val trackerSigAHex = Base16.encode(GroupElementSerializer.toBytes(trackerSig.signatureA))
    s"""{
       |  "payerKey": "$payerKeyHex",
       |  "payeeKey": "$payeeKeyHex",
       |  "totalDebt": ${note.totalDebt},
       |  "totalDebtERG": ${note.totalDebt.toDouble / 1000000000},
       |  "timestamp": ${note.timestamp},
       |  "payerSignature": {"a": "$sigAHex", "z": "${note.signatureZ.toString(16)}"},
       |  "trackerSignature": {"a": "$trackerSigAHex", "z": "${trackerSig.signatureZ.toString(16)}"},
       |  "message": "${Base16.encode(note.message)}",
       |  "messageFormat": "key (32 bytes) || totalDebt (8 bytes) || timestamp (8 bytes)",
       |  "noteKey": "${Base16.encode(Blake2b256((payerKeyHex ++ payeeKeyHex).getBytes))}"
       |}""".stripMargin
  }

  def formatNoteHuman(note: IOUNote, trackerSig: TrackerSignature): String = {
    val payerShort = Base16.encode(note.payerKey.getEncoded.toArray).take(16) + "..."
    val payeeShort = Base16.encode(note.payeeKey.getEncoded.toArray).take(16) + "..."
    val trackerSigValid = verifyTrackerSignature(note.message, trackerSig)
    val timestampStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(note.timestamp))
    s"""IOU Note:
       |  Payer:           $payerShort
       |  Payee:           $payeeShort
       |  Amount:          ${note.totalDebt} nanoERG (${note.totalDebt.toDouble / 1000000000} ERG)
       |  Timestamp:       $timestampStr
       |  Payer Sig Valid: ${verifyNote(note)}
       |  Tracker Sig Valid: $trackerSigValid
       |""".stripMargin
  }

  val amount = if (args.length >= 1) args(0).toLong else 50000000L // default 0.05 ERG
  val timestamp = System.currentTimeMillis() // Current timestamp in milliseconds
  val note = createNote(aliceSecret, bobPublicKey, amount, timestamp)
  val trackerSig = createTrackerSignature(note.message)

  // Human-readable to stderr, JSON to stdout
  Console.err.println("=== Basis Note Creator ===")
  Console.err.println("Creates IOU note from Alice to Bob with tracker signature")
  Console.err.println()
  Console.err.println("=== Keys ===")
  Console.err.println(s"Alice Address: ${ParticipantKeys.aliceAddress}")
  Console.err.println(s"Alice Public:  ${ParticipantKeys.alicePublicKeyHex}")
  Console.err.println(s"Alice Secret:  ${ParticipantKeys.aliceSecret.toString(16)}")
  Console.err.println(s"Bob Secret:    ${ParticipantKeys.bobSecret.toString(16)}")
  Console.err.println(s"Bob Public:    ${ParticipantKeys.bobPublicKeyHex}")
  Console.err.println(s"Tracker Address: ${ParticipantKeys.trackerAddress}")
  Console.err.println(s"Tracker Public:  ${ParticipantKeys.trackerPublicKeyHex}")
  Console.err.println(s"Tracker Secret:  ${ParticipantKeys.trackerSecret.toString(16)}")
  Console.err.println()
  Console.err.println(formatNoteHuman(note, trackerSig))
  Console.err.println("=== JSON (stdout) ===")

  println(formatNoteAsJson(note, trackerSig))

  Console.err.println()
  Console.err.println("=== Usage ===")
  Console.err.println("Save note:  sbt \"runMain ...BasisNoteCreator\" > note.json")
  Console.err.println("Redeem:     sbt \"runMain ...BasisNoteRedeemer --note-json note.json --reserve-box <id>\"")
  Console.err.println()
  Console.err.println("Note: This note includes tracker signature for normal redemption.")
}
