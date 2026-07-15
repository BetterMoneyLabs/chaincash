package chaincash.offchain

import SigUtils._
import chaincash.contracts.Constants
import org.ergoplatform.ErgoBox
import org.ergoplatform.ErgoBox.R5
import scorex.util.ModifierId
import scorex.util.encode.Base16
import sigma.ast.{Constant, SType}
import sigma.GroupElement
import sigma.serialization.GroupElementSerializer
import work.lithos.plasma.collections.PlasmaMap

object TrackingTypes {

  type ReserveNftId = ModifierId
  type NoteTokenId = ModifierId
  type NoteId = ModifierId

  case class SigData(reserveId: ReserveNftId, valueBacked: Long, a: GroupElement, z: BigInt)

  case class NoteData(currentUtxo: ErgoBox, history: IndexedSeq[SigData]) {

    def holder: GroupElement = {
      currentUtxo.get(R5).get.asInstanceOf[Constant[SType]].value match {
        case ge: GroupElement => ge
        case _ => throw new IllegalStateException("R5 is not a GroupElement")
      }
    }

    def restoreProver: PlasmaMap[Array[Byte], Array[Byte]] = {
      val keyvals = history.map{sigData =>
        val reserveId = Base16.decode(sigData.reserveId).get
        val value = GroupElementSerializer.toBytes(sigData.a) ++ sigData.z.toByteArray
        reserveId -> value
      }
      val map = Constants.emptyPlasmaMap
      map.insert(keyvals :_*)
      map
    }
  }

  case class ReserveData(reserveBox: ErgoBox,
                         signedUnspentNotes: IndexedSeq[NoteId],
                         liabilites: Long) {
    def reserveNftId: ReserveNftId = ModifierId @@ Base16.encode(reserveBox.additionalTokens.toArray.head._1.toArray)
  }

}
