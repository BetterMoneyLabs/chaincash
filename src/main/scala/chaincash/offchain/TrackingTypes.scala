package chaincash.offchain

import chaincash.contracts.Constants
import org.ergoplatform.ErgoBox
import org.ergoplatform.ErgoBox.R5
import scorex.util.ModifierId
import scorex.util.encode.Base16
import sigmastate.Values.GroupElementConstant
import sigmastate.eval.CGroupElement
import sigmastate.basics.CryptoConstants.EcPointType
import sigmastate.serialization.GroupElementSerializer
import work.lithos.plasma.collections.PlasmaMap

object TrackingTypes {

  type ReserveNftId = ModifierId
  type NoteTokenId = ModifierId
  type NoteId = ModifierId

  case class SigData(reserveId: ReserveNftId, valueBacked: Long, a: EcPointType, z: BigInt)

  case class NoteData(currentUtxo: ErgoBox, history: IndexedSeq[SigData]) {

    def holder: EcPointType = {
      currentUtxo.get(R5).get.asInstanceOf[GroupElementConstant].value.asInstanceOf[CGroupElement].wrappedValue
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
