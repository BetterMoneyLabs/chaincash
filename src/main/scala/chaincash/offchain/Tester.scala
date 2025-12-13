package chaincash.offchain
import sigmastate.eval.CGroupElement

object Tester extends App with TrackingUtils with NoteUtils {
  override val serverUrl: String = "http://127.0.0.1:9053"

  println(fetchNodeHeight())

  processBlocks()

  println("my balance: " + myBalance())

  sendNote(DbEntities.unspentNotes.head.get._2, CGroupElement(myPoint))

}