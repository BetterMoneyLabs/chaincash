package chaincash.contracts

import chaincash.offchain.SigUtils
import com.google.common.primitives.Longs
import org.ergoplatform.ErgoAddressEncoder
import org.ergoplatform.appkit.NetworkType
import scorex.crypto.encode.Base16
import scorex.crypto.hash.Blake2b256
import sigmastate.basics.CryptoConstants
import sigmastate.eval._
import sigmastate.serialization.GroupElementSerializer
import special.sigma.GroupElement

import scala.io.StdIn

/**
 * Utility for creating Basis IOU notes
 * 
 * An IOU note represents a debt from party A (payer) to party B (payee)
 * Note format: (B_pubkey, totalDebt, sig_A)
 * where sig_A signs: hash(A_pubkey || B_pubkey) || totalDebt
 */
object BasisNoteCreator extends App {

  val networkType = NetworkType.MAINNET
  val ergoAddressEncoder = new ErgoAddressEncoder(networkType.networkPrefix)
  val g = CryptoConstants.dlogGroup.generator

  def createNoteMessage(payerKey: GroupElement, payeeKey: GroupElement, totalDebt: Long): Array[Byte] = {
    val key = Blake2b256(payerKey.getEncoded.toArray ++ payeeKey.getEncoded.toArray)
    key ++ Longs.toByteArray(totalDebt)
  }

  def createNote(payerSecret: BigInt, payeeKey: GroupElement, totalDebt: Long): IOUNote = {
    val payerKey = g.exp(payerSecret.bigInteger)
    val message = createNoteMessage(payerKey, payeeKey, totalDebt)
    val (a, z) = SigUtils.sign(message, payerSecret)
    IOUNote(payerKey, payeeKey, totalDebt, a, z, message)
  }

  def createNoteFromHex(payerSecretHex: String, payeeKeyHex: String, totalDebt: Long): IOUNote = {
    val payerSecret = BigInt(payerSecretHex, 16)
    val payeeKey = GroupElementSerializer.fromBytes(Base16.decode(payeeKeyHex).get)
    createNote(payerSecret, payeeKey, totalDebt)
  }

  def verifyNote(note: IOUNote): Boolean = {
    val message = createNoteMessage(note.payerKey, note.payeeKey, note.totalDebt)
    SigUtils.verify(message, note.payerKey, note.signatureA, note.signatureZ)
  }

  def formatNoteAsJson(note: IOUNote): String = {
    val payerKeyHex = Base16.encode(note.payerKey.getEncoded.toArray)
    val payeeKeyHex = Base16.encode(note.payeeKey.getEncoded.toArray)
    val sigAHex = Base16.encode(GroupElementSerializer.toBytes(note.signatureA))
    s"""{
       |  "payerKey": "$payerKeyHex",
       |  "payeeKey": "$payeeKeyHex",
       |  "totalDebt": ${note.totalDebt},
       |  "totalDebtERG": ${note.totalDebt.toDouble / 1000000000},
       |  "signature": {"a": "$sigAHex", "z": "${note.signatureZ.toString(16)}"},
       |  "message": "${Base16.encode(note.message)}",
       |  "noteKey": "${Base16.encode(Blake2b256((payerKeyHex ++ payeeKeyHex).getBytes))}"
       |}""".stripMargin
  }

  def formatNoteHuman(note: IOUNote): String = {
    val payerShort = Base16.encode(note.payerKey.getEncoded.toArray).take(16) + "..."
    val payeeShort = Base16.encode(note.payeeKey.getEncoded.toArray).take(16) + "..."
    s"""IOU Note:
       |  Payer:  $payerShort
       |  Payee:  $payeeShort  
       |  Amount: ${note.totalDebt} nanoERG (${note.totalDebt.toDouble / 1000000000} ERG)
       |  Valid:  ${verifyNote(note)}
       |""".stripMargin
  }

  def printUsage(): Unit = {
    println("=== Basis Note Creator ===")
    println("Creates IOU notes for Basis offchain payments.")
    println()
    println("Usage: BasisNoteCreator [payerSecret] [payeeKey] [amount_nanoERG]")
    println("  Run without args for interactive mode")
    println()
  }

  if (args.isEmpty) {
    printUsage()
    println("=== Interactive Mode ===\n")
    
    print("Payer secret (hex): ")
    val payerSecret = try { BigInt(StdIn.readLine(), 16) } catch { case _: Exception => BigInt(StdIn.readLine()) }
    
    print("Payee public key (hex): ")
    val payeeKey = GroupElementSerializer.fromBytes(Base16.decode(StdIn.readLine()).get)
    
    print("Amount nanoERG (default 1 ERG): ")
    val amount = StdIn.readLine() match { case s if s.isEmpty => 1000000000L case s => s.toLong }
    
    val note = createNote(payerSecret, payeeKey, amount)
    println("\n" + formatNoteHuman(note))
    println("=== JSON ===\n" + formatNoteAsJson(note))
    println("\nNext: Send to tracker via POST noteUpdate")
  } else if (args.length >= 3) {
    val note = createNoteFromHex(args(0), args(1), args(2).toLong)
    println(formatNoteHuman(note))
    println("\n=== JSON ===\n" + formatNoteAsJson(note))
  } else {
    println("Error: Need 3 args: payerSecret payeeKey amount_nanoERG")
    sys.exit(1)
  }
}

case class IOUNote(
  payerKey: GroupElement,
  payeeKey: GroupElement,
  totalDebt: Long,
  signatureA: GroupElement,
  signatureZ: BigInt,
  message: Array[Byte]
) {
  def noteKey: Array[Byte] = Blake2b256(payerKey.getEncoded.toArray ++ payeeKey.getEncoded.toArray)
  def noteKeyHex: String = Base16.encode(noteKey)
  def debtInERG: Double = totalDebt.toDouble / 1000000000
}

object IOUNote {
  def createNoteKey(payerKey: GroupElement, payeeKey: GroupElement): Array[Byte] =
    Blake2b256(payerKey.getEncoded.toArray ++ payeeKey.getEncoded.toArray)
  def calculateNewDebt(previousDebt: Long, paymentAmount: Long): Long = previousDebt + paymentAmount
}
