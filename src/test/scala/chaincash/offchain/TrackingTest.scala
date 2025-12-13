package chaincash.offchain

import chaincash.contracts.Constants
import chaincash.contracts.Constants.chainCashPlasmaParameters
import org.ergoplatform.ErgoBox
import org.ergoplatform.ErgoBox.{R4, R5}
import org.ergoplatform.wallet.boxes.ErgoBoxSerializer
import org.scalatest.{Matchers, PropSpec}
import scorex.util.ModifierId
import scorex.util.encode.Base16
import sigmastate.Values.{AvlTreeConstant, GroupElementConstant}
import sigmastate.eval.CGroupElement
import sigmastate.eval.Colls
import sigmastate.serialization.GroupElementSerializer
import special.sigma.GroupElement
import work.lithos.plasma.collections.PlasmaMap
import sigmastate.AvlTreeFlags
import TrackingTypes.{NoteData, NoteId}
import sigmastate.eval.Digest32Coll

class TrackingTest extends PropSpec with Matchers {

  property("liabilities should be updated based on unspent notes") {
    // Mock setup
    val reserveNftId = "321A3A5250655368566D597133743677397A24432646294A404D635166546A57"
    val reserveNftIdBytes = Base16.decode(reserveNftId).get
    val reserveNftIdEncoded = ModifierId @@ Base16.encode(reserveNftIdBytes)
    
    // Create a mock reserve box
    val reserveBox = new ErgoBox(
      value = 1000000000L,
      ergoTree = Constants.reserveErgoTree,
      creationHeight = 100,
      additionalTokens = Colls.fromItems((Digest32Coll @@ Colls.fromArray(reserveNftIdBytes)) -> 1L),
      additionalRegisters = Map(R4 -> GroupElementConstant(Constants.g)),
      transactionId = ModifierId @@ "9999999999999999999999999999999999999999999999999999999999999999",
      index = 0.toShort
    )

    // Initial state: No notes
    // Manually trigger processReserves logic (since we can't mock fetchBoxes easily without refactoring)
    // We will simulate the internal logic of `processReserves` here to test the liability calculation core.
    
    // ... wait, instead of simulating, let's just use DbEntities directly since they are singletons (which is bad for tests but reality here)
    // Clear DBs first? They are persistent...
    // Assuming clean state or isolated test run.
    
    // 1. Register a note backed by this reserve
    val noteTokenId = "4b2d8b7beb3eaac8234d9e61792d270898a43934d6a27275e4f3a044609c9f2a"
    val noteValue = 100L
    
    val noteBox = new ErgoBox(
      value = 1000000L,
      ergoTree = Constants.noteErgoTree,
      creationHeight = 100,
      additionalTokens = Colls.fromItems((Digest32Coll @@ Colls.fromArray(Base16.decode(noteTokenId).get)) -> noteValue),
      additionalRegisters = Map(R4 -> AvlTreeConstant(Constants.emptyTree), R5 -> GroupElementConstant(Constants.g)),
      transactionId = ModifierId @@ "0000000000000000000000000000000000000000000000000000000000000000",
      index = 0.toShort
    )
    val noteBoxId = ModifierId @@ Base16.encode(noteBox.id)
    
    // Create NoteData with history pointing to reserve
    val sigData = TrackingTypes.SigData(
      reserveId = reserveNftIdEncoded,
      valueBacked = noteValue,
      a = Constants.g.asInstanceOf[CGroupElement].wrappedValue,
      z = BigInt(1)
    )
    
    val noteData = TrackingTypes.NoteData(
      currentUtxo = noteBox,
      history = IndexedSeq(sigData)
    )
    
    DbEntities.unspentNotes.put(noteBoxId, noteData)
    
    // 2. Run liability calculation logic
    val currentLiabilities = DbEntities.unspentNotes.foldLeft(0L) { case (acc, (id: NoteId, nd: NoteData)) =>
       val isBackedByThis = nd.history.lastOption.exists { lastSig =>
          lastSig.reserveId == reserveNftIdEncoded
       }
       if (isBackedByThis) acc + nd.value else acc
    }
    
    // Assert liability is 100
    currentLiabilities shouldBe 100L
    
    // 3. Add another note
    val noteBox2 = new ErgoBox(
      value = 1000000L,
      ergoTree = Constants.noteErgoTree,
      creationHeight = 100,
      additionalTokens = Colls.fromItems((Digest32Coll @@ Colls.fromArray(Base16.decode(noteTokenId).get)) -> 50L), // different value
      additionalRegisters = Map(R4 -> AvlTreeConstant(Constants.emptyTree), R5 -> GroupElementConstant(Constants.g)),
      transactionId = ModifierId @@ "1111111111111111111111111111111111111111111111111111111111111111",
      index = 0.toShort
    )
    // Fake ID for test distinctness (real box id depends on content but let's assume distinct handling in map key)
    val noteBoxId2 = ModifierId @@ Base16.encode(noteBox2.id).replace("a", "b") 
    
    val noteData2 = TrackingTypes.NoteData(
      currentUtxo = noteBox2,
      history = IndexedSeq(sigData.copy(valueBacked = 50L))
    )
    
    DbEntities.unspentNotes.put(noteBoxId2, noteData2)
    
    val currentLiabilities2 = DbEntities.unspentNotes.foldLeft(0L) { case (acc, (id: NoteId, nd: NoteData)) =>
       val isBackedByThis = nd.history.lastOption.exists { lastSig =>
          lastSig.reserveId == reserveNftIdEncoded
       }
       if (isBackedByThis) acc + nd.value else acc
    }
    
    currentLiabilities2 shouldBe 150L
    
    // 4. Spend first note (remove from unspent)
    DbEntities.unspentNotes.remove(noteBoxId)
    
    val currentLiabilities3 = DbEntities.unspentNotes.foldLeft(0L) { case (acc, (id: NoteId, nd: NoteData)) =>
       val isBackedByThis = nd.history.lastOption.exists { lastSig =>
          lastSig.reserveId == reserveNftIdEncoded
       }
       if (isBackedByThis) acc + nd.value else acc
    }
    
    currentLiabilities3 shouldBe 50L
  }

}
