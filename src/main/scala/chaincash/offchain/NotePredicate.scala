package chaincash.offchain

import TrackingTypes.{NoteData, NoteId, ReserveNftId}
import sigmastate.basics.CryptoConstants.EcPointType

trait NotePredicate {
  /**
   * Whether a note with identifier `noteId` can be accepted
   */
  def acceptable(noteId: NoteId): Boolean

  def acceptable(noteIds: Seq[NoteId]): Boolean = {
    noteIds.map(acceptable).forall(_ == true)
  }
}

// todo: tests
// Collateral or Whitelist (CoW) predicate #1
// at least 100% collateralized or current holder whitelisted
class CoW1Predicate(whitelist: Set[EcPointType]) extends NotePredicate {

  /**
   * Whether a note with identifier `noteId` can be accepted
   */
  override def acceptable(noteId: NoteId): Boolean = {
    DbEntities.unspentNotes.get(noteId) match {
      case Some(nd) =>
        val holder = nd.holder
        val goldPrice = DbEntities.state.get("goldPrice").get

        def estimateReserve(reserveNftId: ReserveNftId): (Long, Long) = {
          val rd = DbEntities.reserves.get(reserveNftId).get
          val assets = rd.reserveBox.value
          val liabilities = rd.liabilites * goldPrice.toLong
          assets -> liabilities
        }

        def reservesOk(nd: NoteData): Boolean = {
          val holderReserveOpt = DbEntities.reserveKeys.get(nd.holder)
          val holderReserve = holderReserveOpt.map(estimateReserve).getOrElse(0L -> 0L)
          val historyReserves = nd.history.map(_.reserveId).map(estimateReserve)
          val reserves: Seq[(Long, Long)] = historyReserves ++ Seq(holderReserve)
          val totalAssets = reserves.map(_._1).sum
          val totalLiabilities = reserves.map(_._2).sum
          totalAssets >= totalLiabilities
        }
        whitelist.contains(holder) || reservesOk(nd)
      case None =>
        false
    }
  }

}