package chaincash

import chaincash.contracts.Constants
import chaincash.offchain.SigUtils
import chaincash.offchain.SigUtils._
import com.google.common.primitives.Longs
import org.ergoplatform.P2PKAddress
import org.ergoplatform.appkit.impl.{ErgoTreeContract, OutBoxImpl}
import org.ergoplatform.appkit.{AppkitHelpers, BlockchainContext, ConstantsBuilder, ContextVar, ErgoValue, InputBox, NetworkType, OutBox, OutBoxBuilder, SignedTransaction}
import org.ergoplatform.sdk.ErgoToken
import org.scalatest.{Matchers, PropSpec}
import scorex.crypto.hash.Blake2b256
import scorex.util.encode.Base16
import sigma.ast.ErgoTree
import sigma.data.{AvlTreeFlags, ProveDlog}
import sigma.serialization.GroupElementSerializer
import sigma.{AvlTree, GroupElement}
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.PlasmaMap

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.security.MessageDigest
import java.util
import scala.collection.JavaConverters._

class BasisV2Spec extends PropSpec with Matchers with HttpClientTesting {

  implicit val addressEncoder = Constants.ergoAddressEncoder

  private val ownerSecret = SigUtils.randBigInt
  private val ownerPk = Constants.g.exp(ownerSecret.bigInteger)
  private val receiverSecret = SigUtils.randBigInt
  private val receiverPk = Constants.g.exp(receiverSecret.bigInteger)
  private val otherSecret = SigUtils.randBigInt
  private val otherPk = Constants.g.exp(otherSecret.bigInteger)
  private val trackerSecret = SigUtils.randBigInt
  private val trackerPk = Constants.g.exp(trackerSecret.bigInteger)

  private val reserveNftA = hex("4b2d8b7beb3eaac8234d9e61792d270898a43934d6a27275e4f3a044609c9f2a")
  private val reserveNftB = hex("5b2d8b7beb3eaac8234d9e61792d270898a43934d6a27275e4f3a044609c9f2b")
  private val tokenReserveNft = hex("6b2d8b7beb3eaac8234d9e61792d270898a43934d6a27275e4f3a044609c9f2c")
  private val reserveTokenId = hex("7b2d8b7beb3eaac8234d9e61792d270898a43934d6a27275e4f3a044609c9f2d")
  private val trackerNft = hex("3c45f29a5165b030fdb5eaf5d81f8108f9d8f507b31487dd51f4ae08fe07cf4a")
  private val zeroId = Array.fill[Byte](32)(0)

  private val minValue = 1000000000L
  private val feeValue = 1000000L
  private val totalDebt = 1000000000L
  private val timestamp = 1000000000000L

  private val fakeTxIds = Vector(
    "f9e5ce5aa0d95f5d54a7bc89c46730d9662397067250aa18a0039631c0f5b801",
    "f9e5ce5aa0d95f5d54a7bc89c46730d9662397067250aa18a0039631c0f5b802",
    "f9e5ce5aa0d95f5d54a7bc89c46730d9662397067250aa18a0039631c0f5b803",
    "f9e5ce5aa0d95f5d54a7bc89c46730d9662397067250aa18a0039631c0f5b804",
    "f9e5ce5aa0d95f5d54a7bc89c46730d9662397067250aa18a0039631c0f5b805",
    "f9e5ce5aa0d95f5d54a7bc89c46730d9662397067250aa18a0039631c0f5b806"
  )
  private val fakeIndex = 1.toShort

  private val redeemedFlags = AvlTreeFlags(insertAllowed = true, updateAllowed = true, removeAllowed = false)
  private val trackerFlags = AvlTreeFlags(insertAllowed = true, updateAllowed = true, removeAllowed = false)
  private val redeemedParameters = PlasmaParameters(32, Some(24))
  private val trackerParameters = PlasmaParameters(32, Some(8))

  private val ergDomain = hex("4241534953020000")
  private val tokenDomain = hex("4241534953020001")

  private def hex(value: String): Array[Byte] = Base16.decode(value).get

  private def claimKey(
    domain: Array[Byte],
    reserveNft: Array[Byte],
    assetId: Option[Array[Byte]],
    receiver: GroupElement = receiverPk
  ): Array[Byte] =
    Blake2b256(
      domain ++ reserveNft ++ assetId.getOrElse(Array.emptyByteArray) ++
        trackerNft ++ ownerPk.getEncoded.toArray ++ receiver.getEncoded.toArray
    )

  private def message(key: Array[Byte], debt: Long = totalDebt, ts: Long = timestamp): Array[Byte] =
    key ++ Longs.toByteArray(debt) ++ Longs.toByteArray(ts)

  private def signatureBytes(messageBytes: Array[Byte], secret: BigInt): Array[Byte] = {
    val signature = SigUtils.sign(messageBytes, secret)
    GroupElementSerializer.toBytes(signature._1) ++ signature._2.toByteArray
  }

  private case class StateStep(
    inputTree: ErgoValue[AvlTree],
    lookupProof: Array[Byte],
    updateProof: Array[Byte],
    outputTree: ErgoValue[AvlTree]
  )

  private def stateStep(
    key: Array[Byte],
    oldValue: Option[(Long, Long, Long)],
    newValue: (Long, Long, Long)
  ): StateStep = {
    val tree = new PlasmaMap[Array[Byte], Array[Byte]](redeemedFlags, redeemedParameters)
    oldValue.foreach { case (ts, debt, redeemed) =>
      tree.insertOrUpdate(key -> stateValue(ts, debt, redeemed))
    }
    val inputTree = tree.ergoValue
    val lookupProof = tree.lookUp(key).proof.bytes
    val updateProof = tree.insertOrUpdate(key -> stateValue(newValue._1, newValue._2, newValue._3)).proof.bytes
    StateStep(inputTree, lookupProof, updateProof, tree.ergoValue)
  }

  private def stateValue(ts: Long, debt: Long, redeemed: Long): Array[Byte] =
    Longs.toByteArray(ts) ++ Longs.toByteArray(debt) ++ Longs.toByteArray(redeemed)

  private case class TrackerState(tree: ErgoValue[AvlTree], lookupProof: Array[Byte])

  private def trackerState(key: Array[Byte], debt: Long = totalDebt): TrackerState = {
    val tree = new PlasmaMap[Array[Byte], Array[Byte]](trackerFlags, trackerParameters)
    tree.insertOrUpdate(key -> Longs.toByteArray(debt))
    TrackerState(tree.ergoValue, tree.lookUp(key).proof.bytes)
  }

  private def createOut(
    tree: ErgoTree,
    value: Long,
    registers: Array[ErgoValue[_]],
    tokens: Array[ErgoToken]
  )(implicit ctx: BlockchainContext): OutBoxImpl = {
    val candidate = AppkitHelpers.createBoxCandidate(value, tree, tokens, registers, ctx.getHeight)
    new OutBoxImpl(candidate)
  }

  private def createOut(
    contract: String,
    value: Long,
    registers: Array[ErgoValue[_]],
    tokens: Array[ErgoToken]
  )(implicit ctx: BlockchainContext): OutBoxImpl =
    createOut(ctx.compileContract(ConstantsBuilder.empty(), contract).getErgoTree, value, registers, tokens)

  private def p2pkTree(key: GroupElement): ErgoTree = P2PKAddress(ProveDlog(key)).script

  private def addTokens(builder: OutBoxBuilder)(tokens: java.util.List[ErgoToken]): OutBoxBuilder =
    if (tokens.isEmpty) builder else builder.tokens(tokens.asScala: _*)

  private def addRegisters(builder: OutBoxBuilder)(registers: java.util.List[ErgoValue[_]]): OutBoxBuilder =
    if (registers.isEmpty) builder else builder.registers(registers.asScala: _*)

  private def createTx(
    inputBoxes: Array[InputBox],
    dataInputs: Array[InputBox],
    boxesToCreate: Array[OutBoxImpl],
    fee: Option[Long] = None,
    secrets: Array[String] = Array.empty
  )(implicit ctx: BlockchainContext): SignedTransaction = {
    val txBuilder = ctx.newTxBuilder
    val outputs: Array[OutBox] = boxesToCreate.map { box =>
      val base = txBuilder.outBoxBuilder()
        .value(box.getValue)
        .creationHeight(box.getCreationHeight)
        .contract(new ErgoTreeContract(box.getErgoTree, NetworkType.MAINNET))
      addRegisters(addTokens(base)(box.getTokens))(box.getRegisters).build
    }
    val unsignedNoFee = ctx.newTxBuilder()
      .boxesToSpend(new util.ArrayList[InputBox](inputBoxes.toList.asJava))
      .withDataInputs(new util.ArrayList[InputBox](dataInputs.toList.asJava))
      .outputs(outputs: _*)
      .sendChangeTo(P2PKAddress(ProveDlog(ownerPk)))
    val unsigned = fee.map(unsignedNoFee.fee).getOrElse(unsignedNoFee).build()
    val prover = secrets.map(BigInt(_)).foldLeft(ctx.newProverBuilder()) {
      case (builder, secret) => builder.withDLogSecret(secret.bigInteger)
    }.build()
    prover.sign(unsigned)
  }

  private def reserveRegisters(
    tree: ErgoValue[AvlTree],
    emergencyHeight: Long,
    predecessor: Array[Byte] = zeroId,
    refundHeight: Long = 0L
  ): Array[ErgoValue[_]] = Array(
    ErgoValue.of(ownerPk),
    tree,
    ErgoValue.of(trackerNft),
    ErgoValue.of(refundHeight),
    ErgoValue.of(emergencyHeight),
    ErgoValue.of(predecessor)
  )

  private def redemptionVars(
    reserveSignature: Array[Byte],
    step: StateStep,
    trackerSignature: Option[Array[Byte]],
    trackerProof: Option[Array[Byte]],
    includePriorProof: Boolean = true,
    receiver: GroupElement = receiverPk,
    debt: Long = totalDebt,
    ts: Long = timestamp
  ): Array[ContextVar] = {
    val base = Vector(
      new ContextVar(0, ErgoValue.of(0: Byte)),
      new ContextVar(1, ErgoValue.of(receiver)),
      new ContextVar(2, ErgoValue.of(reserveSignature)),
      new ContextVar(3, ErgoValue.of(debt)),
      new ContextVar(4, ErgoValue.of(ts)),
      new ContextVar(5, ErgoValue.of(step.updateProof))
    )
    val withTrackerSig = trackerSignature.fold(base)(sig => base :+ new ContextVar(6, ErgoValue.of(sig)))
    val withPrior = if (includePriorProof) withTrackerSig :+ new ContextVar(7, ErgoValue.of(step.lookupProof)) else withTrackerSig
    trackerProof.fold(withPrior)(proof => withPrior :+ new ContextVar(8, ErgoValue.of(proof))).toArray
  }

  private def ergReserveInput(
    txId: String,
    reserveNft: Array[Byte],
    value: Long,
    step: StateStep,
    emergencyHeight: Long,
    vars: Array[ContextVar],
    refundHeight: Long = 0L
  )(implicit ctx: BlockchainContext): InputBox =
    ctx.newTxBuilder.outBoxBuilder
      .value(value)
      .tokens(new ErgoToken(reserveNft, 1))
      .registers(reserveRegisters(step.inputTree, emergencyHeight, refundHeight = refundHeight): _*)
      .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.basisV2Contract))
      .build()
      .convertToInputWith(txId, fakeIndex)
      .withContextVars(vars: _*)

  private def tokenReserveInput(
    txId: String,
    value: Long,
    reserveAmount: Long,
    step: StateStep,
    emergencyHeight: Long,
    vars: Array[ContextVar],
    refundHeight: Long = 0L
  )(implicit ctx: BlockchainContext): InputBox =
    ctx.newTxBuilder.outBoxBuilder
      .value(value)
      .tokens(new ErgoToken(tokenReserveNft, 1), new ErgoToken(reserveTokenId, reserveAmount))
      .registers(reserveRegisters(step.inputTree, emergencyHeight, refundHeight = refundHeight): _*)
      .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.basisTokenV2Contract))
      .build()
      .convertToInputWith(txId, fakeIndex)
      .withContextVars(vars: _*)

  private def trackerInput(state: TrackerState)(implicit ctx: BlockchainContext): InputBox =
    ctx.newTxBuilder.outBoxBuilder
      .value(minValue)
      .tokens(new ErgoToken(trackerNft, 1))
      .registers(ErgoValue.of(trackerPk), state.tree)
      .contract(ctx.compileContract(ConstantsBuilder.empty(), "sigmaProp(true)"))
      .build()
      .convertToInputWith(fakeTxIds(5), fakeIndex)

  private def feeInput(
    txId: String,
    value: Long = minValue,
    tokens: Array[ErgoToken] = Array.empty
  )(implicit ctx: BlockchainContext): InputBox = {
    val builder = ctx.newTxBuilder.outBoxBuilder.value(value)
      .contract(ctx.compileContract(ConstantsBuilder.empty(), "sigmaProp(true)"))
    val withTokens = if (tokens.isEmpty) builder else builder.tokens(tokens: _*)
    withTokens.build().convertToInputWith(txId, fakeIndex)
  }

  private def ergSuccessor(
    input: InputBox,
    value: Long,
    tree: ErgoValue[AvlTree],
    reserveNft: Array[Byte],
    emergencyHeight: Long,
    refundHeight: Long = 0L
  )(implicit ctx: BlockchainContext): OutBoxImpl =
    createOut(
      Constants.basisV2Contract,
      value,
      reserveRegisters(tree, emergencyHeight, input.getId.getBytes, refundHeight),
      Array(new ErgoToken(reserveNft, 1))
    )

  private def tokenSuccessor(
    input: InputBox,
    value: Long,
    reserveAmount: Long,
    tree: ErgoValue[AvlTree],
    emergencyHeight: Long,
    refundHeight: Long = 0L
  )(implicit ctx: BlockchainContext): OutBoxImpl =
    createOut(
      Constants.basisTokenV2Contract,
      value,
      reserveRegisters(tree, emergencyHeight, input.getId.getBytes, refundHeight),
      Array(new ErgoToken(tokenReserveNft, 1), new ErgoToken(reserveTokenId, reserveAmount))
    )

  private def ergPayout(
    input: InputBox,
    value: Long,
    receiver: GroupElement = receiverPk
  )(implicit ctx: BlockchainContext): OutBoxImpl =
    createOut(
      p2pkTree(receiver),
      value,
      Array[ErgoValue[_]](ErgoValue.of(input.getId.getBytes)),
      Array.empty[ErgoToken]
    )

  private def tokenPayout(
    input: InputBox,
    amount: Long,
    receiver: GroupElement = receiverPk
  )(implicit ctx: BlockchainContext): OutBoxImpl =
    createOut(
      p2pkTree(receiver),
      minValue,
      Array[ErgoValue[_]](ErgoValue.of(input.getId.getBytes)),
      Array(new ErgoToken(reserveTokenId, amount))
    )

  property("Basis v2 normal redemption accepts an exact receiver payout with external fee funding") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx =>
      val amount = 300000000L
      val key = claimKey(ergDomain, reserveNftA, None)
      val step = stateStep(key, None, (timestamp, totalDebt, amount))
      // A later tracker commitment may be larger; the older signed cumulative
      // claim remains independently redeemable up to its own totalDebt.
      val tracker = trackerState(key, totalDebt + 1L)
      val claimMessage = message(key)
      val input = ergReserveInput(
        fakeTxIds(0), reserveNftA, minValue + totalDebt, step, ctx.getHeight + 1000L,
        redemptionVars(
          signatureBytes(claimMessage, ownerSecret), step,
          Some(signatureBytes(claimMessage, trackerSecret)), Some(tracker.lookupProof)
        )
      )
      val successor = ergSuccessor(input, input.getValue - amount, step.outputTree, reserveNftA, ctx.getHeight + 1000L)
      val payout = ergPayout(input, amount)

      noException should be thrownBy createTx(
        Array(input, feeInput(fakeTxIds(1))), Array(trackerInput(tracker)),
        Array(successor, payout), fee = Some(feeValue), secrets = Array(receiverSecret.toString)
      )
    }
  }

  property("Basis v2 rejects cross-reserve and cross-domain signed claims independently") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx =>
      val amount = 300000000L
      val keyA = claimKey(ergDomain, reserveNftA, None)
      val keyB = claimKey(ergDomain, reserveNftB, None)
      val stepB = stateStep(keyB, None, (timestamp, totalDebt, amount))
      val trackerB = trackerState(keyB)
      val wrongMessage = message(keyA)
      val inputB = ergReserveInput(
        fakeTxIds(0), reserveNftB, minValue + totalDebt, stepB, ctx.getHeight + 1000L,
        redemptionVars(
          signatureBytes(wrongMessage, ownerSecret), stepB,
          Some(signatureBytes(wrongMessage, trackerSecret)), Some(trackerB.lookupProof)
        )
      )

      a[Throwable] should be thrownBy createTx(
        Array(inputB), Array(trackerInput(trackerB)),
        Array(
          ergSuccessor(inputB, inputB.getValue - amount, stepB.outputTree, reserveNftB, ctx.getHeight + 1000L),
          ergPayout(inputB, amount)
        ),
        secrets = Array(receiverSecret.toString)
      )

      val expectedKey = claimKey(ergDomain, reserveNftA, None)
      val wrongDomainKey = claimKey(tokenDomain, reserveNftA, None)
      val expectedStep = stateStep(expectedKey, None, (timestamp, totalDebt, amount))
      val expectedTracker = trackerState(expectedKey)
      val wrongDomainMessage = message(wrongDomainKey)
      val wrongDomainInput = ergReserveInput(
        fakeTxIds(1), reserveNftA, minValue + totalDebt, expectedStep,
        ctx.getHeight + 1000L,
        redemptionVars(
          signatureBytes(wrongDomainMessage, ownerSecret), expectedStep,
          Some(signatureBytes(wrongDomainMessage, trackerSecret)),
          Some(expectedTracker.lookupProof)
        )
      )

      a[Throwable] should be thrownBy createTx(
        Array(wrongDomainInput), Array(trackerInput(expectedTracker)),
        Array(
          ergSuccessor(
            wrongDomainInput, wrongDomainInput.getValue - amount,
            expectedStep.outputTree, reserveNftA, ctx.getHeight + 1000L
          ),
          ergPayout(wrongDomainInput, amount)
        ),
        secrets = Array(receiverSecret.toString)
      )
    }
  }

  property("Basis v2 rejects an omitted prior-state proof") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx =>
      val amount = 300000000L
      val key = claimKey(ergDomain, reserveNftA, None)
      val step = stateStep(key, None, (timestamp, totalDebt, amount))
      val tracker = trackerState(key)
      val claimMessage = message(key)
      val input = ergReserveInput(
        fakeTxIds(0), reserveNftA, minValue + totalDebt, step, ctx.getHeight + 1000L,
        redemptionVars(
          signatureBytes(claimMessage, ownerSecret), step,
          Some(signatureBytes(claimMessage, trackerSecret)), Some(tracker.lookupProof),
          includePriorProof = false
        )
      )

      a[Throwable] should be thrownBy createTx(
        Array(input), Array(trackerInput(tracker)),
        Array(
          ergSuccessor(input, input.getValue - amount, step.outputTree, reserveNftA, ctx.getHeight + 1000L),
          ergPayout(input, amount)
        ), secrets = Array(receiverSecret.toString)
      )
    }
  }

  property("Basis v2 rejects a stale absence proof for an existing redeemed record") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx =>
      val firstAmount = 300000000L
      val secondAmount = 200000000L
      val key = claimKey(ergDomain, reserveNftA, None)
      val validStep = stateStep(
        key,
        Some((timestamp, totalDebt, firstAmount)),
        (timestamp, totalDebt, firstAmount + secondAmount)
      )
      val emptyStep = stateStep(key, None, (timestamp, totalDebt, secondAmount))
      val staleProofStep = validStep.copy(lookupProof = emptyStep.lookupProof)
      val tracker = trackerState(key)
      val claimMessage = message(key)
      val input = ergReserveInput(
        fakeTxIds(0), reserveNftA, minValue + totalDebt - firstAmount,
        staleProofStep, ctx.getHeight + 1000L,
        redemptionVars(
          signatureBytes(claimMessage, ownerSecret), staleProofStep,
          Some(signatureBytes(claimMessage, trackerSecret)), Some(tracker.lookupProof)
        )
      )

      a[Throwable] should be thrownBy createTx(
        Array(input), Array(trackerInput(tracker)),
        Array(
          ergSuccessor(input, input.getValue - secondAmount, validStep.outputTree, reserveNftA, ctx.getHeight + 1000L),
          ergPayout(input, secondAmount)
        ), secrets = Array(receiverSecret.toString)
      )
    }
  }

  property("Basis v2 rejects mutated receiver, payout amount, and reserve-funded fee independently") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx =>
      val amount = 300000000L
      val key = claimKey(ergDomain, reserveNftA, None)
      val step = stateStep(key, None, (timestamp, totalDebt, amount))
      val tracker = trackerState(key)
      val claimMessage = message(key)

      def input(txId: String) = ergReserveInput(
        txId, reserveNftA, minValue + totalDebt, step, ctx.getHeight + 1000L,
        redemptionVars(
          signatureBytes(claimMessage, ownerSecret), step,
          Some(signatureBytes(claimMessage, trackerSecret)), Some(tracker.lookupProof)
        )
      )

      val wrongReceiverInput = input(fakeTxIds(0))
      a[Throwable] should be thrownBy createTx(
        Array(wrongReceiverInput), Array(trackerInput(tracker)),
        Array(
          ergSuccessor(wrongReceiverInput, wrongReceiverInput.getValue - amount, step.outputTree, reserveNftA, ctx.getHeight + 1000L),
          ergPayout(wrongReceiverInput, amount, otherPk)
        ), secrets = Array(receiverSecret.toString)
      )

      val wrongAmountInput = input(fakeTxIds(1))
      a[Throwable] should be thrownBy createTx(
        Array(wrongAmountInput), Array(trackerInput(tracker)),
        Array(
          ergSuccessor(wrongAmountInput, wrongAmountInput.getValue - amount, step.outputTree, reserveNftA, ctx.getHeight + 1000L),
          ergPayout(wrongAmountInput, amount - feeValue)
        ), secrets = Array(receiverSecret.toString)
      )

      val feeDrainInput = input(fakeTxIds(2))
      a[Throwable] should be thrownBy createTx(
        Array(feeDrainInput), Array(trackerInput(tracker)),
        Array(
          ergSuccessor(feeDrainInput, feeDrainInput.getValue - amount - feeValue, step.outputTree, reserveNftA, ctx.getHeight + 1000L),
          ergPayout(feeDrainInput, amount)
        ), fee = Some(feeValue), secrets = Array(receiverSecret.toString)
      )
    }
  }

  property("Basis v2 permits repeated partial settlement with the original signed cumulative claim") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx =>
      val firstAmount = 300000000L
      val secondAmount = 400000000L
      val key = claimKey(ergDomain, reserveNftA, None)
      val tracker = trackerState(key)
      val claimMessage = message(key)
      val ownerSignature = signatureBytes(claimMessage, ownerSecret)
      val trackerSignature = signatureBytes(claimMessage, trackerSecret)

      val firstStep = stateStep(key, None, (timestamp, totalDebt, firstAmount))
      val firstInput = ergReserveInput(
        fakeTxIds(0), reserveNftA, minValue + totalDebt, firstStep, ctx.getHeight + 1000L,
        redemptionVars(ownerSignature, firstStep, Some(trackerSignature), Some(tracker.lookupProof))
      )
      val firstTx = createTx(
        Array(firstInput), Array(trackerInput(tracker)),
        Array(
          ergSuccessor(firstInput, firstInput.getValue - firstAmount, firstStep.outputTree, reserveNftA, ctx.getHeight + 1000L),
          ergPayout(firstInput, firstAmount)
        ), secrets = Array(receiverSecret.toString)
      )

      val secondStep = stateStep(
        key,
        Some((timestamp, totalDebt, firstAmount)),
        (timestamp, totalDebt, firstAmount + secondAmount)
      )
      val secondInput = firstTx.getOutputsToSpend.get(0).withContextVars(
        redemptionVars(ownerSignature, secondStep, Some(trackerSignature), Some(tracker.lookupProof)): _*
      )

      noException should be thrownBy createTx(
        Array(secondInput), Array(trackerInput(tracker)),
        Array(
          ergSuccessor(secondInput, secondInput.getValue - secondAmount, secondStep.outputTree, reserveNftA, ctx.getHeight + 1000L),
          ergPayout(secondInput, secondAmount)
        ), secrets = Array(receiverSecret.toString)
      )
    }
  }

  property("Basis v2 emergency redemption needs no tracker input, proof, or signature only at the fixed boundary") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx =>
      val amount = 300000000L
      val key = claimKey(ergDomain, reserveNftA, None)
      val step = stateStep(key, None, (timestamp, totalDebt, amount))
      val claimMessage = message(key)
      val ownerSignature = signatureBytes(claimMessage, ownerSecret)

      val emergencyInput = ergReserveInput(
        fakeTxIds(0), reserveNftA, minValue + totalDebt, step, ctx.getHeight.toLong,
        redemptionVars(ownerSignature, step, None, None)
      )
      noException should be thrownBy createTx(
        Array(emergencyInput), Array.empty,
        Array(
          ergSuccessor(emergencyInput, emergencyInput.getValue - amount, step.outputTree, reserveNftA, ctx.getHeight.toLong),
          ergPayout(emergencyInput, amount)
        ), secrets = Array(receiverSecret.toString)
      )

      val preBoundaryInput = ergReserveInput(
        fakeTxIds(1), reserveNftA, minValue + totalDebt, step, ctx.getHeight + 1L,
        redemptionVars(ownerSignature, step, None, None)
      )
      a[Throwable] should be thrownBy createTx(
        Array(preBoundaryInput), Array.empty,
        Array(
          ergSuccessor(preBoundaryInput, preBoundaryInput.getValue - amount, step.outputTree, reserveNftA, ctx.getHeight + 1L),
          ergPayout(preBoundaryInput, amount)
        ), secrets = Array(receiverSecret.toString)
      )
    }
  }

  property("Basis v2 rejects tokenless reserve state and two reserve inputs sharing one successor") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx =>
      val key = claimKey(ergDomain, reserveNftA, None)
      val step = stateStep(key, None, (timestamp, totalDebt, 1L))
      val emergency = ctx.getHeight + 1000L

      val tokenless = ctx.newTxBuilder.outBoxBuilder
        .value(minValue)
        .registers(reserveRegisters(step.inputTree, emergency): _*)
        .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.basisV2Contract))
        .build().convertToInputWith(fakeTxIds(0), fakeIndex)
        .withContextVars(new ContextVar(0, ErgoValue.of(10: Byte)))
      val tokenlessOut = createOut(
        Constants.basisV2Contract, minValue + 100000000L,
        reserveRegisters(step.inputTree, emergency, tokenless.getId.getBytes), Array.empty[ErgoToken]
      )
      a[Throwable] should be thrownBy createTx(Array(tokenless), Array.empty, Array(tokenlessOut))

      def topUpInput(txId: String) = ctx.newTxBuilder.outBoxBuilder
        .value(minValue)
        .tokens(new ErgoToken(reserveNftA, 1))
        .registers(reserveRegisters(step.inputTree, emergency): _*)
        .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.basisV2Contract))
        .build().convertToInputWith(txId, fakeIndex)
        .withContextVars(new ContextVar(0, ErgoValue.of(10: Byte)))

      val first = topUpInput(fakeTxIds(1))
      val second = topUpInput(fakeTxIds(2))
      val shared = ergSuccessor(first, minValue + 100000000L, step.inputTree, reserveNftA, emergency)
      a[Throwable] should be thrownBy createTx(Array(first, second), Array.empty, Array(shared))
    }
  }

  property("Basis token v2 preserves ERG on top-up and redemption") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx =>
      val amount = 300L
      val reserveAmount = 1000L
      val emergency = ctx.getHeight + 1000L
      val key = claimKey(tokenDomain, tokenReserveNft, Some(reserveTokenId))
      val step = stateStep(key, None, (timestamp, totalDebt, amount))

      val topUpInput = tokenReserveInput(
        fakeTxIds(0), minValue * 2, reserveAmount, step, emergency,
        Array(new ContextVar(0, ErgoValue.of(10: Byte)))
      )
      val tokenFunding = feeInput(fakeTxIds(1), tokens = Array(new ErgoToken(reserveTokenId, 1)))
      val drainingTopUp = tokenSuccessor(
        topUpInput, minValue, reserveAmount + 1, step.inputTree, emergency
      )
      a[Throwable] should be thrownBy createTx(
        Array(topUpInput, tokenFunding), Array.empty, Array(drainingTopUp)
      )

      val tracker = trackerState(key)
      val claimMessage = message(key)
      val redemptionInput = tokenReserveInput(
        fakeTxIds(2), minValue * 2, reserveAmount, step, emergency,
        redemptionVars(
          signatureBytes(claimMessage, ownerSecret), step,
          Some(signatureBytes(claimMessage, trackerSecret)), Some(tracker.lookupProof)
        )
      )
      val drainingSuccessor = tokenSuccessor(
        redemptionInput, minValue, reserveAmount - amount, step.outputTree, emergency
      )
      a[Throwable] should be thrownBy createTx(
        Array(redemptionInput), Array(trackerInput(tracker)),
        Array(drainingSuccessor, tokenPayout(redemptionInput, amount)),
        secrets = Array(receiverSecret.toString)
      )
    }
  }

  property("Basis token v2 accepts exact token payout and rejects token leakage to change") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx =>
      val amount = 300L
      val reserveAmount = 1000L
      val emergency = ctx.getHeight + 1000L
      val key = claimKey(tokenDomain, tokenReserveNft, Some(reserveTokenId))
      val step = stateStep(key, None, (timestamp, totalDebt, amount))
      val tracker = trackerState(key)
      val claimMessage = message(key)

      def input(txId: String) = tokenReserveInput(
        txId, minValue * 2, reserveAmount, step, emergency,
        redemptionVars(
          signatureBytes(claimMessage, ownerSecret), step,
          Some(signatureBytes(claimMessage, trackerSecret)), Some(tracker.lookupProof)
        )
      )

      val validInput = input(fakeTxIds(0))
      noException should be thrownBy createTx(
        Array(validInput, feeInput(fakeTxIds(1))), Array(trackerInput(tracker)),
        Array(
          tokenSuccessor(validInput, validInput.getValue, reserveAmount - amount, step.outputTree, emergency),
          tokenPayout(validInput, amount)
        ), secrets = Array(receiverSecret.toString)
      )

      val leakingInput = input(fakeTxIds(2))
      a[Throwable] should be thrownBy createTx(
        Array(leakingInput, feeInput(fakeTxIds(3))), Array(trackerInput(tracker)),
        Array(
          tokenSuccessor(leakingInput, leakingInput.getValue, reserveAmount - amount, step.outputTree, emergency),
          tokenPayout(leakingInput, amount - 1)
        ), secrets = Array(receiverSecret.toString)
      )
    }
  }

  property("Basis v2 top-up branches preserve state and accept only externally funded increases") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx =>
      val emergency = ctx.getHeight + 1000L
      val ergKey = claimKey(ergDomain, reserveNftA, None)
      val ergStep = stateStep(ergKey, None, (timestamp, totalDebt, 1L))
      val ergInput = ergReserveInput(
        fakeTxIds(0), reserveNftA, minValue, ergStep, emergency,
        Array(new ContextVar(0, ErgoValue.of(10: Byte)))
      )
      noException should be thrownBy createTx(
        Array(ergInput, feeInput(fakeTxIds(1))), Array.empty,
        Array(ergSuccessor(ergInput, minValue + 100000000L, ergStep.inputTree, reserveNftA, emergency))
      )

      val tokenKey = claimKey(tokenDomain, tokenReserveNft, Some(reserveTokenId))
      val tokenStep = stateStep(tokenKey, None, (timestamp, totalDebt, 1L))
      val tokenInput = tokenReserveInput(
        fakeTxIds(2), minValue, 1000L, tokenStep, emergency,
        Array(new ContextVar(0, ErgoValue.of(10: Byte)))
      )
      noException should be thrownBy createTx(
        Array(
          tokenInput,
          feeInput(fakeTxIds(3), tokens = Array(new ErgoToken(reserveTokenId, 1L)))
        ),
        Array.empty,
        Array(tokenSuccessor(tokenInput, minValue, 1001L, tokenStep.inputTree, emergency))
      )
    }
  }

  property("Basis v2 refund initiation and completion enforce owner proof and overflow-safe delay") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx =>
      val emergency = ctx.getHeight + 100000L
      val key = claimKey(ergDomain, reserveNftA, None)
      val step = stateStep(key, None, (timestamp, totalDebt, 1L))

      val initiateInput = ergReserveInput(
        fakeTxIds(0), reserveNftA, minValue, step, emergency,
        Array(new ContextVar(0, ErgoValue.of(20: Byte)))
      )
      noException should be thrownBy createTx(
        Array(initiateInput), Array.empty,
        Array(
          ergSuccessor(
            initiateInput, minValue, step.inputTree, reserveNftA, emergency,
            refundHeight = ctx.getHeight.toLong
          )
        ), secrets = Array(ownerSecret.toString)
      )

      val maturedHeight = ctx.getHeight.toLong - 43200L
      val completeInput = ergReserveInput(
        fakeTxIds(1), reserveNftA, minValue, step, emergency,
        Array(new ContextVar(0, ErgoValue.of(30: Byte))), refundHeight = maturedHeight
      )
      val ownerOutput = createOut(
        p2pkTree(ownerPk), minValue, Array.empty[ErgoValue[_]],
        Array(new ErgoToken(reserveNftA, 1L))
      )
      noException should be thrownBy createTx(
        Array(completeInput), Array.empty, Array(ownerOutput),
        secrets = Array(ownerSecret.toString)
      )

      val earlyInput = ergReserveInput(
        fakeTxIds(2), reserveNftA, minValue, step, emergency,
        Array(new ContextVar(0, ErgoValue.of(30: Byte))),
        refundHeight = ctx.getHeight.toLong - 43199L
      )
      a[Throwable] should be thrownBy createTx(
        Array(earlyInput), Array.empty, Array(ownerOutput),
        secrets = Array(ownerSecret.toString)
      )

      val overflowSeed = ergReserveInput(
        fakeTxIds(3), reserveNftA, minValue, step, emergency,
        Array(new ContextVar(0, ErgoValue.of(30: Byte))), refundHeight = Long.MaxValue
      )
      a[Throwable] should be thrownBy createTx(
        Array(overflowSeed), Array.empty, Array(ownerOutput),
        secrets = Array(ownerSecret.toString)
      )
    }
  }

  property("Basis token v2 refund branches preserve both token roles until owner-authorized completion") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx =>
      val emergency = ctx.getHeight + 100000L
      val key = claimKey(tokenDomain, tokenReserveNft, Some(reserveTokenId))
      val step = stateStep(key, None, (timestamp, totalDebt, 1L))
      val reserveAmount = 1000L

      val initiateInput = tokenReserveInput(
        fakeTxIds(0), minValue, reserveAmount, step, emergency,
        Array(new ContextVar(0, ErgoValue.of(20: Byte)))
      )
      noException should be thrownBy createTx(
        Array(initiateInput), Array.empty,
        Array(
          tokenSuccessor(
            initiateInput, minValue, reserveAmount, step.inputTree, emergency,
            refundHeight = ctx.getHeight.toLong
          )
        ), secrets = Array(ownerSecret.toString)
      )

      val completeInput = tokenReserveInput(
        fakeTxIds(1), minValue, reserveAmount, step, emergency,
        Array(new ContextVar(0, ErgoValue.of(30: Byte))),
        refundHeight = ctx.getHeight.toLong - 43200L
      )
      val ownerOutput = createOut(
        p2pkTree(ownerPk), minValue, Array.empty[ErgoValue[_]],
        Array(
          new ErgoToken(tokenReserveNft, 1L),
          new ErgoToken(reserveTokenId, reserveAmount)
        )
      )
      noException should be thrownBy createTx(
        Array(completeInput), Array.empty, Array(ownerOutput),
        secrets = Array(ownerSecret.toString)
      )
    }
  }

  property("Basis v2 source constants compile from the exact repository files") {
    val root = Paths.get(sys.props("user.dir"))
    val ergSource = new String(Files.readAllBytes(root.resolve("contracts/offchain/basis-v2.es")), StandardCharsets.UTF_8)
      .replace("\r\n", "\n").stripSuffix("\n")
    val tokenSource = new String(Files.readAllBytes(root.resolve("contracts/offchain/basis-token-v2.es")), StandardCharsets.UTF_8)
      .replace("\r\n", "\n").stripSuffix("\n")
    Constants.basisV2Contract shouldEqual ergSource
    Constants.basisTokenV2Contract shouldEqual tokenSource

    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx =>
      Constants.basisV2ErgoTree.bytes shouldEqual
        ctx.compileContract(ConstantsBuilder.empty(), ergSource).getErgoTree.bytes
      Constants.basisTokenV2ErgoTree.bytes shouldEqual
        ctx.compileContract(ConstantsBuilder.empty(), tokenSource).getErgoTree.bytes
    }
  }

  property("Basis v2 full ErgoTree bytes match the committed golden files") {
    val root = Paths.get(sys.props("user.dir"))
    def expected(path: String): Array[Byte] =
      hex(new String(Files.readAllBytes(root.resolve(path)), StandardCharsets.US_ASCII).trim)
    Constants.basisV2ErgoTree.bytes shouldEqual expected("contracts/offchain/basis-v2.p2s")
    Constants.basisTokenV2ErgoTree.bytes shouldEqual expected("contracts/offchain/basis-token-v2.p2s")

    // Keep the digest calculation in the test so reviewers can independently
    // compare the full bytes before relying on the shorter receipt hashes.
    MessageDigest.getInstance("SHA-256").digest(Constants.basisV2ErgoTree.bytes).length shouldEqual 32
    MessageDigest.getInstance("SHA-256").digest(Constants.basisTokenV2ErgoTree.bytes).length shouldEqual 32
  }
}
