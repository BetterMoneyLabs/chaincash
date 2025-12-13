package gp

case class Note(noteId: Array[Byte], amount: Long, ownerPubKey: Array[Byte])

// only single output, signed by trusted Git oracle
// in this output, noteId is commit id, amount is number of lines of code, ownerPubKey is pubkey of Github account
case class MintTransaction(output: Note)

object MintTransaction {
  val OraclePubKey: Array[Byte] = Array.fill(33)(0)
}
