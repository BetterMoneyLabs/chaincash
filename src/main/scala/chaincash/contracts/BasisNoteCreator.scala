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
 * ## How It Works
 *
 * 1. Alice creates an IOU note representing debt from Alice to Bob
 * 2. Alice signs the note with her secret key
 * 3. Tracker signs the note to certify it's witnessed in the tracker's state
 * 4. The signed note can be redeemed against Alice's reserve (if exists) or held as credit
 *
 * ## Note Structure
 *
 * An IOU note contains:
 * - payerKey: Public key of the debtor (Alice)
 * - payeeKey: Public key of the creditor (Bob)
 * - totalDebt: Total amount owed in nanoERG
 * - signature: Alice's signature on (payerKey || payeeKey || totalDebt)
 * - trackerSignature: Tracker's signature certifying the note is witnessed
 *
 * ## Tracker's Role
 *
 * The tracker signature serves as a witness that:
 * - The note is included in the tracker's debt state
 * - The debt does not violate collateralization of previous notes
 * - The tracker will commit this state to the blockchain
 *
 * The tracker cannot steal funds - it only certifies inclusion. Redemption still
 * requires the payer's signature and valid AVL proof against tracker's committed state.
 *
 * ## Emergency Exit
 *
 * If the tracker goes offline, notes can still be redeemed against the last
 * committed state on the blockchain (after emergency period expires).
 *
 * Usage:
 *   sbt "runMain chaincash.contracts.BasisNoteCreator [amount_nanoERG]"
 *
 * See contracts/offchain/tracker.md and contracts/offchain/basis.md for more details.
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
    signatureA: GroupElement,
    signatureZ: BigInt,
    message: Array[Byte]
  )

  case class TrackerSignature(
    signatureA: GroupElement,
    signatureZ: BigInt
  )

  def createNoteMessage(payerKey: GroupElement, payeeKey: GroupElement, totalDebt: Long): Array[Byte] = {
    Blake2b256(payerKey.getEncoded.toArray ++ payeeKey.getEncoded.toArray) ++ Longs.toByteArray(totalDebt)
  }

  def createNote(payerSecret: BigInt, payeeKey: GroupElement, totalDebt: Long): IOUNote = {
    val payerKey = g.exp(payerSecret.bigInteger)
    val message = createNoteMessage(payerKey, payeeKey, totalDebt)
    val (a, z) = SigUtils.sign(message, payerSecret)
    IOUNote(payerKey, payeeKey, totalDebt, a, z, message)
  }

  def createTrackerSignature(message: Array[Byte]): TrackerSignature = {
    val (a, z) = SigUtils.sign(message, trackerSecret)
    TrackerSignature(a, z)
  }

  def verifyNote(note: IOUNote): Boolean = {
    val message = createNoteMessage(note.payerKey, note.payeeKey, note.totalDebt)
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
       |  "payerSignature": {"a": "$sigAHex", "z": "${note.signatureZ.toString(16)}"},
       |  "trackerSignature": {"a": "$trackerSigAHex", "z": "${trackerSig.signatureZ.toString(16)}"},
       |  "message": "${Base16.encode(note.message)}",
       |  "noteKey": "${Base16.encode(Blake2b256((payerKeyHex ++ payeeKeyHex).getBytes))}"
       |}""".stripMargin
  }

  def formatNoteHuman(note: IOUNote, trackerSig: TrackerSignature): String = {
    val payerShort = Base16.encode(note.payerKey.getEncoded.toArray).take(16) + "..."
    val payeeShort = Base16.encode(note.payeeKey.getEncoded.toArray).take(16) + "..."
    val trackerSigValid = verifyTrackerSignature(note.message, trackerSig)
    s"""IOU Note:
       |  Payer:           $payerShort
       |  Payee:           $payeeShort
       |  Amount:          ${note.totalDebt} nanoERG (${note.totalDebt.toDouble / 1000000000} ERG)
       |  Payer Sig Valid: ${verifyNote(note)}
       |  Tracker Sig Valid: $trackerSigValid
       |""".stripMargin
  }

  val amount = if (args.length >= 1) args(0).toLong else 50000000L // default 0.05 ERG
  val note = createNote(aliceSecret, bobPublicKey, amount)
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
