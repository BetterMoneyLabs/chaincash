package chaincash.offchain.server

import scala.util.Random

/**
 * We consider that every ChainCash agent may be associated with a pubkey. In general, pubkey could be corresponding
 * to a complex cryptographic statement (e.g. provable via a sigma-protocol, like in Ergo).
 */
case class PubKey(value: String)

/**
 * An issuer, also known as an agent, every participant in ChainCash monetary system can issue new money
 */
case class Issuer(pk: PubKey)

/**
 * Signature which is proving knowledge of a secret corresponding to public key `pk`
 */
case class Signature(pk: PubKey)

/**
 * Every agent may have reserve backing money spend or issued by the agent with collateral, or signalizing
 * trust which can be expressed towards the agent
 */
trait Reserve {
  val issuer: Issuer

  def usCentAmount: Long // in us cents
}

trait TrustBasedReserve extends Reserve {
  def usCentAmount: Long  = 0L
}

trait CollateralBasedReserve extends Reserve

case class ErgReserve(nanoergs: Long, issuer: Issuer) extends CollateralBasedReserve {
  override def usCentAmount = ???
}

case class UsdReserve(usCentAmount: Long, issuer: Issuer) extends CollateralBasedReserve {
  val usd: Double = usCentAmount / 100.0
}

/**
 * A record added to a note when it is being spent
 */
case class BackingStatement(reserve: Reserve, signature: Signature)

// amount is in usd cents for now
case class Note(id: Long, amount: Long, backing: Seq[BackingStatement])

object Note {
  def apply(amount: Long, backing: Seq[BackingStatement]): Note = {
    Note(Random.nextLong(), amount, backing)
  }
}

trait Transaction

case class ExchangeTransaction(input: Note, outputs: Seq[Note]) extends Transaction

object ExchangeTransaction {
  def payment(from: Note, spenderReserve: Reserve, amount: Long): ExchangeTransaction = {
    require(from.amount >= amount, "Input can't cover the payment")
    val changeOutputOpt = if (from.amount == amount) {
      None
    } else { // > case
      Some(Note(from.amount - amount, from.backing))
    }
    val newBackingStatement = BackingStatement(spenderReserve, Signature(spenderReserve.issuer.pk))
    val paymentOutput = Note(amount, backing = from.backing ++ Seq(newBackingStatement))
    ExchangeTransaction(from, paymentOutput +: changeOutputOpt.toSeq)
  }
}

case class MintingTransaction(output: Note) extends Transaction
case class RedeemingTransaction(input: Note) extends Transaction


trait AcceptanceFilter {
  def acceptable(note: Note): Boolean
}

case class NoteSet(set: Seq[Note]){

  // inefficient and insecure
  def process(transaction: Transaction): NoteSet = transaction match {
    case ExchangeTransaction(input, outputs) =>
      NoteSet(set.filter(n => n != input) ++ outputs)
    case MintingTransaction(output) =>
      NoteSet(set :+ output)
    case RedeemingTransaction(input) =>
      NoteSet(set.filter(n => n != input))
  }
}

object NoteSet {
  def empty: NoteSet = NoteSet(Seq.empty)
}

object Tester extends App {
  def testingAcceptanceFilter(utxoSet: NoteSet): AcceptanceFilter = testingAcceptanceFilter(utxoSet.set)

  def testingAcceptanceFilter(circulatingNotes: Seq[Note]): AcceptanceFilter = {
    (note: Note) => {
      val noteReserves = note.backing.distinct
      val notesIntersecting = noteReserves.flatMap { r => circulatingNotes.filter(n => n.backing.contains(r)) }
      val notesIntersectingBacking = notesIntersecting.flatMap(_.backing.map(_.reserve).distinct).map(_.usCentAmount).sum
      notesIntersectingBacking > notesIntersecting.map(_.amount).sum
    }
  }

  val pk1 = PubKey("1")

  val issuer1 = Issuer(pk1)
  val issuer2 = Issuer(PubKey("2"))
  val issuer3 = Issuer(PubKey("3"))

  val reserve1 = UsdReserve(1000 * 100, issuer1)
  val reserve2 = UsdReserve(1500 * 100, issuer2)
  val reserve3 = UsdReserve(700 * 100, issuer3)

  var utxoSet = NoteSet.empty

  val tx1 = MintingTransaction(Note(1, 100 * 100, Seq(BackingStatement(reserve1, Signature(pk1)))))

  utxoSet = utxoSet.process(tx1)

  val filter1 = testingAcceptanceFilter(utxoSet)
  println(filter1.acceptable(utxoSet.set.head))

}