package chaincash.offchain

import io.circe.parser.parse
import org.ergoplatform.ErgoBox
import scorex.util.{ModifierId, ScorexLogging}
import scorex.util.encode.Base16
import TrackingTypes._
import chaincash.offchain.DbEntities.heightKey
import org.ergoplatform.ErgoBox.R4
import sigmastate.Values.GroupElementConstant

trait TrackingUtils extends WalletUtils with HttpUtils with ScorexLogging {

  val noteScanId = 21
  val reserveScanId = 20


  def lastProcessedHeight(): Int = DbEntities.state.get(heightKey).map(_.toInt).getOrElse(0)

  private def fetchBoxes(scanId: Int, from: Int, to: Int) = {
    val reserveScanUrl = s"$serverUrl/scan/unspentBoxes/$scanId?minInclusionHeight=$from&maxInclusionHeight=$to"
    val boxesUnspentJson = parse(getJsonAsString(reserveScanUrl)).toOption.get
    boxesUnspentJson.\\("box").map(_.as[ErgoBox].toOption.get)
  }

  def processReservesFor(from: Int, to: Int) = {

    def processBox(box: ErgoBox): Unit = {
      val boxId = box.id
      val boxTokens = box.additionalTokens.toArray
      if (boxTokens.isEmpty) {
        log.warn(s"Reserve box with no NFT: ${Base16.encode(boxId)}")
        return
      }
      val reserveNftRecord = boxTokens.head
      if (reserveNftRecord._2 > 1) {
        log.warn(s"Reserve box with more than one id token: ${Base16.encode(boxId)}")
        return
      }
      val reserveNftId = reserveNftRecord._1
      val reserveNftIdEncoded = ModifierId @@ Base16.encode(reserveNftId.toArray)
      val rd: ReserveData = DbEntities.reserves.get(reserveNftIdEncoded) match {
        case Some(reserveData: ReserveData) =>
          // if existing reserve box updated, e.g. top-up done on it
          ReserveData(box, reserveData.signedUnspentNotes, liabilites = 0L) // todo: fix liabilities
        case None =>
          val rd = ReserveData(box, IndexedSeq.empty, liabilites = 0L) // todo: fix liabilities
          box.additionalRegisters.get(R4).foreach { v =>
            v match {
              case owner: GroupElementConstant if owner == GroupElementConstant(myPoint) =>
                DbEntities.myReserves.add(rd.reserveNftId)
              case _ =>
                log.warn(s"Reserve R4 miss for $v")
            }
          }
          rd
      }
      DbEntities.reserves.put(key = rd.reserveNftId, value = rd)
    }

    fetchBoxes(reserveScanId, from, to).foreach { box =>
      processBox(box)
    }
  }

  // todo: check how it works when not is spent multiple times in the same block
  def processNotes(from: Int, to: Int) = {
    def processBox(box: ErgoBox): Unit = {
      val boxId = ModifierId @@ Base16.encode(box.id)
      val boxTokens = box.additionalTokens.toArray
      if (boxTokens.isEmpty || boxTokens.length > 1) {
        log.warn(s"Reserve box with no NFT: $boxId")
        return
      }
      val noteTokenId = ModifierId @@ Base16.encode(boxTokens.head._1.toArray)
      val noteValue = boxTokens.head._2

      DbEntities.issuedNotes.get(noteTokenId) match {
        case Some(_) =>

        case None =>
          DbEntities.issuedNotes.put(noteTokenId, box)
          // todo: check that AVL+ tree is empty
          val noteData = NoteData(box, IndexedSeq.empty)
          DbEntities.unspentNotes.put(boxId, noteData)

      }

    }

    fetchBoxes(noteScanId, from, to).foreach { box =>
      processBox(box)
    }
  }

  def processBlocks(): Unit = {
    val localHeight = lastProcessedHeight()
    val nodeHeight = fetchNodeHeight()
    if (nodeHeight > localHeight) {
      processReservesFor(localHeight, nodeHeight)
      processNotes(localHeight, nodeHeight)
      DbEntities.state.put(heightKey, nodeHeight.toString)
    }
  }


}
