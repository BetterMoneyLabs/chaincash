package chaincash.offchain

import chaincash.contracts.Constants
import org.ergoplatform.ErgoBox
import org.scalatest.{Matchers, PropSpec}
import scorex.util.ModifierId
import scorex.util.encode.Base16
import org.ergoplatform.ErgoBox.{R4, R5}
import sigmastate.Values.{AvlTreeConstant, GroupElementConstant}
import sigmastate.eval.Colls
import sigmastate.eval._
import special.sigma.GroupElement
import sigmastate.basics.CryptoConstants

class IndexTest extends PropSpec with Matchers {

  property("DbEntities should maintain indexes on add/remove") {
     // Mock Data
     val holderKey = Constants.g.asInstanceOf[sigmastate.eval.CGroupElement].wrappedValue
     val otherKey = sigmastate.basics.CryptoConstants.dlogGroup.generator
     
     val reserveId = "321A3A5250655368566D597133743677397A24432646294A404D635166546A57"
     val reserveIdEncoded = ModifierId @@ Base16.encode(Base16.decode(reserveId).get)
     
     val noteBox = new ErgoBox(
       value = 1000L,
       ergoTree = Constants.noteErgoTree,
       creationHeight = 100,
       additionalRegisters = Map(
         R4 -> AvlTreeConstant(Constants.emptyTree),
         R5 -> GroupElementConstant(Constants.g)
       ),
       transactionId = ModifierId @@ "0000000000000000000000000000000000000000000000000000000000000000",
       index = 0.toShort
     )
     val noteId = ModifierId @@ Base16.encode(noteBox.id)
     
     val sigData = TrackingTypes.SigData(reserveIdEncoded, 100L, holderKey, BigInt(1))
     val noteData = TrackingTypes.NoteData(noteBox, IndexedSeq(sigData))
     
     // Test Add
     DbEntities.addNote(noteId, noteData)
     
     // Verify Unspent
     DbEntities.unspentNotes.get(noteId) shouldBe defined
     
     // Verify Holder Index
     val holderNotes = DbEntities.notesByHolder.get(holderKey)
     holderNotes shouldBe defined
     holderNotes.get should contain (noteId)
     
     // Verify Reserve Index
     val reserveNotes = DbEntities.notesByReserve.get(reserveIdEncoded)
     reserveNotes shouldBe defined
     reserveNotes.get should contain (noteId)
     
     // Test Remove
     DbEntities.removeNote(noteId)
     
     // Verify Cleanup
     DbEntities.unspentNotes.get(noteId) shouldBe empty
     
     // Verify Indexes Cleaned
     DbEntities.notesByHolder.get(holderKey) match {
        case Some(list) => list should not contain noteId
        case None => // OK
     }
     
     DbEntities.notesByReserve.get(reserveIdEncoded) match {
        case Some(list) => list should not contain noteId
        case None => // OK
     }
  }

}
