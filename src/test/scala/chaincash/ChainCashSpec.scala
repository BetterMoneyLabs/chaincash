package chaincash

import chaincash.contracts.Constants
import chaincash.contracts.Constants.chainCashPlasmaParameters
import chaincash.offchain.SigUtils
import com.google.common.primitives.Longs
import org.ergoplatform.P2PKAddress
import org.ergoplatform.appkit.impl.{ErgoScriptContract, ErgoTreeContract, OutBoxImpl}
import org.ergoplatform.appkit.{AppkitHelpers, BlockchainContext, ConstantsBuilder, ContextVar, ErgoValue, InputBox, NetworkType, OutBox, OutBoxBuilder, SignedTransaction}
import org.ergoplatform.sdk.ErgoToken
import org.scalatest.{Matchers, PropSpec}
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import scorex.crypto.hash.Blake2b256
import scorex.util.encode.{Base16, Base64}
import sigmastate.AvlTreeFlags
import sigmastate.basics.DLogProtocol.ProveDlog
import sigmastate.eval._
import sigmastate.serialization.GroupElementSerializer
import special.sigma.{AvlTree, GroupElement}
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.{PlasmaMap, Proof}

import collection.JavaConverters._
import java.util
import scala.util.Try

class ChainCashSpec extends PropSpec with Matchers with ScalaCheckDrivenPropertyChecks with HttpClientTesting {

  implicit val addrEncoder = Constants.ergoAddressEncoder

  val fakeScript = "sigmaProp(true)"

  val emptyTree = Constants.emptyTree

  val noteTokenId = "4b2d8b7beb3eaac8234d9e61792d270898a43934d6a27275e4f3a044609c9f2a"

  val oracleNFT = "3c45f29a5165b030fdb5eaf5d81f8108f9d8f507b31487dd51f4ae08fe07cf4a"
  val oracleNFTBytes = Base16.decode(oracleNFT).get

  val reserveNFT = "161A3A5250655368566D597133743677397A24432646294A404D635166546A57"
  val reserveNFTBytes = Base16.decode(reserveNFT).get

  val buyBackNftId = "bf24ed4af7eb5a7839c43aa6b240697d81b196120c837e1a941832c266d3755c"

  val holderSecret = SigUtils.randBigInt
  val holderPk = Constants.g.exp(holderSecret.bigInteger)
  val changeAddress = P2PKAddress(ProveDlog(holderPk)).toString()

  val minValue = 1000000000L
  val feeValue = 1000000L

  val noteValue: Long = 1

  val fakeTxId1 = "f9e5ce5aa0d95f5d54a7bc89c46730d9662397067250aa18a0039631c0f5b809"
  val fakeTxId2 = "f9e5ce5aa0d95f5d54a7bc89c46730d9662397067250aa18a0039631c0f5b808"
  val fakeTxId3 = "f9e5ce5aa0d95f5d54a7bc89c46730d9662397067250aa18a0039631c0f5b807"
  val fakeTxId4 = "f9e5ce5aa0d95f5d54a7bc89c46730d9662397067250aa18a0039631c0f5b806"
  val fakeTxId5 = "f9e5ce5aa0d95f5d54a7bc89c46730d9662397067250aa18a0039631c0f5b105"
  val fakeIndex = 1.toShort

  def trueScript = "sigmaProp(true)"
  def trueErgoTree = Constants.compile(trueScript)
  def trueErgoContract = new ErgoTreeContract(trueErgoTree, Constants.networkType)

  def createOut(contract: String,
                value: Long,
                registers: Array[ErgoValue[_]],
                tokens: Array[ErgoToken])(implicit ctx: BlockchainContext): OutBoxImpl = {
    val c = ErgoScriptContract.create(new org.ergoplatform.appkit.Constants, contract, Constants.networkType)
    val ebc = AppkitHelpers.createBoxCandidate(value, c.getErgoTree, tokens, registers, ctx.getHeight)
    new OutBoxImpl(ebc)
  }

  private def addTokens(outBoxBuilder: OutBoxBuilder)(tokens: java.util.List[ErgoToken]) = {
    if (tokens.isEmpty) outBoxBuilder
    else {
      outBoxBuilder.tokens(tokens.asScala: _*)
    }
  }

  private def addRegisters(
                            outBoxBuilder: OutBoxBuilder
                          )(registers: java.util.List[ErgoValue[_]]) = {
    if (registers.isEmpty) outBoxBuilder
    else {
      outBoxBuilder.registers(registers.asScala: _*)
    }
  }

  def getAddressFromString(string: String) =
    Try(Constants.ergoAddressEncoder.fromString(string).get).getOrElse(throw new Exception(s"Invalid address [$string]"))


  def decodeBigInt(encoded: String): BigInt = Try(BigInt(encoded, 10)).recover { case ex => BigInt(encoded, 16) }.get

  def createTx(
                inputBoxes: Array[InputBox],
                dataInputs: Array[InputBox],
                boxesToCreate: Array[OutBoxImpl],
                fee: Option[Long],
                changeAddress: String,
                proveDlogSecrets: Array[String],
                broadcast: Boolean
              )(implicit ctx: BlockchainContext): SignedTransaction = {
    val txB = ctx.newTxBuilder
    val outputBoxes: Array[OutBox] = boxesToCreate.map { box =>
      val outBoxBuilder: OutBoxBuilder = txB
        .outBoxBuilder()
        .value(box.getValue())
        .creationHeight(box.getCreationHeight())
        .contract(new ErgoTreeContract(box.getErgoTree(), NetworkType.MAINNET))
      val outBoxBuilderWithTokens: OutBoxBuilder =
        addTokens(outBoxBuilder)(box.getTokens())
      val outBox: OutBox =
        addRegisters(outBoxBuilderWithTokens)(box.getRegisters()).build
      outBox
    }
    val inputs = new util.ArrayList[InputBox]()

    inputBoxes.foreach(inputs.add)

    val dataInputBoxes = new util.ArrayList[InputBox]()

    dataInputs.foreach(dataInputBoxes.add)

    val txToSignNoFee = ctx
      .newTxBuilder()
      .boxesToSpend(inputs)
      .withDataInputs(dataInputBoxes)
      .outputs(outputBoxes: _*)
      .sendChangeTo(getAddressFromString(changeAddress))

    val txToSign = (if(fee.isDefined){
      txToSignNoFee.fee(fee.get)
    } else {
      txToSignNoFee
    }).build()

    val proveDlogSecretsBigInt = proveDlogSecrets.map(decodeBigInt)

    val dlogProver = proveDlogSecretsBigInt.foldLeft(ctx.newProverBuilder()) {
      case (oldProverBuilder, newDlogSecret) =>
        oldProverBuilder.withDLogSecret(newDlogSecret.bigInteger)
    }

    val signedTx = dlogProver.build().sign(txToSign)
    if (broadcast) ctx.sendTransaction(signedTx)
    signedTx
  }

  property("spending should work - no change") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>

      val position = 0L

      val positionBytes = Longs.toByteArray(position)

      val msg: Array[Byte] = positionBytes ++ Longs.toByteArray(noteValue) ++ Base16.decode(noteTokenId).get
      val sig = SigUtils.sign(msg, holderSecret)

      val plasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
      val sigBytes = GroupElementSerializer.toBytes(sig._1) ++ sig._2.toByteArray
      val key = positionBytes ++ reserveNFTBytes
      val insertRes = plasmaMap.insert(key -> sigBytes)
      val insertProof = insertRes.proof
      val outTree = plasmaMap.ergoValue.getValue

      val noteInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue)
          .tokens(new ErgoToken(noteTokenId, noteValue))
          .registers(Constants.emptyTreeErgoValue, ErgoValue.of(holderPk), ErgoValue.of(position))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.noteContract))
          .build()
          .convertToInputWith(fakeTxId1, fakeIndex)
          .withContextVars(
            new ContextVar(0, ErgoValue.of(0: Byte)),
            new ContextVar(1, ErgoValue.of(sig._1)),
            new ContextVar(2, ErgoValue.of(sig._2.toByteArray)),
            new ContextVar(3, ErgoValue.of(insertProof.bytes))
          )

      val reserveDataInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue)
          .tokens(new ErgoToken(reserveNFTBytes, 1))
          .registers(ErgoValue.of(holderPk))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.reserveContract))
          .build()
          .convertToInputWith(fakeTxId2, fakeIndex)

      val noteOutput = createOut(
        Constants.noteContract,
        minValue,
        Array(ErgoValue.of(outTree), ErgoValue.of(holderPk), ErgoValue.of(position + 1)),
        Array(new ErgoToken(noteTokenId, 1))
      )

      val inputs = Array[InputBox](noteInput)
      val dataInputs = Array[InputBox](reserveDataInput)
      val outputs = Array[OutBoxImpl](noteOutput)

      noException shouldBe thrownBy {
        createTx(
          inputs,
          dataInputs,
          outputs,
          fee = None,
          changeAddress,
          Array[String](holderSecret.toString()),
          false
        )
      }
    }
  }

  property("redemption should work") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val position = 0L

      val positionBytes = Longs.toByteArray(position)
      val msg: Array[Byte] = positionBytes ++ Longs.toByteArray(noteValue) ++ Base16.decode(noteTokenId).get
      val sig = SigUtils.sign(msg, holderSecret)

      val plasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
      val sigBytes = GroupElementSerializer.toBytes(sig._1) ++ sig._2.toByteArray
      val keyBytes = positionBytes ++ reserveNFTBytes
      val insertRes = plasmaMap.insert(keyBytes -> sigBytes)
      val _ = insertRes.proof
      val historyTree = plasmaMap.ergoValue.getValue

      val lookupRes = plasmaMap.lookUp(keyBytes)
      val lookupProof = lookupRes.proof

      val noteInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue + feeValue)
          .tokens(new ErgoToken(noteTokenId, noteValue))
          .registers(ErgoValue.of(historyTree), ErgoValue.of(holderPk))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.noteContract))
          .build()
          .convertToInputWith(fakeTxId1, fakeIndex)
          .withContextVars(
            new ContextVar(0, ErgoValue.of(-1: Byte))
          )

      val reserveInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue)
          .tokens(new ErgoToken(reserveNFTBytes, 1))
          .registers(ErgoValue.of(holderPk))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.reserveContract))
          .build()
          .convertToInputWith(fakeTxId2, fakeIndex)
          .withContextVars(
            new ContextVar(0, ErgoValue.of(0: Byte)),
            new ContextVar(1, ErgoValue.of(lookupProof.bytes)),
            new ContextVar(2, ErgoValue.of(Longs.toByteArray(noteValue))),
            new ContextVar(3, ErgoValue.of(position)),
            new ContextVar(4, ErgoValue.of(false))
          )

      val buyBackInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue)
          .tokens(new ErgoToken(buyBackNftId, 1))
          .contract(trueErgoContract)
          .build()
          .convertToInputWith(fakeTxId5, fakeIndex)

      val oracleRate = 500000L // nanoErg per mg
      val oracleDataInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue)
          .tokens(new ErgoToken(oracleNFTBytes, 1))
          .registers(ErgoValue.of(oracleRate * 1000000))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), "{false}"))
          .build()
          .convertToInputWith(fakeTxId3, fakeIndex)

      val reserveOutput = createOut(
        Constants.reserveContract,
        minValue - oracleRate * 98 / 100,
        registers = Array(ErgoValue.of(holderPk)),
        tokens = Array(new ErgoToken(reserveNFT, 1))
      )

      val receiptOutput = createOut(
        Constants.receiptContract,
        minValue,
        registers = Array(ErgoValue.of(historyTree), ErgoValue.of(0L), ErgoValue.of(ctx.getHeight - 5), ErgoValue.of(holderPk)),
        tokens = Array(new ErgoToken(noteTokenId, noteValue))
      )

      val buyBackOutput = createOut(
        trueScript,
        buyBackInput.getValue + (oracleRate * 98 / 100) * 2 / 1000,
        registers = Array(),
        tokens = Array(new ErgoToken(buyBackNftId, 1))
      )

      val inputs = Array[InputBox](noteInput, reserveInput, buyBackInput)
      val dataInputs = Array[InputBox](oracleDataInput)
      val outputs = Array[OutBoxImpl](reserveOutput, receiptOutput, buyBackOutput)

      createTx(
        inputs,
        dataInputs,
        outputs,
        fee = None ,
        changeAddress,
        Array[String](holderSecret.toString()),
        false
      )
    }
  }

  property("chain of redemptions") {
    val reserveASecret = SigUtils.randBigInt
    val reserveAPk = Constants.g.exp(reserveASecret.bigInteger)
    val reserveBSecret = SigUtils.randBigInt
    val reserveBPk = Constants.g.exp(reserveBSecret.bigInteger)
    val reserveCSecret = SigUtils.randBigInt

    val reserveANFT = "121A3A5250655368566D597133743677397A24432646294A404D635166546A57"
    val reserveANFTBytes = Base16.decode(reserveANFT).get
    val reserveBNFT = "221A3A5250655368566D597133743677397A24432646294A404D635166546A57"
    val reserveBNFTBytes = Base16.decode(reserveBNFT).get
    val reserveCNFT = "321A3A5250655368566D597133743677397A24432646294A404D635166546A57"
    val reserveCNFTBytes = Base16.decode(reserveCNFT).get

    val holderSecret = SigUtils.randBigInt
    val holderPk = Constants.g.exp(holderSecret.bigInteger)

    // forming history A -> B -> C -> current holder
    val aPosition = 0L
    val aPositionBytes = Longs.toByteArray(aPosition)
    val aValue = 100L
    val msg0: Array[Byte] = aPositionBytes ++ Longs.toByteArray(100) ++ Base16.decode(noteTokenId).get
    val sig0 = SigUtils.sign(msg0, reserveASecret)
    val sig0Bytes = GroupElementSerializer.toBytes(sig0._1) ++ sig0._2.toByteArray

    val bPosition = 1L
    val bPositionBytes = Longs.toByteArray(bPosition)
    val bValue = 50L
    val msg1: Array[Byte] = bPositionBytes ++ Longs.toByteArray(bValue) ++ Base16.decode(noteTokenId).get
    val sig1 = SigUtils.sign(msg1, reserveBSecret)
    val sig1Bytes = GroupElementSerializer.toBytes(sig1._1) ++ sig1._2.toByteArray

    val finalNoteValue = 1
    val cPosition = 2L
    val cPositionBytes = Longs.toByteArray(cPosition)
    val msg2: Array[Byte] = cPositionBytes ++ Longs.toByteArray(finalNoteValue) ++ Base16.decode(noteTokenId).get
    val sig2 = SigUtils.sign(msg2, reserveCSecret)
    val sig2Bytes = GroupElementSerializer.toBytes(sig2._1) ++ sig2._2.toByteArray

    val plasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
    val keyABytes = aPositionBytes ++ reserveANFTBytes
    val keyBBytes = bPositionBytes ++ reserveBNFTBytes
    val keyCBytes = cPositionBytes ++ reserveCNFTBytes
    val insertRes = plasmaMap.insert(Seq(keyABytes -> sig0Bytes, keyBBytes -> sig1Bytes, keyCBytes -> sig2Bytes):_*)
    val _ = insertRes.proof
    val historyTree = plasmaMap.ergoValue.getValue

    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      // we test chain of two redemptions here, first, holder of the note is redeeming against reserve B, and then
      // owner of B is redeeming against reserve A

      val fundingBox =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(2 * minValue + feeValue)
          .contract(ctx.compileContract(ConstantsBuilder.empty(), fakeScript))
          .build()
          .convertToInputWith(fakeTxId1, fakeIndex)


      val oracleRate = 500000L // nanoErg per mg
      val oracleDataInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue)
          .tokens(new ErgoToken(oracleNFTBytes, 1))
          .registers(ErgoValue.of(oracleRate * 1000000))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), "{false}"))
          .build()
          .convertToInputWith(fakeTxId3, fakeIndex)

      val noteInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue + feeValue)
          .tokens(new ErgoToken(noteTokenId, finalNoteValue))
          .registers(ErgoValue.of(historyTree), ErgoValue.of(holderPk))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.noteContract))
          .build()
          .convertToInputWith(fakeTxId1, fakeIndex)
          .withContextVars(
            new ContextVar(0, ErgoValue.of(-1: Byte))
          )

      val lookupBRes = plasmaMap.lookUp(keyBBytes)
      val lookupBProof = lookupBRes.proof

      val reserveBInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue)
          .tokens(new ErgoToken(reserveBNFT, 1))
          .registers(ErgoValue.of(reserveBPk))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.reserveContract))
          .build()
          .convertToInputWith(fakeTxId2, fakeIndex)
          .withContextVars(
            new ContextVar(0, ErgoValue.of(0: Byte)),
            new ContextVar(1, ErgoValue.of(lookupBProof.bytes)),
            new ContextVar(2, ErgoValue.of(Longs.toByteArray(bValue))),
            new ContextVar(3, ErgoValue.of(bPosition)),
            new ContextVar(4, ErgoValue.of(false))
          )

      val buyBackInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue)
          .tokens(new ErgoToken(buyBackNftId, 1))
          .contract(trueErgoContract)
          .build()
          .convertToInputWith(fakeTxId5, fakeIndex)

      val reserveOutput = createOut(
        Constants.reserveContract,
        minValue - oracleRate * 98 / 100,
        registers = Array(ErgoValue.of(reserveBPk)),
        tokens = Array(new ErgoToken(reserveBNFT, 1))
      )

      val receiptOutput = createOut(
        Constants.receiptContract,
        minValue,
        registers = Array(ErgoValue.of(historyTree), ErgoValue.of(bPosition), ErgoValue.of(ctx.getHeight - 5), ErgoValue.of(reserveBPk)),
        tokens = Array(new ErgoToken(noteTokenId, finalNoteValue))
      )

      val buyBackOutput = createOut(
        trueScript,
        buyBackInput.getValue + (oracleRate * 98 / 100) * 2 / 1000,
        registers = Array(),
        tokens = Array(new ErgoToken(buyBackNftId, 1))
      )


      val inputs = Array[InputBox](noteInput, reserveBInput, buyBackInput)
      val dataInputs = Array[InputBox](oracleDataInput)
      val outputs = Array[OutBoxImpl](reserveOutput, receiptOutput, buyBackOutput)

      val firstTx = createTx(
        inputs,
        dataInputs,
        outputs,
        fee = None ,
        changeAddress,
        Array[String](holderSecret.toString()),
        false
      )

      val receiptInput = firstTx.getOutputsToSpend.get(1)

      val lookupARes = plasmaMap.lookUp(keyABytes)
      val lookupAProof = lookupARes.proof

      val reserveAInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue)
          .tokens(new ErgoToken(reserveANFT, 1))
          .registers(ErgoValue.of(reserveAPk))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.reserveContract))
          .build()
          .convertToInputWith(fakeTxId5, fakeIndex)
          .withContextVars(
            new ContextVar(0, ErgoValue.of(0: Byte)),
            new ContextVar(1, ErgoValue.of(lookupAProof.bytes)),
            new ContextVar(2, ErgoValue.of(Longs.toByteArray(aValue))),
            new ContextVar(3, ErgoValue.of(aPosition)),
            new ContextVar(4, ErgoValue.of(true))
          )

      val reserveAOutput = createOut(
        Constants.reserveContract,
        minValue - oracleRate * 98 / 100,
        registers = Array(ErgoValue.of(reserveAPk)),
        tokens = Array(new ErgoToken(reserveANFT, 1))
      )

      val receiptAOutput = createOut(
        Constants.receiptContract,
        minValue,
        registers = Array(ErgoValue.of(historyTree), ErgoValue.of(aPosition), ErgoValue.of(ctx.getHeight - 5), ErgoValue.of(reserveAPk)),
        tokens = Array(new ErgoToken(noteTokenId, finalNoteValue))
      )

      val inputs2 = Array[InputBox](receiptInput, reserveAInput, buyBackInput, fundingBox)
      val dataInputs2 = Array[InputBox](oracleDataInput)
      val outputs2 = Array[OutBoxImpl](reserveAOutput, receiptAOutput, buyBackOutput)

      createTx(
        inputs2,
        dataInputs2,
        outputs2,
        fee = Some(1000000) ,
        changeAddress,
        Array[String](reserveBSecret.toString()),
        false
      )
    }
  }

  property("chain of redemptions - fail if trying to spend receipt from later reserve") {
    val reserveASecret = SigUtils.randBigInt
    val reserveAPk = Constants.g.exp(reserveASecret.bigInteger)
    val reserveBSecret = SigUtils.randBigInt
    val reserveBPk = Constants.g.exp(reserveBSecret.bigInteger)
    val reserveCSecret = SigUtils.randBigInt
    val reserveCPk = Constants.g.exp(reserveCSecret.bigInteger)

    val reserveANFT = "121A3A5250655368566D597133743677397A24432646294A404D635166546A57"
    val reserveANFTBytes = Base16.decode(reserveANFT).get
    val reserveBNFT = "221A3A5250655368566D597133743677397A24432646294A404D635166546A57"
    val reserveBNFTBytes = Base16.decode(reserveBNFT).get
    val reserveCNFT = "321A3A5250655368566D597133743677397A24432646294A404D635166546A57"
    val reserveCNFTBytes = Base16.decode(reserveCNFT).get

    val holderSecret = SigUtils.randBigInt
    val holderPk = Constants.g.exp(holderSecret.bigInteger)

    // forming history A -> B -> C -> current holder
    val aPosition = 0L
    val aPositionBytes = Longs.toByteArray(aPosition)
    val aValue = 100L
    val msg0: Array[Byte] = aPositionBytes ++ Longs.toByteArray(100) ++ Base16.decode(noteTokenId).get
    val sig0 = SigUtils.sign(msg0, reserveASecret)
    val sig0Bytes = GroupElementSerializer.toBytes(sig0._1) ++ sig0._2.toByteArray

    val bPosition = 1L
    val bPositionBytes = Longs.toByteArray(bPosition)
    val bValue = 50L
    val msg1: Array[Byte] = bPositionBytes ++ Longs.toByteArray(bValue) ++ Base16.decode(noteTokenId).get
    val sig1 = SigUtils.sign(msg1, reserveBSecret)
    val sig1Bytes = GroupElementSerializer.toBytes(sig1._1) ++ sig1._2.toByteArray

    val finalNoteValue = 1
    val cPosition = 2L
    val cPositionBytes = Longs.toByteArray(cPosition)
    val msg2: Array[Byte] = cPositionBytes ++ Longs.toByteArray(finalNoteValue) ++ Base16.decode(noteTokenId).get
    val sig2 = SigUtils.sign(msg2, reserveCSecret)
    val sig2Bytes = GroupElementSerializer.toBytes(sig2._1) ++ sig2._2.toByteArray

    val plasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
    val keyABytes = aPositionBytes ++ reserveANFTBytes
    val keyBBytes = bPositionBytes ++ reserveBNFTBytes
    val keyCBytes = cPositionBytes ++ reserveCNFTBytes
    val insertRes = plasmaMap.insert(Seq(keyABytes -> sig0Bytes, keyBBytes -> sig1Bytes, keyCBytes -> sig2Bytes):_*)
    val _ = insertRes.proof
    val historyTree = plasmaMap.ergoValue.getValue

    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val fundingBox =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(2 * minValue + feeValue)
          .contract(ctx.compileContract(ConstantsBuilder.empty(), fakeScript))
          .build()
          .convertToInputWith(fakeTxId1, fakeIndex)

      val oracleRate = 500000L // nanoErg per mg
      val oracleDataInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue)
          .tokens(new ErgoToken(oracleNFTBytes, 1))
          .registers(ErgoValue.of(oracleRate * 1000000))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), "{false}"))
          .build()
          .convertToInputWith(fakeTxId3, fakeIndex)

      // Try to redeem receipt of B against reserve C (which should fail)
      val receiptInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue)
          .registers(ErgoValue.of(historyTree), ErgoValue.of(bPosition), ErgoValue.of(ctx.getHeight - 5), ErgoValue.of(reserveBPk))
          .tokens(new ErgoToken(noteTokenId, bValue))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.receiptContract))
          .build()
          .convertToInputWith(fakeTxId4, fakeIndex)

      val lookupCRes = plasmaMap.lookUp(keyCBytes)
      val lookupCProof = lookupCRes.proof

      val reserveCInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue)
          .tokens(new ErgoToken(reserveCNFT, 1))
          .registers(ErgoValue.of(reserveCPk))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.reserveContract))
          .build()
          .convertToInputWith(fakeTxId5, fakeIndex)
          .withContextVars(
            new ContextVar(0, ErgoValue.of(0: Byte)),
            new ContextVar(1, ErgoValue.of(lookupCProof.bytes)),
            new ContextVar(2, ErgoValue.of(Longs.toByteArray(finalNoteValue))),
            new ContextVar(3, ErgoValue.of(cPosition)),
            new ContextVar(4, ErgoValue.of(true))
          )

      val buyBackInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue)
          .tokens(new ErgoToken(buyBackNftId, 1))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), trueScript))
          .build()
          .convertToInputWith(fakeTxId2, fakeIndex)

      val reserveCOutput = createOut(
        Constants.reserveContract,
        minValue - oracleRate * 98 / 100,
        registers = Array(ErgoValue.of(reserveCPk)),
        tokens = Array(new ErgoToken(reserveCNFT, 1))
      )

      val receiptCOutput = createOut(
        Constants.receiptContract,
        minValue,
        registers = Array(ErgoValue.of(historyTree), ErgoValue.of(cPosition), ErgoValue.of(ctx.getHeight - 5), ErgoValue.of(reserveCPk)),
        tokens = Array(new ErgoToken(noteTokenId, finalNoteValue))
      )

      val buyBackOutput = createOut(
        trueScript,
        buyBackInput.getValue + (oracleRate * 98 / 100) * 2 / 1000,
        registers = Array(),
        tokens = Array(new ErgoToken(buyBackNftId, 1))
      )

      val inputs = Array[InputBox](receiptInput, reserveCInput, buyBackInput, fundingBox)
      val dataInputs = Array[InputBox](oracleDataInput)
      val outputs = Array[OutBoxImpl](reserveCOutput, receiptCOutput, buyBackOutput)

      an[Exception] should be thrownBy createTx(
        inputs,
        dataInputs,
        outputs,
        fee = Some(1000000),
        changeAddress,
        Array[String](reserveBSecret.toString()),
        false
      )
    }
  }

  property("mutual credit clearing") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val fundingBox =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(200 * minValue + feeValue)
          .contract(ctx.compileContract(ConstantsBuilder.empty(), fakeScript))
          .build()
          .convertToInputWith(fakeTxId5, fakeIndex)

      val position = 0L
      val positionBytes = Longs.toByteArray(position)

      val reserve1NFT = "261A3A5250655368566D597133743677397A24432646294A404D635166546A57"
      val reserve1NFTBytes = Base16.decode(reserve1NFT).get

      val reserve2NFT = "361A3A5250655368566D597133743677397A24432646294A404D635166546A57"
      val reserve2NFTBytes = Base16.decode(reserve2NFT).get

      val note1TokenId = "6b2d8b7beb3eaac8234d9e61792d270898a43934d6a27275e4f3a044609c9f2a"
      val note2TokenId = "8b2d8b7beb3eaac8234d9e61792d270898a43934d6a27275e4f3a044609c9f2a"

      val holder1Secret = SigUtils.randBigInt
      val holder1Pk = Constants.g.exp(holder1Secret.bigInteger)
      val holder2Secret = SigUtils.randBigInt
      val holder2Pk = Constants.g.exp(holder2Secret.bigInteger)

      val msg1: Array[Byte] = positionBytes ++ Longs.toByteArray(noteValue) ++ Base16.decode(note1TokenId).get
      val sig1 = SigUtils.sign(msg1, holder1Secret)

      val msg2: Array[Byte] = positionBytes ++ Longs.toByteArray(noteValue) ++ Base16.decode(note2TokenId).get
      val sig2 = SigUtils.sign(msg2, holder2Secret)

      val plasmaMap1 = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
      val sig1Bytes = GroupElementSerializer.toBytes(sig1._1) ++ sig1._2.toByteArray
      val key1Bytes = positionBytes ++ reserve1NFTBytes
      val insertRes1 = plasmaMap1.insert(key1Bytes -> sig1Bytes)
      val i1 = insertRes1.proof
      val historyTree1 = plasmaMap1.ergoValue.getValue
      val lookupRes1 = plasmaMap1.lookUp(key1Bytes)
      val lookupProof1 = lookupRes1.proof

      val plasmaMap2 = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
      val key2Bytes = positionBytes ++ reserve2NFTBytes
      val sig2Bytes = GroupElementSerializer.toBytes(sig2._1) ++ sig2._2.toByteArray
      val insertRes2 = plasmaMap2.insert(key2Bytes -> sig2Bytes)
      val i2 = insertRes2.proof
      val historyTree2 = plasmaMap2.ergoValue.getValue
      val lookupRes2 = plasmaMap2.lookUp(key2Bytes)
      val lookupProof2 = lookupRes2.proof

      val note1Input =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue + feeValue)
          .tokens(new ErgoToken(note1TokenId, noteValue))
          .registers(ErgoValue.of(historyTree1), ErgoValue.of(holder1Pk))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.noteContract))
          .build()
          .convertToInputWith(fakeTxId1, fakeIndex)
          .withContextVars(
            new ContextVar(0, ErgoValue.of(-2: Byte))
          )

      val note2Input =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue + feeValue)
          .tokens(new ErgoToken(note2TokenId, noteValue))
          .registers(ErgoValue.of(historyTree2), ErgoValue.of(holder2Pk))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.noteContract))
          .build()
          .convertToInputWith(fakeTxId2, fakeIndex)
          .withContextVars(
            new ContextVar(0, ErgoValue.of(-3: Byte))
          )

      val reserve1Input =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue)
          .tokens(new ErgoToken(reserve1NFTBytes, 1))
          .registers(ErgoValue.of(holder1Pk))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.reserveContract))
          .build()
          .convertToInputWith(fakeTxId3, fakeIndex)
          .withContextVars(
            new ContextVar(0, ErgoValue.of(0: Byte)),
            new ContextVar(1, ErgoValue.of(lookupProof1.bytes)),
            new ContextVar(2, ErgoValue.of(Longs.toByteArray(noteValue))),
            new ContextVar(3, ErgoValue.of(position)),
            new ContextVar(4, ErgoValue.of(false)),
            new ContextVar(5, ErgoValue.of(2))
          )

      val reserve2Input =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue)
          .tokens(new ErgoToken(reserve2NFTBytes, 1))
          .registers(ErgoValue.of(holder2Pk))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.reserveContract))
          .build()
          .convertToInputWith(fakeTxId4, fakeIndex)
          .withContextVars(
            new ContextVar(0, ErgoValue.of(1: Byte)),
            new ContextVar(1, ErgoValue.of(lookupProof2.bytes)),
            new ContextVar(2, ErgoValue.of(Longs.toByteArray(noteValue))),
            new ContextVar(3, ErgoValue.of(position)),
            new ContextVar(4, ErgoValue.of(false)),
            new ContextVar(5, ErgoValue.of(3))
          )

      val oracleRate = 500000L // nanoErg per mg
      val oracleDataInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue)
          .tokens(new ErgoToken(oracleNFTBytes, 1))
          .registers(ErgoValue.of(oracleRate * 1000000))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), "{false}"))
          .build()
          .convertToInputWith(fakeTxId3, fakeIndex)

      val reserve1Output = createOut(
        Constants.reserveContract,
        minValue,
        registers = Array(ErgoValue.of(holder1Pk)),
        tokens = Array(new ErgoToken(reserve1NFT, 1))
      )

      val reserve2Output = createOut(
        Constants.reserveContract,
        minValue,
        registers = Array(ErgoValue.of(holder2Pk)),
        tokens = Array(new ErgoToken(reserve2NFT, 1))
      )

      val receipt1Output = createOut(
        Constants.receiptContract,
        minValue,
        registers = Array(ErgoValue.of(historyTree1), ErgoValue.of(0L), ErgoValue.of(ctx.getHeight - 5), ErgoValue.of(holder1Pk)),
        tokens = Array(new ErgoToken(note1TokenId, noteValue))
      )

      val receipt2Output = createOut(
        Constants.receiptContract,
        minValue,
        registers = Array(ErgoValue.of(historyTree2), ErgoValue.of(0L), ErgoValue.of(ctx.getHeight - 5), ErgoValue.of(holder2Pk)),
        tokens = Array(new ErgoToken(note2TokenId, noteValue))
      )

      val inputs = Array[InputBox](note1Input, note2Input, reserve1Input, reserve2Input, fundingBox)
      val dataInputs = Array[InputBox](oracleDataInput)
      val outputs = Array[OutBoxImpl](reserve1Output, reserve2Output, receipt1Output, receipt2Output)

      createTx(
        inputs,
        dataInputs,
        outputs,
        fee = None ,
        changeAddress,
        Array[String](holder1Secret.toString(), holder2Secret.toString()),
        false
      )
    }
  }

  property("spending should work - multiple notes and change") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>

      val firstPosition = 0L
      val firstPositionBytes = Longs.toByteArray(firstPosition)
      val secondPosition = 10L
      val secondPositionBytes = Longs.toByteArray(secondPosition)

      val firstNoteTokenId = noteTokenId
      val secondNoteTokenId = Base16.encode(Blake2b256.apply(noteTokenId))

      val firstNoteValue = 55
      val secondNoteValue = 60

      val msg1: Array[Byte] = firstPositionBytes ++ Longs.toByteArray(firstNoteValue) ++ Base16.decode(firstNoteTokenId).get
      val msg2: Array[Byte] = secondPositionBytes ++ Longs.toByteArray(secondNoteValue) ++ Base16.decode(secondNoteTokenId).get
      val sig1 = SigUtils.sign(msg1, holderSecret)
      val sig2 = SigUtils.sign(msg2, holderSecret)

      def insertToEmptyTree(sig: (GroupElement, BigInt), positionBytes: Array[Byte]): (Proof, AvlTree) = {
        val plasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
        val sigBytes = GroupElementSerializer.toBytes(sig._1) ++ sig._2.toByteArray
        val keyBytes = positionBytes ++ reserveNFTBytes
        val insertRes = plasmaMap.insert(keyBytes -> sigBytes)
        val insertProof = insertRes.proof
        val outTree = plasmaMap.ergoValue.getValue
        insertProof -> outTree
      }

      val (insertProof1, outTree1) = insertToEmptyTree(sig1, firstPositionBytes)
      val (insertProof2, outTree2) = insertToEmptyTree(sig2, secondPositionBytes)

      val firstNoteInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue)
          .tokens(new ErgoToken(firstNoteTokenId, firstNoteValue))
          .registers(Constants.emptyTreeErgoValue, ErgoValue.of(holderPk), ErgoValue.of(firstPosition))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.noteContract))
          .build()
          .convertToInputWith(fakeTxId1, fakeIndex)
          .withContextVars(
            new ContextVar(0, ErgoValue.of(0: Byte)),
            new ContextVar(1, ErgoValue.of(sig1._1)),
            new ContextVar(2, ErgoValue.of(sig1._2.toByteArray)),
            new ContextVar(3, ErgoValue.of(insertProof1.bytes)),
            new ContextVar(4, ErgoValue.of(2: Byte))
          )

      val secondNoteInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue)
          .tokens(new ErgoToken(secondNoteTokenId, secondNoteValue))
          .registers(Constants.emptyTreeErgoValue, ErgoValue.of(holderPk), ErgoValue.of(secondPosition))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.noteContract))
          .build()
          .convertToInputWith(fakeTxId1, fakeIndex)
          .withContextVars(
            new ContextVar(0, ErgoValue.of(1: Byte)),
            new ContextVar(1, ErgoValue.of(sig2._1)),
            new ContextVar(2, ErgoValue.of(sig2._2.toByteArray)),
            new ContextVar(3, ErgoValue.of(insertProof2.bytes)),
            new ContextVar(4, ErgoValue.of(3: Byte))
          )

      val fundingBox =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(2 * minValue + feeValue)
          .contract(ctx.compileContract(ConstantsBuilder.empty(), fakeScript))
          .build()
          .convertToInputWith(fakeTxId1, fakeIndex)

      val reserveDataInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue)
          .tokens(new ErgoToken(reserveNFTBytes, 1))
          .registers(ErgoValue.of(holderPk))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.reserveContract))
          .build()
          .convertToInputWith(fakeTxId2, fakeIndex)

      val note1Output = createOut(
        Constants.noteContract,
        minValue,
        registers = Array(ErgoValue.of(outTree1), ErgoValue.of(holderPk), ErgoValue.of(firstPosition + 1)),
        tokens = Array(new ErgoToken(firstNoteTokenId, 50))
      )

      val note1Change = createOut(
        Constants.noteContract,
        minValue,
        registers = Array(ErgoValue.of(outTree1), ErgoValue.of(holderPk), ErgoValue.of(firstPosition + 1)),
        tokens = Array(new ErgoToken(firstNoteTokenId, 5))
      )

      val note2Output = createOut(
        Constants.noteContract,
        minValue,
        registers = Array(ErgoValue.of(outTree2), ErgoValue.of(holderPk), ErgoValue.of(secondPosition + 1)),
        tokens = Array(new ErgoToken(secondNoteTokenId, 50))
      )

      val note2Change = createOut(
        Constants.noteContract,
        minValue,
        registers = Array(ErgoValue.of(outTree2), ErgoValue.of(holderPk), ErgoValue.of(secondPosition + 1)),
        tokens = Array(new ErgoToken(secondNoteTokenId, 10))
      )

      val inputs = Array[InputBox](firstNoteInput, secondNoteInput, fundingBox)
      val dataInputs = Array[InputBox](reserveDataInput)
      val outputs = Array[OutBoxImpl](note1Output, note2Output, note1Change, note2Change)

      noException shouldBe thrownBy {
        createTx(
          inputs,
          dataInputs,
          outputs,
          fee = Some(feeValue),
          changeAddress,
          Array[String](holderSecret.toString()),
          false
        )
      }
    }
  }

  property("refund - init") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val reserveInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue)
          .tokens(new ErgoToken(reserveNFTBytes, 1))
          .registers(ErgoValue.of(holderPk))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.reserveContract))
          .build()
          .convertToInputWith(fakeTxId2, fakeIndex)
          .withContextVars(
            new ContextVar(0, ErgoValue.of(20: Byte))
          )

      val fundingBox =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue)
          .contract(ctx.compileContract(ConstantsBuilder.empty(), fakeScript))
          .build()
          .convertToInputWith(fakeTxId1, fakeIndex)

      val reserveOutput = createOut(
        Constants.reserveContract,
        minValue,
        registers = Array(ErgoValue.of(holderPk), ErgoValue.of(ctx.getHeight - 2)),
        tokens = Array(new ErgoToken(reserveNFT, 1))
      )

      val inputs = Array[InputBox](fundingBox, reserveInput)
      val dataInputs = Array[InputBox]()
      val outputs = Array[OutBoxImpl](reserveOutput)

      createTx(
        inputs,
        dataInputs,
        outputs,
        fee = None ,
        changeAddress,
        Array[String](holderSecret.toString()),
        false
      )
    }
  }

  property("refund - cancel") {
    // todo:
  }


  property("refund - done") {
    // todo:
  }

  property("top-up") {
    // todo:
  }

}
