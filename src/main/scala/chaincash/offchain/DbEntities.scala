package chaincash.offchain

import com.google.common.primitives.{Longs, Shorts}
import org.bouncycastle.util.BigIntegers
import org.ergoplatform.ErgoBox
import org.ergoplatform.wallet.boxes.ErgoBoxSerializer
import scorex.util.ModifierId
import scorex.util.encode.Base16
import sigmastate.eval.CGroupElement
import sigmastate.serialization.GroupElementSerializer
import swaydb.{Glass, _}
import swaydb.data.slice.Slice
import swaydb.serializers.Default._
import swaydb.serializers.Serializer
import TrackingTypes._
import sigmastate.basics.CryptoConstants.EcPointType

object DbEntities {

  val heightKey = "height"

  val oracleRateKey = "goldprice"

  implicit object EcPointSerializer extends Serializer[EcPointType] {
    override def write(modifierId: EcPointType): Slice[Byte] =
      ByteArraySerializer.write(GroupElementSerializer.toBytes(modifierId))

    override def read(slice: Slice[Byte]): EcPointType = {
      val bytes = ByteArraySerializer.read(slice)
      GroupElementSerializer.fromBytes(bytes)
    }
  }

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
      val history = historyBs.grouped(105).map(bs => SigDataSerializer.fromBytes(bs)).toIndexedSeq
      NoteData(box, history)
    }
  }

  object SigDataSerializer {
    def toBytes(sigData: SigData): Array[Byte] = {
      Base16.decode(sigData.reserveId).get ++
        Longs.toByteArray(sigData.valueBacked) ++
        GroupElementSerializer.toBytes(sigData.a) ++
        BigIntegers.asUnsignedByteArray(32, sigData.z.bigInteger)
    }

    def fromBytes(bytes: Array[Byte]): SigData = {
      val ri = bytes.slice(0, 32)
      val vb = bytes.slice(32, 40)
      val a = bytes.slice(40, 73)
      val z = bytes.slice(73, 105)

      SigData(
        ModifierId @@ Base16.encode(ri),
        Longs.fromByteArray(vb),
        GroupElementSerializer.fromBytes(a),
        BigIntegers.fromUnsignedByteArray(z))
    }
  }

  implicit object ReserveDataSerializer extends Serializer[ReserveData] {
    override def write(reserveData: ReserveData): Slice[Byte] = {
      val boxBytes = ErgoBoxSerializer.toBytes(reserveData.reserveBox)
      val boxBytesCount = Shorts.toByteArray(boxBytes.length.toShort)
      
      // Serialize liabilities Map
      val liabilitiesBytes = reserveData.liabilities.foldLeft(Array.emptyByteArray) { case (acc, (id, amount)) =>
         acc ++ Base16.decode(id).get ++ Longs.toByteArray(amount)
      }
      val liabilitiesCount = Shorts.toByteArray(reserveData.liabilities.size.toShort)
      
      val notesBytes = reserveData.signedUnspentNotes.foldLeft(Array.emptyByteArray) { case (acc, id) =>
        acc ++ Base16.decode(id).get
      }
      ByteArraySerializer.write(boxBytesCount ++ boxBytes ++ liabilitiesCount ++ liabilitiesBytes ++ notesBytes)
    }

    override def read(slice: Slice[Byte]): ReserveData = {
      val bytes = ByteArraySerializer.read(slice)
      var position = 0
      
      val boxBytesCount = Shorts.fromByteArray(bytes.take(2))
      position += 2
      
      val boxBytes = bytes.slice(position, position + boxBytesCount)
      position += boxBytesCount
      val box = ErgoBoxSerializer.parseBytes(boxBytes)
      
      val liabilitiesCount = Shorts.fromByteArray(bytes.slice(position, position + 2))
      position += 2
      
      val liabilitiesSeq = (0 until liabilitiesCount).map { _ =>
         val idBytes = bytes.slice(position, position + 32)
         position += 32
         val amount = Longs.fromByteArray(bytes.slice(position, position + 8))
         position += 8
         (ModifierId @@ Base16.encode(idBytes)) -> amount
      }
      val liabilities = liabilitiesSeq.toMap
      
      val historyBs = bytes.slice(position, bytes.length)
      val notes = historyBs.grouped(32).map(bs => ModifierId @@ Base16.encode(bs)).toIndexedSeq
      ReserveData(box, notes, liabilities)
    }
  }


  implicit object NoteIdsSerializer extends Serializer[IndexedSeq[NoteId]] {
    override def write(data: IndexedSeq[NoteId]): Slice[Byte] = {
      val idsBytes = data.foldLeft(Array.emptyByteArray) { case (acc, id) =>
         acc ++ Base16.decode(id).get
      }
      ByteArraySerializer.write(idsBytes)
    }

    override def read(slice: Slice[Byte]): IndexedSeq[NoteId] = {
      val bytes = ByteArraySerializer.read(slice)
      bytes.grouped(32).map(bs => ModifierId @@ Base16.encode(bs)).toIndexedSeq
    }
  }

  val issuedNotes = persistent.Map[NoteTokenId, ErgoBox, Nothing, Glass](dir = "db/issued_notes")
  val unspentNotes = persistent.Map[NoteId, NoteData, Nothing, Glass](dir = "db/unspent_notes")
  val reserves = persistent.Map[ReserveNftId, ReserveData, Nothing, Glass](dir = "db/reserves")
  val state = persistent.Map[String, String, Nothing, Glass](dir = "db/state")

  // ecpoint -> reserve index
  val reserveKeys = persistent.Map[EcPointType, ReserveNftId, Nothing, Glass](dir = "db/reserveKeys")

  val myReserves = persistent.Set[ReserveNftId, Nothing, Glass](dir = "db/my-reserves")
  
  // Indexes
  val notesByHolder = persistent.Map[EcPointType, IndexedSeq[NoteId], Nothing, Glass](dir = "db/notes_by_holder")
  val notesByReserve = persistent.Map[ReserveNftId, IndexedSeq[NoteId], Nothing, Glass](dir = "db/notes_by_reserve")

  // Repository Methods
  def addNote(id: NoteId, data: NoteData): Unit = {
    unspentNotes.put(id, data)
    
    // Update Holder Index
    val holder = data.holder
    val holderNotes = notesByHolder.get(holder).getOrElse(IndexedSeq.empty)
    if (!holderNotes.contains(id)) {
      notesByHolder.put(holder, holderNotes :+ id)
    }
    
    // Update Reserve Index (Latest Reserve only)
    data.history.lastOption.foreach { sig =>
      val reserveId = sig.reserveId
      val reserveNotes = notesByReserve.get(reserveId).getOrElse(IndexedSeq.empty)
      if (!reserveNotes.contains(id)) {
        notesByReserve.put(reserveId, reserveNotes :+ id)
      }
    }
  }

  def removeNote(id: NoteId): Unit = {
    unspentNotes.get(id).foreach { data =>
      // Remove from Holder Index
      val holder = data.holder
      val holderNotes = notesByHolder.get(holder).getOrElse(IndexedSeq.empty)
      if (holderNotes.contains(id)) {
        val updated = holderNotes.filterNot(_ == id)
        if (updated.isEmpty) notesByHolder.remove(holder)
        else notesByHolder.put(holder, updated)
      }
      
      // Remove from Reserve Index
      data.history.lastOption.foreach { sig =>
        val reserveId = sig.reserveId
        val reserveNotes = notesByReserve.get(reserveId).getOrElse(IndexedSeq.empty)
        if (reserveNotes.contains(id)) {
          val updated = reserveNotes.filterNot(_ == id)
          if (updated.isEmpty) notesByReserve.remove(reserveId)
          else notesByReserve.put(reserveId, updated)
        }
      }
      
      unspentNotes.remove(id)
    }
  }


}
