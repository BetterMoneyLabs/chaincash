package chaincash.offchain

import chaincash.contracts.Constants
import chaincash.contracts.Constants.{noteErgoTree, reserveErgoTree}
import chaincash.offchain.TrackingTypes.NoteData
import com.google.common.primitives.Longs
import io.circe.syntax.EncoderOps
import org.ergoplatform.ErgoBox.{R4, R5, R6}
import org.ergoplatform.sdk.wallet.Constants.eip3DerivationPath
import org.ergoplatform.sdk.wallet.secrets.ExtendedSecretKey
import org.ergoplatform.sdk.wallet.settings.EncryptionSettings
import org.ergoplatform.wallet.interface4j.SecretString
import org.ergoplatform.wallet.secrets.JsonSecretStorage
import org.ergoplatform.wallet.settings.SecretStorageSettings
import org.ergoplatform.{DataInput, ErgoBoxCandidate, P2PKAddress, UnsignedErgoLikeTransaction, UnsignedInput}
import scorex.crypto.hash.Digest32
import scorex.util.encode.Base16
import sigmastate.Values.{AvlTreeConstant, ByteArrayConstant, ByteConstant, GroupElementConstant, LongConstant}
import sigmastate.eval.Colls
import sigmastate.interpreter.ContextExtension
import sigmastate.eval._
import sigmastate.serialization.GroupElementSerializer
import special.sigma.GroupElement

trait NoteUtils extends WalletUtils {
  // create note with nominal of `amountMg` mg of gold
  def createNote(amountMg: Long, ownerPubkey: GroupElement, changeAddress: P2PKAddress): Unit = {
    val inputs = fetchInputs().take(60)
    val creationHeight = inputs.map(_.creationHeight).max
    val noteTokenId = Digest32 @@ inputs.head.id.toArray

    val inputValue = inputs.map(_.value).sum
    require(inputValue >= feeValue * 21)

    val noteAmount = feeValue * 20

    val noteOut = new ErgoBoxCandidate(
      noteAmount,
      noteErgoTree,
      creationHeight,
      Colls.fromItems((Digest32Coll @@ Colls.fromArray(noteTokenId)) -> amountMg),
      Map(
        R4 -> AvlTreeConstant(Constants.emptyTree),
        R5 -> GroupElementConstant(ownerPubkey),
        R6 -> LongConstant(0)
      )
    )
    val feeOut = createFeeOut(creationHeight)
    val changeOutOpt = if(inputValue > 21 * feeValue) {
      val changeValue = inputValue - (21 * feeValue)
      Some(new ErgoBoxCandidate(changeValue, changeAddress.script, creationHeight))
    } else {
      None
    }

    val unsignedInputs = inputs.map(box => new UnsignedInput(box.id, ContextExtension.empty))
    val outs = Seq(noteOut, feeOut) ++ changeOutOpt.toSeq
    val tx = new UnsignedErgoLikeTransaction(unsignedInputs.toIndexedSeq, IndexedSeq.empty, outs.toIndexedSeq)
    println(tx.asJson)
  }

  def createNote(amountMg: Long): Unit = {
    val changeAddress = fetchChangeAddress()
    createNote(amountMg, changeAddress.pubkey.value, changeAddress)
  }

  private def readSecret(): ExtendedSecretKey ={
    val sss = SecretStorageSettings("secrets", EncryptionSettings("HmacSHA256", 128000, 256))
    val jss = JsonSecretStorage.readFile(sss).get
    jss.unlock(SecretString.create("wpass"))
    val masterKey = jss.secret.get
    masterKey.derive(eip3DerivationPath)
  }

  def sendNote(noteData: NoteData, to: GroupElement) = {
    val changeAddress = fetchChangeAddress()

    val noteInputBox = noteData.currentUtxo
    val p2pkInputs = fetchInputs().take(5) // to pay fees
    val inputs = Seq(noteInputBox) ++ p2pkInputs
    val creationHeight = inputs.map(_.creationHeight).max

    val inputValue = inputs.map(_.value).sum

    val noteRecord = noteInputBox.additionalTokens.toArray.head
    val noteTokenId = noteRecord._1
    val noteAmount = noteRecord._2

    val secret = readSecret()
    val msg: Array[Byte] = Longs.toByteArray(noteAmount) ++ noteTokenId.toArray
    val sig = SigUtils.sign(msg, secret.privateInput.w)
    secret.zeroSecret()
    val sigBytes = GroupElementSerializer.toBytes(sig._1) ++ sig._2.toByteArray

    // todo: likely should be passed from outside
    val reserveId = myReserveIds().head
    val reserveIdBytes = Base16.decode(reserveId).get
    val reserveBox = DbEntities.reserves.get(reserveId).get.reserveBox

    val prover = noteData.restoreProver
    val insertProof = prover.insert(reserveIdBytes -> sigBytes).proof
    val updTree = prover.ergoValue.getValue

    val noteOut = new ErgoBoxCandidate(
      noteInputBox.value,
      noteErgoTree,
      creationHeight,
      Colls.fromItems(noteTokenId -> noteAmount),
      Map(R4 -> AvlTreeConstant(updTree), R5 -> GroupElementConstant(to))
    )

    val noteInput = new UnsignedInput(noteInputBox.id, ContextExtension(Map(
      0.toByte -> ByteConstant(0),
      1.toByte -> GroupElementConstant(sig._1),
      2.toByte -> ByteArrayConstant(sig._2.toByteArray),
      3.toByte -> ByteArrayConstant(insertProof.bytes)
    )))

    val feeOut = createFeeOut(creationHeight)
    val changeValue = inputValue - noteOut.value - feeOut.value
    val changeOut = new ErgoBoxCandidate(changeValue, changeAddress.script, creationHeight)
    val outs = IndexedSeq(noteOut, changeOut, feeOut)

    val unsignedInputs = Seq(noteInput) ++ p2pkInputs.map(box => new UnsignedInput(box.id, ContextExtension.empty))

    val dataInputs = IndexedSeq(DataInput(reserveBox.id))

    val tx = new UnsignedErgoLikeTransaction(unsignedInputs.toIndexedSeq, dataInputs, outs.toIndexedSeq)
    println(tx.asJson)
  }

}
