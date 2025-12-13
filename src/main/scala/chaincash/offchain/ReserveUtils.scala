package chaincash.offchain

import io.circe.syntax.EncoderOps
import org.ergoplatform.ErgoBox.R4
import org.ergoplatform.{ErgoBoxCandidate, JsonCodecs, P2PKAddress, UnsignedErgoLikeTransaction, UnsignedInput}
import scorex.crypto.hash.Digest32
import sigmastate.Values.GroupElementConstant
import sigmastate.eval.Colls
import special.sigma.GroupElement
import sigmastate.eval._
import sigmastate.interpreter.ContextExtension

trait ReserveUtils extends WalletUtils with JsonCodecs {
  import chaincash.contracts.Constants.reserveErgoTree

  // create reserve with `amount` nanoerg associated with `pubkey`
  def createReserve(pubKey: GroupElement, amount: Long, changeAddress: P2PKAddress): Unit = {
    val inputs = fetchInputs().take(60)
    val creationHeight = inputs.map(_.creationHeight).max
    val reserveInputNft = Digest32 @@ inputs.head.id.toArray

    val inputValue = inputs.map(_.value).sum
    require(inputValue >= amount + feeValue)

    val reserveOut = new ErgoBoxCandidate(
      amount,
      reserveErgoTree,
      creationHeight,
      Colls.fromItems((Digest32Coll @@ Colls.fromArray(reserveInputNft)) -> 1L),
      Map(R4 -> GroupElementConstant(pubKey))
    )
    val feeOut = createFeeOut(creationHeight)
    val changeOutOpt = if(inputValue > amount + feeValue) {
      val changeValue = inputValue - (amount + feeValue)
      Some(new ErgoBoxCandidate(changeValue, changeAddress.script, creationHeight))
    } else {
      None
    }

    val unsignedInputs = inputs.map(box => new UnsignedInput(box.id, ContextExtension.empty))
    val outs = Seq(reserveOut, feeOut) ++ changeOutOpt.toSeq
    val tx = new UnsignedErgoLikeTransaction(unsignedInputs.toIndexedSeq, IndexedSeq.empty, outs.toIndexedSeq)
    println(tx.asJson)
  }

  def createReserve(address: P2PKAddress, amount: Long): Unit = {
    createReserve(address.pubkey.value, amount, address)
  }

  def createReserve(amount: Long): Unit = {
    val changeAddress = fetchChangeAddress()
    createReserve(changeAddress, amount)
  }

}
