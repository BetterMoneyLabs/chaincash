package chaincash.offchain

import sigmastate.eval.Digest32Coll

import chaincash.contracts.Constants
import org.ergoplatform.{ErgoBox, Input}
import org.ergoplatform.ErgoBox.{R4, R5}
import org.scalatest.{Matchers, PropSpec}
import scorex.util.ModifierId
import scorex.util.encode.Base16
import sigmastate.Values.{AvlTreeConstant, GroupElementConstant}
import sigmastate.eval.Colls
import sigmastate.eval._
import special.sigma.GroupElement
import work.lithos.plasma.collections.PlasmaMap
import sigmastate.AvlTreeFlags
import sigmastate.eval.CGroupElement
import sigmastate.basics.CryptoConstants
import org.ergoplatform.P2PKAddress
import sigmastate.interpreter.{ContextExtension, ProverResult}

import sigmastate.basics.DLogProtocol.ProveDlog

class TrackingChainingTest extends PropSpec with Matchers {

  // Mock implementation of TrackingUtils for testing
  object TestTracker extends TrackingUtils {
     // Override fetching methods to mock behavior
     override val serverUrl: String = "http://mock"
     override def getJsonAsString(url: String): String = "{}"
     override def fetchNodeHeight(): Int = 100
     override def fetchChangeAddress(): P2PKAddress = P2PKAddress(ProveDlog(Constants.g.asInstanceOf[CGroupElement].wrappedValue))(Constants.ergoAddressEncoder)
     
     // Expose processTransaction for testing
     override def processTransaction(tx: BlockTransaction): Unit = super.processTransaction(tx)
  }

  property("tracker should handle multiple spends (A->B->C) in one block") {
      // ... (Same setup until Tx construction)
      
      // Note A (Already Unspent in DB)
      // Tx 1: A -> B
      // Tx 2: B -> C
      
      val noteTokenId = "4b2d8b7beb3eaac8234d9e61792d270898a43934d6a27275e4f3a044609c9f2a"
      val noteTokenIdBytes = Base16.decode(noteTokenId).get
      val noteTokenIdEncoded = ModifierId @@ noteTokenId

      // Note A
      val boxA = new ErgoBox(
        value = 1000000L,
        ergoTree = Constants.noteErgoTree,
        creationHeight = 90,
        additionalTokens = Colls.fromItems((Digest32Coll @@ Colls.fromArray(noteTokenIdBytes)) -> 100L),
        additionalRegisters = Map(R4 -> AvlTreeConstant(Constants.emptyTree), R5 -> GroupElementConstant(Constants.g)),
        transactionId = ModifierId @@ "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        index = 0
      )
      val boxIdA = ModifierId @@ Base16.encode(boxA.id)
      
      // Seed DB with A
      DbEntities.issuedNotes.put(noteTokenIdEncoded, boxA) // Register token
      DbEntities.addNote(boxIdA, TrackingTypes.NoteData(boxA, IndexedSeq.empty))
      
      // Note B (Output of Tx1)
      val boxB = new ErgoBox(
        value = 1000000L,
        ergoTree = Constants.noteErgoTree,
        creationHeight = 100,
        additionalTokens = Colls.fromItems((Digest32Coll @@ Colls.fromArray(noteTokenIdBytes)) -> 100L),
        additionalRegisters = Map(R4 -> AvlTreeConstant(Constants.emptyTree), R5 -> GroupElementConstant(Constants.g)),
        transactionId = ModifierId @@ "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        index = 0
      )
      val boxIdB = ModifierId @@ Base16.encode(boxB.id)

      // Tx 1: Spends A, Creates B
      val inputA = new Input(boxA.id, ProverResult(Array.emptyByteArray, ContextExtension.empty))
      val tx1 = BlockTransaction(
         IndexedSeq(inputA),
         IndexedSeq(boxB)
      )
      
      // Note C (Output of Tx2)
      val boxC = new ErgoBox(
        value = 1000000L,
        ergoTree = Constants.noteErgoTree,
        creationHeight = 100,
        additionalTokens = Colls.fromItems((Digest32Coll @@ Colls.fromArray(noteTokenIdBytes)) -> 100L),
        additionalRegisters = Map(R4 -> AvlTreeConstant(Constants.emptyTree), R5 -> GroupElementConstant(Constants.g)),
        transactionId = ModifierId @@ "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
        index = 0
      )
      val boxIdC = ModifierId @@ Base16.encode(boxC.id)

      // Tx 2: Spends B, Creates C
      val inputB = new Input(boxB.id, ProverResult(Array.emptyByteArray, ContextExtension.empty))
      val tx2 = BlockTransaction(
         IndexedSeq(inputB),
         IndexedSeq(boxC)
      )
      
      // Execute Logic
      // Process Tx1
      TestTracker.processTransaction(tx1)
      
      // Assert intermediate state: A spent, B unspent
      DbEntities.unspentNotes.get(boxIdA) shouldBe empty
      DbEntities.unspentNotes.get(boxIdB) shouldBe defined
      
      // Process Tx2
      TestTracker.processTransaction(tx2)
      
      // Assert final state: B spent, C unspent
      DbEntities.unspentNotes.get(boxIdB) shouldBe empty
      DbEntities.unspentNotes.get(boxIdC) shouldBe defined
      
      // Verify History Chaining (Implicitly empty here, but structurally verified)
      val noteDataC = DbEntities.unspentNotes.get(boxIdC).get
      noteDataC.currentUtxo.id shouldBe boxC.id
  }

}
