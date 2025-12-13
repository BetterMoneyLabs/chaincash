package chaincash.offchain

import io.circe.parser.parse
import org.ergoplatform.ErgoBox
import scorex.util.{ModifierId, ScorexLogging}
import scorex.util.encode.Base16
import TrackingTypes._
import chaincash.offchain.DbEntities.heightKey
import org.ergoplatform.ErgoBox.R4
import sigmastate.Values.GroupElementConstant

import org.ergoplatform.JsonCodecs
import org.ergoplatform.ErgoLikeTransaction
import chaincash.contracts.Constants
import org.ergoplatform.Input
import io.circe.{Decoder, HCursor}

case class BlockTransaction(inputs: IndexedSeq[Input], outputs: IndexedSeq[ErgoBox])

trait TrackingUtils extends WalletUtils with HttpUtils with ScorexLogging with JsonCodecs {
  
  implicit val blockTransactionDecoder: Decoder[BlockTransaction] = (c: HCursor) => for {
    inputs <- c.downField("inputs").as[IndexedSeq[Input]]
    outputs <- c.downField("outputs").as[IndexedSeq[ErgoBox]]
  } yield BlockTransaction(inputs, outputs)

  val noteScanId = 21
  val reserveScanId = 20

  def lastProcessedHeight(): Int = DbEntities.state.get(heightKey).map(_.toInt).getOrElse(0)

  def processBlock(height: Int): Unit = {
     val idsUrl = s"$serverUrl/blocks/at/$height"
     val idsJson = parse(getJsonAsString(idsUrl)).toOption.get
     val blockIds = idsJson.as[Seq[String]].toOption.getOrElse(Seq.empty)
     
     blockIds.headOption.foreach { blockId =>
       val blockUrl = s"$serverUrl/blocks/$blockId"
       val blockJson = parse(getJsonAsString(blockUrl)).toOption.get
       val transactions = blockJson.\\("transactions").head.as[Seq[BlockTransaction]].toOption.get
       
       transactions.foreach(processTransaction)
       
       DbEntities.state.put(heightKey, height.toString)
     }
  }
  
  def processTransaction(tx: BlockTransaction): Unit = {
     // 1. Process Inputs (Spending)
     var propagatedHistory: Option[IndexedSeq[SigData]] = None
     
     tx.inputs.foreach { input =>
       val boxIdEncoded = ModifierId @@ Base16.encode(input.boxId)
       DbEntities.unspentNotes.get(boxIdEncoded).foreach { noteData =>
          DbEntities.removeNote(boxIdEncoded)
          propagatedHistory = Some(noteData.history)
       }
     }
     
     // 2. Process Outputs (Creation/Receipt)
     tx.outputs.foreach { box =>
       val boxTokens = box.additionalTokens.toArray
       if (boxTokens.nonEmpty) {
          val token0 = boxTokens.head
          val tokenId = ModifierId @@ Base16.encode(token0._1.toArray)
           // Check if it's a Note
          if (DbEntities.issuedNotes.get(tokenId).isDefined) {
             val boxId = ModifierId @@ Base16.encode(box.id)
             val history = propagatedHistory.getOrElse(IndexedSeq.empty)
             val noteData = NoteData(box, history)
             DbEntities.addNote(boxId, noteData)
          } 
          
    
          // Check if it's a Reserve
          DbEntities.reserves.get(tokenId) match {
             case Some(old) =>
               DbEntities.reserves.put(tokenId, old.copy(reserveBox = box))
             case None =>
               // Discovery of new Reserve
               // Check if ErgoTree matches Reserve Contract and it holds a singleton token
               if (box.ergoTree == Constants.reserveErgoTree && boxTokens.length == 1 && boxTokens.head._2 == 1) {
                  val newRd = ReserveData(box, IndexedSeq.empty, Map.empty)
                  DbEntities.reserves.put(tokenId, newRd)
                  
                  // Track if it's mine
                  box.additionalRegisters.get(R4).foreach {
                      case owner: GroupElementConstant if owner == GroupElementConstant(myPoint) => 
                         DbEntities.myReserves.add(tokenId)
                      case _ =>
                  }
               }
          }
       }
     }
  }
  
  def processBlocks(): Unit = {
     val localHeight = lastProcessedHeight()
     val nodeHeight = fetchNodeHeight()
     ((localHeight + 1) to nodeHeight).foreach { h =>
        processBlock(h)
        updateAllLiabilities()
     }
  }
  
  def updateAllLiabilities() = {
     DbEntities.reserves.foreach { case (reserveId: ReserveNftId, rData: ReserveData) =>
        // Calculate liabilities map: NoteTokenId -> Amount
        val liabilitiesMap = scala.collection.mutable.Map.empty[ModifierId, Long]
        
        // Use Index to find backed notes
        val backedNoteIds = DbEntities.notesByReserve.get(reserveId).getOrElse(IndexedSeq.empty)
        
        backedNoteIds.foreach { noteId =>
           DbEntities.unspentNotes.get(noteId).foreach { noteData => 
             // Double check the linkage (optional but safer against stale index if update wasn't atomic)
             val isBackedByThis = noteData.history.lastOption.exists { lastSig =>
               lastSig.reserveId == reserveId
             }
             if (isBackedByThis) {
               val noteToken = noteData.currentUtxo.additionalTokens.toArray.head
               val tokenId = ModifierId @@ Base16.encode(noteToken._1.toArray)
               val current = liabilitiesMap.getOrElse(tokenId, 0L)
               liabilitiesMap.put(tokenId, current + noteData.value)
             }
           }
        }
        
        if (liabilitiesMap.toMap != rData.liabilities) {
           DbEntities.reserves.put(reserveId, rData.copy(liabilities = liabilitiesMap.toMap))
        }
     }
  }

}
