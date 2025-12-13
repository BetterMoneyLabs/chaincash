package chaincash.offchain

import chaincash.contracts.Constants
import chaincash.contracts.Constants.{noteErgoTree, reserveErgoTree}
import chaincash.offchain.TrackingTypes.NoteData
import com.google.common.primitives.Longs
import io.circe.syntax.EncoderOps
import org.ergoplatform.ErgoBox.{R4, R5, R6, R7}
import org.ergoplatform.sdk.wallet.Constants.eip3DerivationPath
import org.ergoplatform.sdk.wallet.secrets.ExtendedSecretKey
import org.ergoplatform.sdk.wallet.settings.EncryptionSettings
import org.ergoplatform.wallet.interface4j.SecretString
import org.ergoplatform.wallet.secrets.JsonSecretStorage
import org.ergoplatform.wallet.settings.SecretStorageSettings
import org.ergoplatform.{DataInput, ErgoBox, ErgoBoxCandidate, P2PKAddress, UnsignedErgoLikeTransaction, UnsignedInput}
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

  def redeemNote(noteData: NoteData,
                 reserveBox: ErgoBox,
                 oracleBox: ErgoBox,
                 buyBackBox: ErgoBox,
                 proof: Array[Byte],
                 reservePosition: Long,
                 changeAddress: P2PKAddress): UnsignedErgoLikeTransaction = {

    val inputs = fetchInputs().take(5)
    val creationHeight = inputs.map(_.creationHeight).max

    val noteInput = noteData.currentUtxo
    val noteTokenId = noteInput.additionalTokens.toArray.head._1
    val noteValue = noteInput.additionalTokens.toArray.head._2

    // Calculate redemption values
    // Oracle R4 is rate in nanoErg per kg. Contract divides by 1000000 to get nanoErg per mg.
    val rate = oracleBox.additionalRegisters(R4).asInstanceOf[LongConstant].value / 1000000L
    val maxToRedeem = noteValue * rate * 98 / 100
    val reserveErgs = reserveBox.value
    val redemptionAmount = Math.min(maxToRedeem, reserveErgs) // Simple logic: redeem max possible

    // Calculate buyback fee (0.2% of redeemed amount)
    // Contract logic: toOracle = redeemed * 2 / 1000
    val buyBackFee = redemptionAmount * 2 / 1000
    // Buyback output must increase value by at least buyBackFee
    val buyBackOutValue = buyBackBox.value + buyBackFee

    val reserveOutValue = reserveErgs - redemptionAmount
    require(reserveOutValue >= 0, "Reserve underflow")

    // Outputs
    // 0. Reserve Output
    val reserveOut = new ErgoBoxCandidate(
      reserveOutValue,
      reserveErgoTree,
      creationHeight,
      reserveBox.additionalTokens,
      reserveBox.additionalRegisters
    )

    // 1. Receipt Output
    // R4: History (from Note R4), R5: Position (reservePosition implied?), R6: Height, R7: Owner (Reserve Owner)
    // Contract: receiptOut.R5[Long].get == position (Var 3 in Reserve)
    val reserveOwner = reserveBox.additionalRegisters(R4).asInstanceOf[GroupElementConstant].value
    val receiptOut = new ErgoBoxCandidate(
      noteInput.value, // value preserved from note? Contract doesn't enforce strict value on receipt, but note spending path does. Redemption path?
      // Reserve contract checks: noteValue <= maxValue.
      // Receipt contract checks: receiptOut.tokens(0) == noteInput.tokens(0)
      // Receipt needs min value.
      Constants.receiptErgoTree,
      creationHeight,
      Colls.fromItems(noteTokenId -> noteValue),
      Map(
        R4 -> noteInput.additionalRegisters(R4),
        R5 -> LongConstant(reservePosition),
        R6 -> LongConstant(creationHeight), // using creationHeight as approximate height
        R7 -> GroupElementConstant(reserveOwner)
      )
    )

    // 2. Buyback Output
    val buyBackOut = new ErgoBoxCandidate(
      buyBackOutValue,
      buyBackBox.ergoTree,
      creationHeight,
      buyBackBox.additionalTokens,
      Map.empty // Preserving registers not strictly required by verification? Contract doesn't check registers.
    )

    val feeOut = createFeeOut(creationHeight)

    // Input List: Note, Reserve, Buyback, (Wallet Inputs for Fees/Change)
    // Indices in transaction:
    // 0: Note (index passed to Note contract)
    // 1: Reserve (index passed to Note contract)
    // 2: Buyback (hardcoded in Reserve contract as INPUTS(2))
    
    // Reserve Contract expects: SELF is input.
    // Note Contract expects: 
    //   getVar[Int](1) -> Reserve Input Index
    //   getVar[Int](2) -> Receipt Output Index
    
    // We will arrange inputs: [Note, Reserve, Buyback, Wallet...]
    // Indices: Note=0, Reserve=1, Buyback=2.
    
    // We will arrange outputs: [Reserve, Receipt, Buyback, Fee, Change]
    // Indices: Reserve=0, Receipt=1, Buyback=2.
    
    val reserveIndex = 1
    val receiptIndex = 1

    val noteInputUnsigned = new UnsignedInput(noteInput.id, ContextExtension(Map(
      0.toByte -> ByteConstant(-1), // Action < 0
      // 1: Reserve Index, 2: Receipt Index
      1.toByte -> sigmastate.Values.IntConstant(reserveIndex),
      2.toByte -> sigmastate.Values.IntConstant(receiptIndex)
    )))

    // Reserve Input Context Extension
    // 0: Action (0)
    // 1: Proof
    // 2: NoteValueBytes (from NoteData history log for this reserve)
    // 3: Position
    // 4: ReceiptMode (false for note redemption)
    
    // Need to find the SigData for this reserve to get 'maxValueBytes' (valueBacked)
    val reserveNftId = reserveBox.additionalTokens.toArray.head._1
    val reserveNftIdEncoded = scorex.util.ModifierId @@ Base16.encode(reserveNftId.toArray)
    val sigData = noteData.history.find(_.reserveId == reserveNftIdEncoded).getOrElse(
       throw new IllegalArgumentException("Note is not backed by this reserve")
    )
    val maxValueBytes = Longs.toByteArray(sigData.valueBacked)

    val reserveInputUnsigned = new UnsignedInput(reserveBox.id, ContextExtension(Map(
      0.toByte -> ByteConstant(0),
      1.toByte -> ByteArrayConstant(proof),
      2.toByte -> ByteArrayConstant(maxValueBytes),
      3.toByte -> LongConstant(reservePosition),
      4.toByte -> sigmastate.Values.FalseLeaf
    )))
    
    val buyBackInputUnsigned = new UnsignedInput(buyBackBox.id, ContextExtension.empty)
    val walletInputsUnsigned = inputs.map(b => new UnsignedInput(b.id, ContextExtension.empty))
    
    val allInputs = IndexedSeq(noteInputUnsigned, reserveInputUnsigned, buyBackInputUnsigned) ++ walletInputsUnsigned
    val allOutputs = IndexedSeq(reserveOut, receiptOut, buyBackOut, feeOut)
    
    // Change
    val inputTotal = noteInput.value + reserveBox.value + buyBackBox.value + inputs.map(_.value).sum
    val outputTotal = allOutputs.map(_.value).sum
    
    if (inputTotal > outputTotal) {
      val change = new ErgoBoxCandidate(inputTotal - outputTotal, changeAddress.script, creationHeight)
      val allOutputsWithChange = allOutputs :+ change
      new UnsignedErgoLikeTransaction(allInputs, IndexedSeq(DataInput(oracleBox.id)), allOutputsWithChange)
    } else {
      new UnsignedErgoLikeTransaction(allInputs, IndexedSeq(DataInput(oracleBox.id)), allOutputs)
    }
  }

}
