package chaincash.offchain

import com.google.common.primitives.{Longs, Shorts}
import org.bouncycastle.util.BigIntegers
import org.ergoplatform.ErgoBox
import org.ergoplatform.wallet.boxes.ErgoBoxSerializer
import scorex.util.ModifierId
import scorex.util.encode.Base16
import sigmastate.eval.CGroupElement
import sigmastate.serialization.GroupElementSerializer
import special.sigma.GroupElement
import swaydb.{Glass, _}
import swaydb.data.slice.Slice
import swaydb.serializers.Default._
import swaydb.serializers.Serializer
import TrackingTypes._

object DbEntities {

  implicit object ModifierIdSerializer extends Serializer[ModifierId] {
    override def write(modifierId: ModifierId): Slice[Byte] =
      StringSerializer.write(modifierId)

    override def read(slice: Slice[Byte]): ModifierId =
      ModifierId @@ StringSerializer.read(slice)
  }

  implicit object BoxSerializer extends Serializer[ErgoBox] {
    override def write(box: ErgoBox): Slice[Byte] =
      ByteArraySerializer.write(ErgoBoxSerializer.toBytes(box))

    override def read(slice: Slice[Byte]): ErgoBox = {
      val bytes = ByteArraySerializer.read(slice)
      ErgoBoxSerializer.parseBytes(bytes)
    }
  }

  implicit object NoteDataSerializer extends Serializer[NoteData] {
    override def write(noteData: NoteData): Slice[Byte] = {
      val boxBytes = ErgoBoxSerializer.toBytes(noteData.currentUtxo)
      val boxBytesCount = Shorts.toByteArray(boxBytes.length.toShort)
      val historyBytes = noteData.history.foldLeft(Array.emptyByteArray) { case (acc, sd) =>
        acc ++ SigDataSerializer.toBytes(sd)
      }
      ByteArraySerializer.write(boxBytesCount ++ boxBytes ++ historyBytes)
    }

    override def read(slice: Slice[Byte]): NoteData = {
      val bytes = ByteArraySerializer.read(slice)
      val boxBytesCount = Shorts.fromByteArray(bytes.take(2))
      val boxBytes = bytes.slice(2, 2 + boxBytesCount)
      val box = ErgoBoxSerializer.parseBytes(boxBytes)
      val historyBs = bytes.slice(2 + boxBytesCount, bytes.length)
      val history = historyBs.grouped(89).map(bs => SigDataSerializer.fromBytes(bs)).toIndexedSeq
      NoteData(box, history)
    }
  }

  object SigDataSerializer {
    def toBytes(sigData: SigData): Array[Byte] = {
      Base16.decode(sigData.reserveId).get ++
        Longs.toByteArray(sigData.valueBacked) ++
        sigData.a.getEncoded.toArray ++
        BigIntegers.asUnsignedByteArray(32, sigData.z.bigInteger)
    }

    def fromBytes(bytes: Array[Byte]): SigData = {
      val ri = bytes.slice(0, 16)
      val vb = bytes.slice(16, 24)
      val a = bytes.slice(24, 57)
      val z = bytes.slice(57, 89)

      SigData(
        ModifierId @@ Base16.encode(ri),
        Longs.fromByteArray(vb),
        CGroupElement(GroupElementSerializer.fromBytes(a)),
        BigIntegers.fromUnsignedByteArray(z))
    }
  }

  implicit object ReserveDataSerializer extends Serializer[ReserveData] {
    override def write(reserveData: ReserveData): Slice[Byte] = {
      val boxBytes = ErgoBoxSerializer.toBytes(reserveData.reserveBox)
      val boxBytesCount = Shorts.toByteArray(boxBytes.length.toShort)
      val notesBytes = reserveData.signedUnspentNotes.foldLeft(Array.emptyByteArray) { case (acc, id) =>
        acc ++ Base16.decode(id).get
      }
      ByteArraySerializer.write(boxBytesCount ++ boxBytes ++ notesBytes)
    }

    override def read(slice: Slice[Byte]): ReserveData = {
      val bytes = ByteArraySerializer.read(slice)
      val boxBytesCount = Shorts.fromByteArray(bytes.take(2))
      val boxBytes = bytes.slice(2, 2 + boxBytesCount)
      val box = ErgoBoxSerializer.parseBytes(boxBytes)
      val historyBs = bytes.slice(2 + boxBytesCount, bytes.length)
      val notes = historyBs.grouped(32).map(bs => ModifierId @@ Base16.encode(bs)).toIndexedSeq
      ReserveData(box, notes)
    }
  }


  val issuedNotes = persistent.Map[NoteTokenId, ErgoBox, Nothing, Glass](dir = "db/issued_notes")
  val unspentNotes = persistent.Map[UtxoId, NoteData, Nothing, Glass](dir = "db/unspent_notes")
  val reserves = persistent.Map[ReserveNftId, ReserveData, Nothing, Glass](dir = "db/reserves")
  val state = persistent.Map[String, String, Nothing, Glass](dir = "db/state")
}
