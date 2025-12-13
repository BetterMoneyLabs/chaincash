package chaincash.offchain
import sigmastate.eval.Digest32Coll

import chaincash.contracts.Constants
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
import org.ergoplatform.P2PKAddress
import sigmastate.basics.DLogProtocol.ProveDlog
import sigmastate.eval.CGroupElement

class MultiTokenTest extends PropSpec with Matchers {

  object TestTracker extends TrackingUtils {
     // Mock setup
     override val serverUrl: String = "http://mock"
     override def getJsonAsString(url: String): String = "{}"
     override def fetchNodeHeight(): Int = 100
     override def fetchChangeAddress(): P2PKAddress = P2PKAddress(ProveDlog(Constants.g.asInstanceOf[CGroupElement].wrappedValue))(Constants.ergoAddressEncoder)
     // Expose for testing
     override def processTransaction(tx: BlockTransaction): Unit = super.processTransaction(tx)
     override def updateAllLiabilities() = super.updateAllLiabilities()
  }

  property("tracker should track liabilities for multiple tokens") {
      // 1. Setup Reserve
      val reserveNftId = "321A3A5250655368566D597133743677397A24432646294A404D635166546A57"
      val reserveNftIdBytes = Base16.decode(reserveNftId).get
      val reserveNftIdEncoded = ModifierId @@ Base16.encode(reserveNftIdBytes)
      
      // Reserve has ERG and maybe some other assets? 
      // For this test, it just exists.
      val reserveBox = new ErgoBox(
        value = 1000000000L,
        ergoTree = Constants.reserveErgoTree,
        creationHeight = 100,
        additionalTokens = Colls.fromItems((Digest32Coll @@ Colls.fromArray(reserveNftIdBytes)) -> 1L),
        additionalRegisters = Map(R4 -> GroupElementConstant(Constants.g)),
        transactionId = ModifierId @@ "9999999999999999999999999999999999999999999999999999999999999999",
        index = 0.toShort
      )
      
      // Inject Reserve into DB manually (or usage processTransaction if we want to test discovery)
      // Let's test discovery!
      val txReserve = BlockTransaction(IndexedSeq.empty, IndexedSeq(reserveBox))
      TestTracker.processTransaction(txReserve)
      
      DbEntities.reserves.get(reserveNftIdEncoded) shouldBe defined
      DbEntities.reserves.get(reserveNftIdEncoded).get.liabilities shouldBe empty
      
      // 2. Setup Notes
      // Note A: Gold
      val goldTokenId = "4b2d8b7beb3eaac8234d9e61792d270898a43934d6a27275e4f3a044609c9f2a"
      val goldTokenBytes = Base16.decode(goldTokenId).get
      val goldTokenEncoded = ModifierId @@ goldTokenId
      
      val boxA = new ErgoBox(
        value = 2000000L,
        ergoTree = Constants.noteErgoTree,
        creationHeight = 100,
        additionalTokens = Colls.fromItems((Digest32Coll @@ Colls.fromArray(goldTokenBytes)) -> 100L),
        additionalRegisters = Map(R4 -> AvlTreeConstant(Constants.emptyTree), R5 -> GroupElementConstant(Constants.g)),
        transactionId = ModifierId @@ "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        index = 0
      )
      val boxIdA = ModifierId @@ Base16.encode(boxA.id)
      
      // Note B: Silver
      val silverTokenId = "5c2d8b7beb3eaac8234d9e61792d270898a43934d6a27275e4f3a044609c9f2b"
      val silverTokenBytes = Base16.decode(silverTokenId).get
      val silverTokenEncoded = ModifierId @@ silverTokenId

      val boxB = new ErgoBox(
        value = 2000000L,
        ergoTree = Constants.noteErgoTree,
        creationHeight = 100,
        additionalTokens = Colls.fromItems((Digest32Coll @@ Colls.fromArray(silverTokenBytes)) -> 500L),
        additionalRegisters = Map(R4 -> AvlTreeConstant(Constants.emptyTree), R5 -> GroupElementConstant(Constants.g)),
        transactionId = ModifierId @@ "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        index = 0
      )
      val boxIdB = ModifierId @@ Base16.encode(boxB.id)
      
      // Helper to link history
      def makeSigData(value: Long) = TrackingTypes.SigData(
         reserveId = reserveNftIdEncoded,
         valueBacked = value,
         a = sigmastate.eval.CGroupElement(Constants.g).wrappedValue,
         z = BigInt(1)
      )
      
      // We need to register these tokens as "Issued Notes" so tracker picks them up
      DbEntities.issuedNotes.put(goldTokenEncoded, boxA) // Just assume same box was minting box for simplicity
      DbEntities.issuedNotes.put(silverTokenEncoded, boxB)
      
      // 3. Process Transactions creating these notes
      // We need to force "History" into their local NoteData, because processTransaction looks for "propagatedHistory" from inputs.
      // Since these are "Minting" (Fresh) notes (no inputs spending notes), propagatedHistory is None.
      // The notes will form NoteData with history = empty.
      // BUT, liability calculation logic looks at HISTORY to decide if it's backed by the reserve.
      // `isBackedByThis = noteData.history.lastOption ...`
      // So if history is empty, it won't be counted as liability!
      
      // Limitation: Freshly Minted notes usually have history appended via the Minting Transaction?
      // `request.es` (Minting) -> The Minting process *creates* the first history entry.
      // The `TrackingUtils.processTransaction` logic for Mints is incomplete:
      // it assumes `propagatedHistory` (from input).
      // If Minting, we need to extract the *Initial History* from the Minting context or constructs.
      // For this test, we can manually inject the notes into `unspentNotes` with history populated, 
      // OR we mock a "Transfer" transaction where Input had the history.
      
      // Let's use Manual Injection for Note Data to verify `updateAllLiabilities`, which is the core task.
      val noteDataA = TrackingTypes.NoteData(boxA, IndexedSeq(makeSigData(100L)))
      val noteDataB = TrackingTypes.NoteData(boxB, IndexedSeq(makeSigData(500L)))
      
      DbEntities.addNote(boxIdA, noteDataA)
      DbEntities.addNote(boxIdB, noteDataB)
      
      // 4. Update Liabilities
      TestTracker.updateAllLiabilities()
      
      // 5. Verify
      val rd = DbEntities.reserves.get(reserveNftIdEncoded).get
      val liabilities = rd.liabilities
      
      liabilities.size shouldBe 2
      liabilities(goldTokenEncoded) shouldBe 100L
      liabilities(silverTokenEncoded) shouldBe 500L
      
      // Verify predicate (assuming 1:1 price)
      // Gold Price setup
      DbEntities.state.put("goldPrice", "1") // 1 ERG/Unit
      
      // Estimate
      val totalLiab = liabilities.values.sum // 600
      totalLiab shouldBe 600L
  }
}
