package chaincash.offchain

import chaincash.offchain.TrackingTypes.ReserveNftId
import io.circe.parser.parse
import org.ergoplatform.{ErgoAddressEncoder, ErgoBox, ErgoBoxCandidate, ErgoTreePredef, JsonCodecs, P2PKAddress}

trait WalletUtils extends HttpUtils with JsonCodecs {
  val serverUrl: String

  val feeValue = 2000000

  lazy val myAddress = fetchChangeAddress()
  lazy val myPoint = myAddress.pubkey.value

  def createFeeOut(creationHeight: Int): ErgoBoxCandidate = {
    new ErgoBoxCandidate(feeValue, ErgoTreePredef.feeProposition(720), creationHeight) // 0.002 ERG
  }

  def fetchInputs(): Seq[ErgoBox] = {
    val boxesUnspentUrl = s"$serverUrl/wallet/boxes/unspent?minConfirmations=0&maxConfirmations=-1&minInclusionHeight=0&maxInclusionHeight=-1"
    val boxesUnspentJson = parse(getJsonAsString(boxesUnspentUrl)).toOption.get
    boxesUnspentJson.\\("box").map(_.as[ErgoBox].toOption.get)
  }

  def fetchNodeHeight(): Int = {
    val infoUrl = s"$serverUrl/info"
    val infoObject = parse(getJsonAsString(infoUrl)).toOption.get.asObject.get
    infoObject.apply("fullHeight").flatMap(_.asNumber).get.toInt.get
  }

  def fetchChangeAddress(): P2PKAddress = {
    val boxesUnspentUrl = s"$serverUrl/wallet/status"
    val walletStatus = parse(getJsonAsString(boxesUnspentUrl)).toOption.get.asObject.get
    val addrStrOpt = walletStatus.apply("changeAddress").flatMap(_.asString)
    val eae = new ErgoAddressEncoder(ErgoAddressEncoder.MainnetNetworkPrefix)
    val addrOpt = addrStrOpt.map(s => eae.fromString(s).get.asInstanceOf[P2PKAddress])
    addrOpt.get
  }

  def myUnspentNotes(): Seq[ErgoBox] = {
    // todo: inefficient scan through all the unspent notes here
    DbEntities.unspentNotes.filter(_._2.holder == myPoint).map(_._2.currentUtxo).materialize
  }

  def myBalance(): Long = {
    myUnspentNotes().map(_.additionalTokens.toArray.head._2).sum
  }

  def myReserveIds(): Seq[ReserveNftId] = {
    DbEntities.myReserves.materialize
  }

}
