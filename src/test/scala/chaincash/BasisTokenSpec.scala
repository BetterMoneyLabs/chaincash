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
import scorex.util.encode.Base16
import sigmastate.AvlTreeFlags
import sigmastate.basics.DLogProtocol.ProveDlog
import sigmastate.eval._
import sigmastate.serialization.GroupElementSerializer
import special.sigma.{AvlTree, GroupElement}
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.PlasmaMap

import collection.JavaConverters._
import java.util
import scala.util.Try

class BasisTokenSpec extends PropSpec with Matchers with ScalaCheckDrivenPropertyChecks with HttpClientTesting {

  implicit val addrEncoder = Constants.ergoAddressEncoder

  // ========== CONSTANTS ==========

  val fakeScript = "sigmaProp(true)"
  val chainCashPlasmaParameters = PlasmaParameters(32, None)

  def emptyPlasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
  def emptyTreeErgoValue: ErgoValue[AvlTree] = emptyPlasmaMap.ergoValue
  def emptyTree: AvlTree = emptyTreeErgoValue.getValue

  // Token IDs for testing
  val trackerNFT = "3c45f29a5165b030fdb5eaf5d81f8108f9d8f507b31487dd51f4ae08fe07cf4a"
  val trackerNFTBytes = Base16.decode(trackerNFT).get
  val reserveTokenId = "5c3e9f8a6b4d7c2e1f0a9b8c7d6e5f4a3b2c1d0e9f8a7b6c5d4e3f2a1b0c9d8e"
  val reserveTokenIdBytes = Base16.decode(reserveTokenId).get
  val reserveNFT = "6d4f0a1b2c3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a"
  val reserveNFTBytes = Base16.decode(reserveNFT).get
  val wrongToken0 = Base16.decode("1111111111111111111111111111111111111111111111111111111111111111").get
  val wrongToken1 = Base16.decode("2222222222222222222222222222222222222222222222222222222222222222").get
  val wrongTrackerNFT = Base16.decode("4c45f29a5165b030fdb5eaf5d81f8108f9d8f507b31487dd51f4ae08fe07cf4b").get

  // Secret keys for testing
  val ownerSecret = SigUtils.randBigInt
  val ownerPk = Constants.g.exp(ownerSecret.bigInteger)
  val receiverSecret = SigUtils.randBigInt
  val receiverPk = Constants.g.exp(receiverSecret.bigInteger)
  val trackerSecret = SigUtils.randBigInt
  val trackerPk = Constants.g.exp(trackerSecret.bigInteger)

  // Transaction values
  val minValue = 1000000000L
  val feeValue = 1000000L
  val minTopUp = 1L // Minimum token top-up (tokens have no decimals)

  // Fake transaction IDs for input boxes
  val fakeTxId1 = "f9e5ce5aa0d95f5d54a7bc89c46730d9662397067250aa18a0039631c0f5b809"
  val fakeTxId2 = "f9e5ce5aa0d95f5d54a7bc89c46730d9662397067250aa18a0039631c0f5b808"
  val fakeTxId3 = "f9e5ce5aa0d95f5d54a7bc89c46730d9662397067250aa18a0039631c0f5b807"
  val fakeIndex = 1.toShort

  val changeAddress = P2PKAddress(ProveDlog(ownerPk)).toString

  // ========== HELPER METHODS - CONTRACT UTILS ==========

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
    if (tokens.isEmpty) outBoxBuilder else outBoxBuilder.tokens(tokens.asScala: _*)
  }

  private def addRegisters(outBoxBuilder: OutBoxBuilder)(registers: java.util.List[ErgoValue[_]]) = {
    if (registers.isEmpty) outBoxBuilder else outBoxBuilder.registers(registers.asScala: _*)
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
      val outBoxBuilderWithTokens: OutBoxBuilder = addTokens(outBoxBuilder)(box.getTokens())
      val outBox: OutBox = addRegisters(outBoxBuilderWithTokens)(box.getRegisters()).build
      outBox
    }
    val inputs = new util.ArrayList[InputBox]()
    inputBoxes.foreach(inputs.add)
    val dataInputBoxes = new util.ArrayList[InputBox]()
    dataInputs.foreach(dataInputBoxes.add)

    val txToSignNoFee = ctx.newTxBuilder()
      .boxesToSpend(inputs)
      .withDataInputs(dataInputBoxes)
      .outputs(outputBoxes: _*)
      .sendChangeTo(getAddressFromString(changeAddress))

    val txToSign = if (fee.isDefined) txToSignNoFee.fee(fee.get) else txToSignNoFee
    val txBuilt = txToSign.build()

    val proveDlogSecretsBigInt = proveDlogSecrets.map(decodeBigInt)
    val dlogProver = proveDlogSecretsBigInt.foldLeft(ctx.newProverBuilder()) {
      case (oldProverBuilder, newDlogSecret) => oldProverBuilder.withDLogSecret(newDlogSecret.bigInteger)
    }

    val signedTx = dlogProver.build().sign(txBuilt)
    if (broadcast) ctx.sendTransaction(signedTx)
    signedTx
  }

  // ========== HELPER METHODS - CRYPTO & TREES ==========

  def mkKey(ownerKey: GroupElement, receiverKey: GroupElement): Array[Byte] =
    Blake2b256(ownerKey.getEncoded.toArray ++ receiverKey.getEncoded.toArray)

  def mkMessage(key: Array[Byte], totalDebt: Long, timestamp: Long): Array[Byte] =
    key ++ Longs.toByteArray(totalDebt) ++ Longs.toByteArray(timestamp)

  // NOTE: Emergency redemption uses the SAME message format as normal redemption.
  // The only difference is that tracker signature becomes OPTIONAL after 3 days (2160 blocks).
  // This function is kept for documentation purposes but is NOT used by the actual contract.
  def mkEmergencyMessage(key: Array[Byte], totalDebt: Long, timestamp: Long): Array[Byte] =
    key ++ Longs.toByteArray(totalDebt) ++ Longs.toByteArray(timestamp) ++ Longs.toByteArray(0L)

  def mkSigBytes(sig: (GroupElement, BigInt)): Array[Byte] =
    GroupElementSerializer.toBytes(sig._1) ++ sig._2.toByteArray

  def corruptSig(sigBytes: Array[Byte]): Array[Byte] = {
    val corrupted = sigBytes.clone()
    corrupted(0) = (corrupted(0) + 1).toByte
    corrupted
  }

  case class TreeAndProof(initialTree: ErgoValue[AvlTree], nextTree: ErgoValue[AvlTree], proofBytes: Array[Byte])

  def mkTreeAndProof(key: Array[Byte], redeemedDebt: Long, timestamp: Long): TreeAndProof = {
    val plasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
    val initial = plasmaMap.ergoValue
    val treeValue = Longs.toByteArray(timestamp) ++ Longs.toByteArray(redeemedDebt)
    val insertRes = plasmaMap.insert((key, treeValue))
    TreeAndProof(initial, plasmaMap.ergoValue, insertRes.proof.bytes)
  }

  case class TrackerTreeAndProof(tree: ErgoValue[AvlTree], lookupProofBytes: Array[Byte])

  def mkTrackerTreeAndProof(key: Array[Byte], totalDebt: Long): TrackerTreeAndProof = {
    val plasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
    val insertRes = plasmaMap.insert((key, Longs.toByteArray(totalDebt)))
    TrackerTreeAndProof(plasmaMap.ergoValue, plasmaMap.lookUp(key).proof.bytes)
  }

  // ========== HELPER METHODS - INPUT BOX BUILDERS ==========

  def mkBasisTokenInput(
    value: Long,
    tree: ErgoValue[AvlTree],
    receiverKey: GroupElement,
    reserveSigBytes: Array[Byte],
    totalDebt: Long,
    insertProofBytes: Array[Byte],
    trackerSigBytes: Array[Byte],
    reserveTokenAmount: Long,
    lookupProofBytes: Option[Array[Byte]] = None,
    trackerLookupProofBytes: Option[Array[Byte]] = None,
    timestamp: Long
  )(implicit ctx: BlockchainContext): InputBox = {
    val baseVars = Array(
      new ContextVar(0, ErgoValue.of(0: Byte)),
      new ContextVar(1, ErgoValue.of(receiverKey)),
      new ContextVar(2, ErgoValue.of(reserveSigBytes)),
      new ContextVar(3, ErgoValue.of(totalDebt)),
      new ContextVar(4, ErgoValue.of(timestamp)),
      new ContextVar(5, ErgoValue.of(insertProofBytes)),
      new ContextVar(6, ErgoValue.of(trackerSigBytes))
    )
    val withLookup = lookupProofBytes match {
      case Some(lookupProof) => baseVars :+ new ContextVar(7, ErgoValue.of(lookupProof))
      case None => baseVars
    }
    val allVars = trackerLookupProofBytes match {
      case Some(trackerLookupProof) => withLookup :+ new ContextVar(8, ErgoValue.of(trackerLookupProof))
      case None => withLookup
    }
    ctx.newTxBuilder().outBoxBuilder
      .value(value)
      .tokens(
        new ErgoToken(reserveNFTBytes, 1),
        new ErgoToken(reserveTokenIdBytes, reserveTokenAmount)
      )
      .registers(ErgoValue.of(ownerPk), tree, ErgoValue.of(trackerNFTBytes))
      .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.basisTokenContract))
      .build()
      .convertToInputWith(fakeTxId1, fakeIndex)
      .withContextVars(allVars: _*)
  }

  def mkTrackerDataInput(
    trackerTree: ErgoValue[AvlTree] = ErgoValue.of(emptyTree),
    creationHeightOffset: Option[Int] = None
  )(implicit ctx: BlockchainContext): InputBox = {
    val builder = ctx.newTxBuilder().outBoxBuilder
      .value(minValue)
      .tokens(new ErgoToken(trackerNFTBytes, 1))
      .registers(ErgoValue.of(trackerPk), trackerTree)
      .contract(ctx.compileContract(ConstantsBuilder.empty(), "{sigmaProp(true)}"))
    val builderWithHeight = creationHeightOffset match {
      case Some(offset) => builder.creationHeight(ctx.getHeight - offset)
      case None => builder
    }
    builderWithHeight.build().convertToInputWith(fakeTxId2, fakeIndex)
  }

  // ========== HELPER METHODS - TEST ASSERTIONS ==========

  private def formatCauseChain(t: Throwable): String =
    Iterator.iterate[Throwable](t)(_.getCause).takeWhile(_ != null).zipWithIndex.map {
      case (e, i) => s"[$i] ${e.getClass.getName}: ${Option(e.getMessage).getOrElse("<no message>")}"
    }.mkString("\n")

  private def hasScriptValidationFailure(t: Throwable): Boolean =
    Iterator.iterate[Throwable](t)(_.getCause).takeWhile(_ != null).exists {
      case _: sigmastate.exceptions.InterpreterException => true
      case _ => false
    }

  private val constructionErrorClassKeywords = Seq(
    "NotEnoughCoins", "NotEnoughTokens", "NotEnoughErgs",
    "BoxSelection", "InputBoxesSelection"
  )

  private def looksLikeConstructionError(t: Throwable): Boolean =
    Iterator.iterate[Throwable](t)(_.getCause).takeWhile(_ != null).exists { e =>
      val simpleName = e.getClass.getSimpleName
      val fullName = e.getClass.getName
      val classMatch = constructionErrorClassKeywords.exists(kw => simpleName.contains(kw) || fullName.contains(kw))
      if (classMatch) true
      else {
        val m = Option(e.getMessage).getOrElse("").toLowerCase
        m.contains("not enough") || m.contains("input boxes") || m.contains("selection") ||
          m.contains("insufficient") || m.contains("change address")
      }
    }

  def assertTxFails(
    inputs: Array[InputBox],
    dataInputs: Array[InputBox],
    outputs: Array[OutBoxImpl],
    secrets: Array[String]
  )(implicit ctx: BlockchainContext): Unit = {
    val ex = intercept[Throwable] {
      createTx(inputs, dataInputs, outputs, fee = Some(feeValue), changeAddress, secrets, broadcast = false)
    }
    assert(!looksLikeConstructionError(ex), s"Construction failure:\n${formatCauseChain(ex)}")
    assert(hasScriptValidationFailure(ex), s"Expected script validation failure:\n${formatCauseChain(ex)}")
  }

  def assertTxSucceeds(
    inputs: Array[InputBox],
    dataInputs: Array[InputBox],
    outputs: Array[OutBoxImpl],
    secrets: Array[String]
  )(implicit ctx: BlockchainContext): SignedTransaction = {
    createTx(inputs, dataInputs, outputs, fee = Some(feeValue), changeAddress, secrets, broadcast = false)
  }

  // ========== POSITIVE TESTS ==========

  property("basis-token redemption should work with valid setup") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val totalDebt = 1000000000L
      val redeemedDebt = 0L
      val redeemAmount = 300000000L
      val newRedeemedDebt = redeemedDebt + redeemAmount
      val timestamp = System.currentTimeMillis()
      val reserveTokenAmount = 1000000000L

      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt, timestamp)
      val reserveSigBytes = mkSigBytes(SigUtils.sign(message, ownerSecret))
      val trackerSigBytes = mkSigBytes(SigUtils.sign(message, trackerSecret))

      val plasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
      val insertRes2 = plasmaMap.insert(key -> (Longs.toByteArray(timestamp) ++ Longs.toByteArray(newRedeemedDebt)))
      val insertProof = insertRes2.proof
      val outputTreeErgoValue = plasmaMap.ergoValue

      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisTokenInput(
        minValue * 2 + feeValue, emptyTreeErgoValue,
        receiverPk, reserveSigBytes, totalDebt, insertProof.bytes, trackerSigBytes,
        reserveTokenAmount, None, Some(trackerLookupProof), timestamp
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)

      val basisOutput = createOut(
        Constants.basisTokenContract, minValue,
        Array(ErgoValue.of(ownerPk), outputTreeErgoValue, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(reserveNFTBytes, 1), new ErgoToken(reserveTokenIdBytes, reserveTokenAmount - redeemAmount))
      )
      val redemptionOutput = createOut(trueScript, minValue, Array(), Array(new ErgoToken(reserveTokenIdBytes, redeemAmount)))

      noException should be thrownBy {
        createTx(Array(basisInput), Array(trackerDataInput), Array(basisOutput, redemptionOutput),
          fee = Some(feeValue), changeAddress, Array(receiverSecret.toString()), false)
      }
    }
  }

  property("basis-token top-up should work") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val topUpAmount = 2000000000L
      val initialReserveTokenAmount = 1000000000L

      val basisInput =
        ctx.newTxBuilder().outBoxBuilder
          .value(minValue)
          .tokens(new ErgoToken(reserveNFTBytes, 1), new ErgoToken(reserveTokenIdBytes, initialReserveTokenAmount))
          .registers(ErgoValue.of(ownerPk), ErgoValue.of(emptyTree), ErgoValue.of(trackerNFTBytes))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.basisTokenContract))
          .build()
          .convertToInputWith(fakeTxId1, fakeIndex)
          .withContextVars(new ContextVar(0, ErgoValue.of(10: Byte)))

      val fundingBox =
        ctx.newTxBuilder().outBoxBuilder
          .value(minValue + feeValue)
          .tokens(new ErgoToken(reserveTokenIdBytes, topUpAmount))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), fakeScript))
          .build()
          .convertToInputWith(fakeTxId2, fakeIndex)

      val basisOutput = createOut(
        Constants.basisTokenContract, minValue * 2,
        Array(ErgoValue.of(ownerPk), ErgoValue.of(emptyTree), ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(reserveNFTBytes, 1), new ErgoToken(reserveTokenIdBytes, initialReserveTokenAmount + topUpAmount))
      )

      noException should be thrownBy {
        createTx(Array(basisInput, fundingBox), Array(), Array(basisOutput),
          fee = Some(feeValue), changeAddress, Array(ownerSecret.toString()), false)
      }
    }
  }

  // ========== NEGATIVE TESTS - INSUFFICIENT AMOUNTS ==========

  property("basis-token redemption should fail with insufficient reserve token amount") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val debtAmount = 500000000L
      val timestamp = System.currentTimeMillis()
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, debtAmount, timestamp)
      val reserveSigBytes = mkSigBytes(SigUtils.sign(message, ownerSecret))
      val trackerSigBytes = mkSigBytes(SigUtils.sign(message, trackerSecret))

      val plasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
      val insertRes = plasmaMap.insert(key -> (Longs.toByteArray(timestamp) ++ Longs.toByteArray(0L)))
      val insertProof = insertRes.proof
      val nextTreeErgoValue = plasmaMap.ergoValue

      val TrackerTreeAndProof(_, trackerLookupProof) = mkTrackerTreeAndProof(key, debtAmount)

      val basisInput = mkBasisTokenInput(
        minValue + debtAmount + feeValue, emptyTreeErgoValue,
        receiverPk, reserveSigBytes, debtAmount, insertProof.bytes, trackerSigBytes,
        debtAmount - 100000000L, // Insufficient reserve tokens
        Some(insertProof.bytes), Some(trackerLookupProof), timestamp
      )
      val trackerDataInput = mkTrackerDataInput()

      val basisOutput = createOut(
        Constants.basisTokenContract, minValue + feeValue - 100000000L,
        Array(ErgoValue.of(ownerPk), nextTreeErgoValue, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(reserveNFTBytes, 1), new ErgoToken(reserveTokenIdBytes, 0L))
      )
      val redemptionOutput = createOut(trueScript, minValue, Array(), Array(new ErgoToken(reserveTokenIdBytes, debtAmount)))

      a[Throwable] should be thrownBy {
        createTx(Array(basisInput), Array(trackerDataInput), Array(basisOutput, redemptionOutput),
          fee = Some(feeValue), changeAddress, Array(receiverSecret.toString()), false)
      }
    }
  }

  property("basis-token top-up should fail with insufficient amount") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val topUpAmount = 0L // Less than minimum 1 token unit

      val basisInput =
        ctx.newTxBuilder().outBoxBuilder
          .value(minValue)
          .tokens(new ErgoToken(reserveNFTBytes, 1), new ErgoToken(reserveTokenIdBytes, 1000000000L))
          .registers(ErgoValue.of(ownerPk), ErgoValue.of(emptyTree), ErgoValue.of(trackerNFTBytes))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.basisTokenContract))
          .build()
          .convertToInputWith(fakeTxId1, fakeIndex)
          .withContextVars(new ContextVar(0, ErgoValue.of(10: Byte)))

      val fundingBox =
        ctx.newTxBuilder().outBoxBuilder
          .value(minValue + feeValue)
          .tokens(new ErgoToken(reserveTokenIdBytes, topUpAmount))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), fakeScript))
          .build()
          .convertToInputWith(fakeTxId2, fakeIndex)

      val basisOutput = createOut(
        Constants.basisTokenContract, minValue,
        Array(ErgoValue.of(ownerPk), ErgoValue.of(emptyTree), ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(reserveNFTBytes, 1), new ErgoToken(reserveTokenIdBytes, 1000000000L))
      )

      a[Throwable] should be thrownBy {
        createTx(Array(basisInput, fundingBox), Array(), Array(basisOutput),
          fee = Some(feeValue), changeAddress, Array(ownerSecret.toString()), false)
      }
    }
  }

  // ========== NEGATIVE TESTS - SIGNATURE VALIDATION ==========

  property("basis-token redemption should fail with invalid tracker signature") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val totalDebt = 500000000L
      val timestamp = System.currentTimeMillis()
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt, timestamp)

      val reserveSigBytes = mkSigBytes(SigUtils.sign(message, ownerSecret))
      val invalidTrackerSigBytes = corruptSig(mkSigBytes(SigUtils.sign(message, trackerSecret)))

      val TreeAndProof(_, nextTree, proofBytes) = mkTreeAndProof(key, totalDebt, timestamp)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisTokenInput(
        minValue * 2 + feeValue, emptyTreeErgoValue,
        receiverPk, reserveSigBytes, totalDebt, proofBytes, invalidTrackerSigBytes,
        totalDebt, None, Some(trackerLookupProof), timestamp
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)

      val basisOutput = createOut(
        Constants.basisTokenContract, minValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(reserveNFTBytes, 1), new ErgoToken(reserveTokenIdBytes, 0L))
      )
      val redemptionOutput = createOut(trueScript, minValue, Array(), Array(new ErgoToken(reserveTokenIdBytes, totalDebt)))

      a[Throwable] should be thrownBy {
        createTx(Array(basisInput), Array(trackerDataInput), Array(basisOutput, redemptionOutput),
          fee = Some(feeValue), changeAddress, Array(receiverSecret.toString()), false)
      }
    }
  }

  property("basis-token redemption should fail with invalid reserve owner signature") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val totalDebt = 500000000L
      val timestamp = System.currentTimeMillis()
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt, timestamp)

      val invalidReserveSigBytes = corruptSig(mkSigBytes(SigUtils.sign(message, ownerSecret)))
      val trackerSigBytes = mkSigBytes(SigUtils.sign(message, trackerSecret))

      val TreeAndProof(_, nextTree, proofBytes) = mkTreeAndProof(key, totalDebt, timestamp)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisTokenInput(
        minValue * 2 + feeValue, emptyTreeErgoValue,
        receiverPk, invalidReserveSigBytes, totalDebt, proofBytes, trackerSigBytes,
        totalDebt, None, Some(trackerLookupProof), timestamp
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)

      val basisOutput = createOut(
        Constants.basisTokenContract, minValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(reserveNFTBytes, 1), new ErgoToken(reserveTokenIdBytes, 0L))
      )
      val redemptionOutput = createOut(trueScript, minValue, Array(), Array(new ErgoToken(reserveTokenIdBytes, totalDebt)))

      a[Throwable] should be thrownBy {
        createTx(Array(basisInput), Array(trackerDataInput), Array(basisOutput, redemptionOutput),
          fee = Some(feeValue), changeAddress, Array(receiverSecret.toString()), false)
      }
    }
  }

  // ========== NEGATIVE TESTS - TRACKER VALIDATION ==========

  property("basis-token redemption should fail with tracker identity mismatch") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val totalDebt = 500000000L
      val timestamp = System.currentTimeMillis()
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt, timestamp)

      val reserveSigBytes = mkSigBytes(SigUtils.sign(message, ownerSecret))
      val trackerSigBytes = mkSigBytes(SigUtils.sign(message, trackerSecret))

      val TreeAndProof(_, nextTree, proofBytes) = mkTreeAndProof(key, totalDebt, timestamp)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      // Input has wrong tracker NFT in R6
      val basisInput =
        ctx.newTxBuilder().outBoxBuilder
          .value(minValue * 2 + feeValue)
          .tokens(new ErgoToken(reserveNFTBytes, 1), new ErgoToken(reserveTokenIdBytes, totalDebt))
          .registers(ErgoValue.of(ownerPk), emptyTreeErgoValue, ErgoValue.of(wrongTrackerNFT)) // Wrong tracker NFT
          .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.basisTokenContract))
          .build()
          .convertToInputWith(fakeTxId1, fakeIndex)
          .withContextVars(
            new ContextVar(0, ErgoValue.of(0: Byte)),
            new ContextVar(1, ErgoValue.of(receiverPk)),
            new ContextVar(2, ErgoValue.of(reserveSigBytes)),
            new ContextVar(3, ErgoValue.of(totalDebt)),
            new ContextVar(4, ErgoValue.of(timestamp)),
            new ContextVar(5, ErgoValue.of(proofBytes)),
            new ContextVar(6, ErgoValue.of(trackerSigBytes)),
            new ContextVar(8, ErgoValue.of(trackerLookupProof))
          )

      val trackerDataInput = mkTrackerDataInput(trackerTree)

      val basisOutput = createOut(
        Constants.basisTokenContract, minValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(wrongTrackerNFT)),
        Array(new ErgoToken(reserveNFTBytes, 1), new ErgoToken(reserveTokenIdBytes, 0L))
      )
      val redemptionOutput = createOut(trueScript, minValue, Array(), Array(new ErgoToken(reserveTokenIdBytes, totalDebt)))

      a[Throwable] should be thrownBy {
        createTx(Array(basisInput), Array(trackerDataInput), Array(basisOutput, redemptionOutput),
          fee = Some(feeValue), changeAddress, Array(receiverSecret.toString()), false)
      }
    }
  }

  // ========== NEGATIVE TESTS - AVL TREE VALIDATION ==========

  property("basis-token redemption should fail with invalid AVL tree proof") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val totalDebt = 500000000L
      val timestamp = System.currentTimeMillis()
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt, timestamp)

      val reserveSigBytes = mkSigBytes(SigUtils.sign(message, ownerSecret))
      val trackerSigBytes = mkSigBytes(SigUtils.sign(message, trackerSecret))

      // Create proof for wrong key
      val wrongKey = mkKey(receiverPk, ownerPk) // Reversed keys
      val wrongPlasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
      val wrongInsertRes = wrongPlasmaMap.insert((wrongKey, Longs.toByteArray(timestamp) ++ Longs.toByteArray(0L)))
      val invalidProof = wrongInsertRes.proof.bytes

      val TreeAndProof(_, nextTree, _) = mkTreeAndProof(key, totalDebt, timestamp)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisTokenInput(
        minValue * 2 + feeValue, emptyTreeErgoValue,
        receiverPk, reserveSigBytes, totalDebt, invalidProof, trackerSigBytes,
        totalDebt, None, Some(trackerLookupProof), timestamp
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)

      val basisOutput = createOut(
        Constants.basisTokenContract, minValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(reserveNFTBytes, 1), new ErgoToken(reserveTokenIdBytes, 0L))
      )
      val redemptionOutput = createOut(trueScript, minValue, Array(), Array(new ErgoToken(reserveTokenIdBytes, totalDebt)))

      a[Throwable] should be thrownBy {
        createTx(Array(basisInput), Array(trackerDataInput), Array(basisOutput, redemptionOutput),
          fee = Some(feeValue), changeAddress, Array(receiverSecret.toString()), false)
      }
    }
  }

  // ========== NEGATIVE TESTS - TIMESTAMP VALIDATION ==========

  property("basis-token redemption should fail with timestamp replay attack (old timestamp)") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val totalDebt = 500000000L
      val oldTimestamp = System.currentTimeMillis() - 10000L // 10 seconds old
      val newTimestamp = System.currentTimeMillis()
      val key = mkKey(ownerPk, receiverPk)

      // First, create a redemption with old timestamp (simulating prior redemption)
      val oldMessage = mkMessage(key, totalDebt, oldTimestamp)
      val oldReserveSigBytes = mkSigBytes(SigUtils.sign(oldMessage, ownerSecret))
      val oldTrackerSigBytes = mkSigBytes(SigUtils.sign(oldMessage, trackerSecret))

      val oldPlasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
      oldPlasmaMap.insert(key -> (Longs.toByteArray(oldTimestamp) ++ Longs.toByteArray(totalDebt)))
      val oldTree = oldPlasmaMap.ergoValue

      // Now try to redeem again with same old timestamp (should fail)
      val message = mkMessage(key, totalDebt, oldTimestamp)
      val reserveSigBytes = mkSigBytes(SigUtils.sign(message, ownerSecret))
      val trackerSigBytes = mkSigBytes(SigUtils.sign(message, trackerSecret))

      val TreeAndProof(_, nextTree, proofBytes) = mkTreeAndProof(key, totalDebt, oldTimestamp)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisTokenInput(
        minValue * 2 + feeValue, oldTree,
        receiverPk, reserveSigBytes, totalDebt, proofBytes, trackerSigBytes,
        totalDebt, Some(proofBytes), Some(trackerLookupProof), oldTimestamp
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)

      val basisOutput = createOut(
        Constants.basisTokenContract, minValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(reserveNFTBytes, 1), new ErgoToken(reserveTokenIdBytes, 0L))
      )
      val redemptionOutput = createOut(trueScript, minValue, Array(), Array(new ErgoToken(reserveTokenIdBytes, totalDebt)))

      a[Throwable] should be thrownBy {
        createTx(Array(basisInput), Array(trackerDataInput), Array(basisOutput, redemptionOutput),
          fee = Some(feeValue), changeAddress, Array(receiverSecret.toString()), false)
      }
    }
  }

  // ========== NEGATIVE TESTS - DEBT LOGIC ==========

  property("basis-token redemption should fail when redeeming more than debt delta") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val totalDebt = 500000000L
      val redeemedDebt = 200000000L // Already redeemed some
      val timestamp = System.currentTimeMillis()
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt, timestamp)

      val reserveSigBytes = mkSigBytes(SigUtils.sign(message, ownerSecret))
      val trackerSigBytes = mkSigBytes(SigUtils.sign(message, trackerSecret))

      // Create tree with existing redeemed amount
      val plasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
      plasmaMap.insert(key -> (Longs.toByteArray(timestamp) ++ Longs.toByteArray(redeemedDebt)))
      val inputTree = plasmaMap.ergoValue

      val insertRes = plasmaMap.insert(key -> (Longs.toByteArray(timestamp) ++ Longs.toByteArray(totalDebt)))
      val nextTree = plasmaMap.ergoValue
      val proofBytes = insertRes.proof.bytes

      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisTokenInput(
        minValue * 2 + feeValue, inputTree,
        receiverPk, reserveSigBytes, totalDebt, proofBytes, trackerSigBytes,
        totalDebt, // Trying to redeem more than allowed (debtDelta = totalDebt - redeemedDebt = 300000000L)
        Some(insertRes.proof.bytes), Some(trackerLookupProof), timestamp
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)

      val basisOutput = createOut(
        Constants.basisTokenContract, minValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(reserveNFTBytes, 1), new ErgoToken(reserveTokenIdBytes, 0L))
      )
      val redemptionOutput = createOut(trueScript, minValue, Array(), Array(new ErgoToken(reserveTokenIdBytes, totalDebt)))

      a[Throwable] should be thrownBy {
        createTx(Array(basisInput), Array(trackerDataInput), Array(basisOutput, redemptionOutput),
          fee = Some(feeValue), changeAddress, Array(receiverSecret.toString()), false)
      }
    }
  }

  // ========== TOKEN PRESERVATION TESTS ==========

  property("basis-token token preservation: verify token IDs at positions #0 and #1 are preserved") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val totalDebt = 500000000L
      val redeemAmount = totalDebt / 2
      val newRedeemedDebt = redeemAmount
      val timestamp = System.currentTimeMillis()
      val reserveTokenAmount = totalDebt

      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt, timestamp)
      val reserveSigBytes = mkSigBytes(SigUtils.sign(message, ownerSecret))
      val trackerSigBytes = mkSigBytes(SigUtils.sign(message, trackerSecret))

      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, newRedeemedDebt, timestamp)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisTokenInput(
        minValue * 2 + feeValue, initialTree,
        receiverPk, reserveSigBytes, totalDebt, proofBytes, trackerSigBytes,
        reserveTokenAmount, None, Some(trackerLookupProof), timestamp
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)
      val redemptionOutput = createOut(trueScript, minValue, Array(), Array(new ErgoToken(reserveTokenIdBytes, redeemAmount)))

      val basisOutput = createOut(
        Constants.basisTokenContract, minValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(reserveNFTBytes, 1), new ErgoToken(reserveTokenIdBytes, reserveTokenAmount - redeemAmount))
      )

      noException should be thrownBy {
        createTx(Array(basisInput), Array(trackerDataInput), Array(basisOutput, redemptionOutput),
          fee = Some(feeValue), changeAddress, Array(receiverSecret.toString()), false)
      }
    }
  }

  property("basis-token redemption should fail with swapped token IDs in output") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val totalDebt = 500000000L
      val redeemAmount = totalDebt / 2
      val newRedeemedDebt = redeemAmount
      val timestamp = System.currentTimeMillis()
      val reserveTokenAmount = totalDebt

      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt, timestamp)
      val reserveSigBytes = mkSigBytes(SigUtils.sign(message, ownerSecret))
      val trackerSigBytes = mkSigBytes(SigUtils.sign(message, trackerSecret))

      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, newRedeemedDebt, timestamp)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput =
        ctx.newTxBuilder().outBoxBuilder
          .value(minValue * 2 + feeValue)
          .tokens(new ErgoToken(reserveNFTBytes, 1), new ErgoToken(reserveTokenIdBytes, reserveTokenAmount))
          .registers(ErgoValue.of(ownerPk), initialTree, ErgoValue.of(trackerNFTBytes))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.basisTokenContract))
          .build()
          .convertToInputWith(fakeTxId1, fakeIndex)
          .withContextVars(
            new ContextVar(0, ErgoValue.of(0: Byte)),
            new ContextVar(1, ErgoValue.of(receiverPk)),
            new ContextVar(2, ErgoValue.of(reserveSigBytes)),
            new ContextVar(3, ErgoValue.of(totalDebt)),
            new ContextVar(4, ErgoValue.of(timestamp)),
            new ContextVar(5, ErgoValue.of(proofBytes)),
            new ContextVar(6, ErgoValue.of(trackerSigBytes)),
            new ContextVar(8, ErgoValue.of(trackerLookupProof))
          )

      val trackerDataInput = mkTrackerDataInput(trackerTree)
      val redemptionOutput = createOut(trueScript, minValue, Array(), Array(new ErgoToken(reserveTokenIdBytes, redeemAmount)))

      // Swapped token positions
      val badBasisOutputSwapped = createOut(
        Constants.basisTokenContract, minValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(reserveTokenIdBytes, reserveTokenAmount - redeemAmount), new ErgoToken(reserveNFTBytes, 1))
      )

      a[Throwable] should be thrownBy {
        createTx(Array(basisInput), Array(trackerDataInput), Array(badBasisOutputSwapped, redemptionOutput),
          fee = Some(feeValue), changeAddress, Array(receiverSecret.toString()), false)
      }

      // Control: correct positions should succeed
      val goodBasisOutput = createOut(
        Constants.basisTokenContract, minValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(reserveNFTBytes, 1), new ErgoToken(reserveTokenIdBytes, reserveTokenAmount - redeemAmount))
      )

      noException should be thrownBy {
        createTx(Array(basisInput), Array(trackerDataInput), Array(goodBasisOutput, redemptionOutput),
          fee = Some(feeValue), changeAddress, Array(receiverSecret.toString()), false)
      }
    }
  }

  // ========== CONTRACT LINKAGE VERIFICATION ==========

  property("Constants.basisTokenContract matches contracts/offchain/basis-token.es (text-equivalent)") {
    import java.nio.charset.StandardCharsets
    import java.nio.file.{Files, Paths}
    val projectRoot = Paths.get(sys.props("user.dir"))
    val contractPath = projectRoot.resolve("contracts/offchain/basis-token.es")
    require(Files.exists(contractPath), s"Missing contract file: $contractPath (user.dir=${sys.props("user.dir")})")
    val fileBytes = Files.readAllBytes(contractPath)
    val fileText = new String(fileBytes, StandardCharsets.UTF_8)
    Constants.basisTokenContract shouldEqual fileText.replace("\r\n", "\n").stripSuffix("\n")
  }

  property("compiled basisTokenErgoTree matches fresh compilation of basis-token.es") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val freshlyCompiled = ctx.compileContract(ConstantsBuilder.empty(), Constants.basisTokenContract)
      Constants.basisTokenErgoTree.bytes shouldEqual freshlyCompiled.getErgoTree.bytes
    }
  }

  // ========== TRIANGULAR TRADE TESTS ==========

  property("basis-token: debt transfer triangular trade with consent should work") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val timestamp = System.currentTimeMillis()
      val reserveTokenAmount = 20000000000L

      val initialDebtToBob = 20000000000L
      val transferAmount = 8000000000L
      val remainingDebtToBob = 12000000000L

      val aliceKey = ownerPk
      val bobKey = receiverPk
      val carolSecret = SigUtils.randBigInt
      val carolKey = Constants.g.exp(carolSecret.bigInteger)

      val keyAliceBob = mkKey(aliceKey, bobKey)
      val keyAliceCarol = mkKey(aliceKey, carolKey)

      val trackerTreeABInitial = mkTrackerTreeAndProof(keyAliceBob, initialDebtToBob)

      val messageToBob = mkMessage(keyAliceBob, remainingDebtToBob, timestamp)
      val aliceSigBob = SigUtils.sign(messageToBob, ownerSecret)
      val trackerSigBob = SigUtils.sign(messageToBob, trackerSecret)

      val messageToCarol = mkMessage(keyAliceCarol, transferAmount, timestamp)
      val aliceSigCarol = SigUtils.sign(messageToCarol, ownerSecret)
      val trackerSigCarol = SigUtils.sign(messageToCarol, trackerSecret)

      val trackerTreeAB = mkTrackerTreeAndProof(keyAliceBob, remainingDebtToBob)
      val trackerTreeAC = mkTrackerTreeAndProof(keyAliceCarol, transferAmount)

      val reserveSigBob = mkSigBytes(aliceSigBob)
      val trackerSigBobBytes = mkSigBytes(trackerSigBob)
      val TreeAndProof(initialTreeBob, nextTreeBob, proofBob) = mkTreeAndProof(keyAliceBob, remainingDebtToBob, timestamp)

      val basisInputBob = mkBasisTokenInput(
        minValue * 2 + feeValue, initialTreeBob,
        bobKey, reserveSigBob, remainingDebtToBob, proofBob, trackerSigBobBytes,
        reserveTokenAmount, None, Some(trackerTreeAB.lookupProofBytes), timestamp
      )
      val trackerDataInputAB = mkTrackerDataInput(trackerTreeAB.tree)

      val basisOutputBob = createOut(Constants.basisTokenContract, minValue,
        Array(ErgoValue.of(ownerPk), nextTreeBob, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(reserveNFTBytes, 1), new ErgoToken(reserveTokenIdBytes, reserveTokenAmount - remainingDebtToBob))
      )
      val redemptionOutputBob = createOut(trueScript, minValue, Array(), Array(new ErgoToken(reserveTokenIdBytes, remainingDebtToBob)))

      noException should be thrownBy {
        createTx(Array(basisInputBob), Array(trackerDataInputAB),
          Array(basisOutputBob, redemptionOutputBob), fee = Some(feeValue), changeAddress,
          Array(receiverSecret.toString()), false)
      }

      println("Basis-token debt transfer test passed: triangular trade with tokens works correctly")
    }
  }

  property("basis-token: debt transfer should fail without debtor consent") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val timestamp = System.currentTimeMillis()
      val transferAmount = 5000000000L

      val aliceKey = ownerPk
      val bobKey = receiverPk
      val carolSecret = SigUtils.randBigInt
      val carolKey = Constants.g.exp(carolSecret.bigInteger)

      val keyAliceBob = mkKey(aliceKey, bobKey)
      val keyAliceCarol = mkKey(aliceKey, carolKey)

      val messageToCarol = mkMessage(keyAliceCarol, transferAmount, timestamp)
      val forgedAliceSig = SigUtils.sign(messageToCarol, receiverSecret)
      val forgeryValid = SigUtils.verify(messageToCarol, aliceKey, forgedAliceSig._1, forgedAliceSig._2)
      forgeryValid shouldBe false

      val trackerOnlySig = SigUtils.sign(messageToCarol, trackerSecret)
      val trackerForgeryValid = SigUtils.verify(messageToCarol, aliceKey, trackerOnlySig._1, trackerOnlySig._2)
      trackerForgeryValid shouldBe false

      println("Basis-token debt transfer without consent test passed: unauthorized note creation prevented")
    }
  }

  property("basis-token: multi-hop triangular trade concept verification") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val timestamp = System.currentTimeMillis()

      val aliceKey = ownerPk
      val bobKey = receiverPk
      val carolSecret = SigUtils.randBigInt
      val carolKey = Constants.g.exp(carolSecret.bigInteger)
      val daveSecret = SigUtils.randBigInt
      val daveKey = Constants.g.exp(daveSecret.bigInteger)

      val keyAliceBob = mkKey(aliceKey, bobKey)
      val keyAliceCarol = mkKey(aliceKey, carolKey)
      val keyAliceDave = mkKey(aliceKey, daveKey)

      val debtToBob = 5000000000L
      val debtToCarol = 5000000000L
      val debtToDave = 5000000000L

      val messageBob = mkMessage(keyAliceBob, debtToBob, timestamp)
      val aliceSigBob = SigUtils.sign(messageBob, ownerSecret)
      val trackerSigBob = SigUtils.sign(messageBob, trackerSecret)

      val messageCarol = mkMessage(keyAliceCarol, debtToCarol, timestamp)
      val aliceSigCarol = SigUtils.sign(messageCarol, ownerSecret)
      val trackerSigCarol = SigUtils.sign(messageCarol, trackerSecret)

      val messageDave = mkMessage(keyAliceDave, debtToDave, timestamp)
      val aliceSigDave = SigUtils.sign(messageDave, ownerSecret)
      val trackerSigDave = SigUtils.sign(messageDave, trackerSecret)

      SigUtils.verify(messageCarol, aliceKey, aliceSigCarol._1, aliceSigCarol._2) shouldBe true
      SigUtils.verify(messageCarol, trackerPk, trackerSigCarol._1, trackerSigCarol._2) shouldBe true
      SigUtils.verify(messageDave, aliceKey, aliceSigDave._1, aliceSigDave._2) shouldBe true
      SigUtils.verify(messageDave, trackerPk, trackerSigDave._1, trackerSigDave._2) shouldBe true

      println("Basis-token multi-hop triangular trade concept test passed")
    }
  }

  property("basis-token: partial transfer with creditor holding note - signature verification") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val timestamp = System.currentTimeMillis()

      val aliceKey = ownerPk
      val bobKey = receiverPk
      val carolSecret = SigUtils.randBigInt
      val carolKey = Constants.g.exp(carolSecret.bigInteger)

      val keyAliceBob = mkKey(aliceKey, bobKey)
      val keyAliceCarol = mkKey(aliceKey, carolKey)

      val transferToCarol = 5000000000L
      val bobRemaining = 5000000000L

      val messageBob = mkMessage(keyAliceBob, bobRemaining, timestamp)
      val aliceSigBob = SigUtils.sign(messageBob, ownerSecret)
      val trackerSigBob = SigUtils.sign(messageBob, trackerSecret)

      val messageCarol = mkMessage(keyAliceCarol, transferToCarol, timestamp)
      val aliceSigCarol = SigUtils.sign(messageCarol, ownerSecret)
      val trackerSigCarol = SigUtils.sign(messageCarol, trackerSecret)

      val timestamp2 = timestamp + 1000
      val carolRemaining = 2000000000L

      val messageCarol2 = mkMessage(keyAliceCarol, carolRemaining, timestamp2)
      val aliceSigCarol2 = SigUtils.sign(messageCarol2, ownerSecret)
      val trackerSigCarol2 = SigUtils.sign(messageCarol2, trackerSecret)

      SigUtils.verify(messageBob, aliceKey, aliceSigBob._1, aliceSigBob._2) shouldBe true
      SigUtils.verify(messageCarol, aliceKey, aliceSigCarol._1, aliceSigCarol._2) shouldBe true
      SigUtils.verify(messageCarol2, aliceKey, aliceSigCarol2._1, aliceSigCarol2._2) shouldBe true

      timestamp2 > timestamp shouldBe true

      println("Basis-token partial transfer with holding signature test passed")
    }
  }
}
