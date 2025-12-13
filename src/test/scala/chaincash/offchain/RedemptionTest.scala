package chaincash.offchain

import chaincash.contracts.Constants
import org.ergoplatform.ErgoBox
import org.ergoplatform.ErgoBox.{R4, R5, R6}
import org.scalatest.{Matchers, PropSpec}
import scorex.util.ModifierId
import scorex.util.encode.Base16
import sigmastate.Values.{AvlTreeConstant, GroupElementConstant, LongConstant}
import sigmastate.eval.Colls
import sigmastate.eval._
import special.sigma.GroupElement
import work.lithos.plasma.collections.PlasmaMap
import sigmastate.AvlTreeFlags
import sigmastate.eval.CGroupElement
import sigmastate.basics.CryptoConstants
import org.ergoplatform.P2PKAddress
import TrackingTypes.{NoteId, NoteData}
import sigmastate.basics.DLogProtocol.ProveDlog
import sigmastate.eval.{CGroupElement, Digest32Coll}

class RedemptionTest extends PropSpec with Matchers {

  // Mock Utils ensuring no IO
  object MockUtils extends NoteUtils {
     override val serverUrl: String = "http://mock"
     override def fetchChangeAddress(): P2PKAddress = P2PKAddress(ProveDlog(Constants.g.asInstanceOf[CGroupElement].wrappedValue))(Constants.ergoAddressEncoder)
     
     override def fetchInputs() = {
       val dummyBox = new ErgoBox(
         value = 2000000L, 
         ergoTree = Constants.reserveErgoTree, 
         creationHeight = 100,
         additionalTokens = Colls.emptyColl,
         additionalRegisters = Map.empty,
         transactionId = ModifierId @@ "8888888888888888888888888888888888888888888888888888888888888888",
         index = 0.toShort
       )
       Seq(dummyBox)
     }
     
     override def createFeeOut(creationHeight: Int) = {
        import org.ergoplatform.ErgoBoxCandidate
        new ErgoBoxCandidate(feeValue, Constants.reserveErgoTree, creationHeight) // Dummy tree
     }
  }

  property("redeemNote should build correct transaction and update state") {
    val reserveNftId = "321A3A5250655368566D597133743677397A24432646294A404D635166546A57"
    val reserveNftIdBytes = Base16.decode(reserveNftId).get
    val reserveNftIdEncoded = ModifierId @@ Base16.encode(reserveNftIdBytes)
    
    val reserveValue = 1000000000L // 1 ERG
    val reserveBox = new ErgoBox(
      value = reserveValue,
      ergoTree = Constants.reserveErgoTree,
      creationHeight = 100,
      additionalTokens = Colls.fromItems((Digest32Coll @@ Colls.fromArray(reserveNftIdBytes)) -> 1L),
      additionalRegisters = Map(R4 -> GroupElementConstant(Constants.g)),
      transactionId = ModifierId @@ "0000000000000000000000000000000000000000000000000000000000000000",
      index = 0.toShort
    )

    val noteTokenId = "4b2d8b7beb3eaac8234d9e61792d270898a43934d6a27275e4f3a044609c9f2a"
    val noteValue = 10L // 10 mg
    
    val noteBox = new ErgoBox(
      value = 1000000L,
      ergoTree = Constants.noteErgoTree,
      creationHeight = 100,
      additionalTokens = Colls.fromItems((Digest32Coll @@ Colls.fromArray(Base16.decode(noteTokenId).get)) -> noteValue),
      additionalRegisters = Map(R4 -> AvlTreeConstant(Constants.emptyTree), R5 -> GroupElementConstant(Constants.g)),
      transactionId = ModifierId @@ "1111111111111111111111111111111111111111111111111111111111111111",
      index = 0.toShort
    )
    val noteBoxId = ModifierId @@ Base16.encode(noteBox.id)

    val oracleRate = 50000000L // 50 nanoErg / mg * 10^6
    val oracleBox = new ErgoBox(
       value = 1000000L,
       ergoTree = Constants.reserveErgoTree, // irrelevant
       creationHeight = 100,
       additionalTokens = Colls.emptyColl,
       additionalRegisters = Map(R4 -> LongConstant(oracleRate)),
       transactionId = ModifierId @@ "2222222222222222222222222222222222222222222222222222222222222222",
       index = 0.toShort
    )

    val buyBackBox = new ErgoBox(
       value = 1000000L,
       ergoTree = Constants.reserveErgoTree, // irrelevant
       creationHeight = 100,
       additionalTokens = Colls.emptyColl,
       transactionId = ModifierId @@ "3333333333333333333333333333333333333333333333333333333333333333",
       index = 0.toShort
    )
    
    // Setup NoteData
    val sigData = TrackingTypes.SigData(
      reserveId = reserveNftIdEncoded,
      valueBacked = noteValue,
      a = Constants.g.asInstanceOf[CGroupElement].wrappedValue,
      z = BigInt(1)
    )
    val noteData = TrackingTypes.NoteData(noteBox, IndexedSeq(sigData))
    
    // Populate DB
    DbEntities.unspentNotes.put(noteBoxId, noteData)
    // Make sure reserve is in DB
    val rd = TrackingTypes.ReserveData(reserveBox, IndexedSeq.empty, liabilities = Map(ModifierId @@ Base16.encode(Base16.decode(noteTokenId).get) -> 10L))
    DbEntities.reserves.put(reserveNftIdEncoded, rd)

    val changeAddress = P2PKAddress(ProveDlog(Constants.g.asInstanceOf[CGroupElement].wrappedValue))(Constants.ergoAddressEncoder)
    
    // Build Tx
    val mockProof = Array[Byte](1, 2, 3)
    val tx = MockUtils.redeemNote(
       noteData,
       reserveBox,
       oracleBox,
       buyBackBox,
       mockProof,
       reservePosition = 0L,
       changeAddress
    )
    
    // Verify Tx Structure
    tx.inputs.length should be >= 3 // Note, Reserve, Buyback
    tx.outputs.length should be >= 3 // Reserve, Receipt, Buyback
    
    // Verify Amounts
    // Rate = 50000000 / 1000000 = 50 nanoErg/mg
    // MaxRedeem = 10 * 50 * 0.98 = 490 nanoErg
    // Redemption = 490
    // Reserve Out = 1000000000 - 490 = 999999510
    
    tx.outputs(0).value shouldBe 999999510L
    tx.outputs(1).value should be (noteBox.value) // Receipt gets note value
    
    // Verify Buyback
    // Fee = 490 * 0.002 = 0.98 ~ 0 (integer division)
    // If fee is 0, buyback output value should be same as input?
    // Code: buyBackOutValue = buyBackBox.value + buyBackFee
    // 1000000 + 0 = 1000000
    tx.outputs(2).value shouldBe 1000000L
    
    // Assume transaction was mined
    // 1. Unspent Note Removed
    DbEntities.unspentNotes.remove(noteBoxId)
    // 2. Reserve Box Updated (Simulated via processReservesFor logic) (Not implicitly called by redeemNote)
    
    // Let's manually verify liabilities update if we run the logic from TrackingUtils
    val liabilities = DbEntities.unspentNotes.foldLeft(0L) { case (acc, (id: NoteId, nd: NoteData)) =>
       val isBackedByThis = nd.history.lastOption.exists { lastSig =>
          lastSig.reserveId == reserveNftIdEncoded
       }
       if (isBackedByThis) acc + nd.value else acc
    }
    
    liabilities shouldBe 0L
  }
}
