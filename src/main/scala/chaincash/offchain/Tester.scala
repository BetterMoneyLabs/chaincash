package chaincash.offchain

object Tester extends App with TrackingUtils with NoteUtils {
  override val serverUrl: String = "http://127.0.0.1:9053"

  println(fetchNodeHeight())

  processBlocks()

  println("my balance: " + myBalance())

}