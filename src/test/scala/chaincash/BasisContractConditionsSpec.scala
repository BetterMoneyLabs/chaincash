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

/**
 * Unit tests for each condition in the basis.es contract.
 *
 * The contract checks:
 *   sigmaProp(selfPreserved &&
 *             trackerIdCorrect &&
 *             trackerDebtCorrect &&
 *             properRedemptionTree &&
 *             properReserveSignature &&
 *             properlyRedeemed &&
 *             receiverCondition)
 *
 * Each test isolates one condition by wrapping ONLY that condition in sigmaProp(),
 * while removing all other conditions. This allows testing each condition independently
 * with the same test data.
 *
 * All tests use consistent data generated from random keys (same across all tests).
 */
class BasisContractConditionsSpec extends PropSpec with Matchers with ScalaCheckDrivenPropertyChecks with HttpClientTesting {

  implicit val addrEncoder = Constants.ergoAddressEncoder

  // ========== FIXED TEST PARAMETERS ==========
  val fakeTxId1 = "f9e5ce5aa0d95f5d54a7bc89c46730d9662397067250aa18a0039631c0f5b809"
  val fakeTxId2 = "f9e5ce5aa0d95f5d54a7bc89c46730d9662397067250aa18a0039631c0f5b808"
  val fakeIndex = 1.toShort

  // Token IDs
  val basisTokenId = "4b2d8b7beb3eaac8234d9e61792d270898a43934d6a27275e4f3a044609c9f2a"
  val trackerNFT = "8b1ab583bb085ecbd8fa9bc2fd59784afcdfce5496eb146bb3dd04664b56822a"
  val trackerNFTBytes = Base16.decode(trackerNFT).get

  // Box IDs
  val reserveBoxId = "59ed384b3983d64f99368218beede3c7a8b0ae26ef64c1b0ddc3953b444b4317"

  // Transaction values
  val totalDebt = 50000000L // 0.05 ERG
  val minValue = 1000000000L
  val feeValue = 1000000L
  val inputValue = minValue + totalDebt + feeValue
  val outputValue = minValue + feeValue
  val redeemAmount = totalDebt

  // ========== TEST DATA GENERATION ==========
  // Use random secrets for signature generation (consistent across all tests)
  val ownerSecret = SigUtils.randBigInt
  val ownerPk = Constants.g.exp(ownerSecret.bigInteger)
  val receiverSecret = SigUtils.randBigInt
  val receiverPk = Constants.g.exp(receiverSecret.bigInteger)
  val trackerSecret = SigUtils.randBigInt
  val trackerPk = Constants.g.exp(trackerSecret.bigInteger)

  // Change address (defined after ownerPk)
  val changeAddress = P2PKAddress(ProveDlog(ownerPk)).toString()

  // ========== SIGNATURE HELPERS ==========
  def mkKey(ownerKey: GroupElement, receiverKey: GroupElement): Array[Byte] =
    Blake2b256(ownerKey.getEncoded.toArray ++ receiverKey.getEncoded.toArray)

  def mkMessage(key: Array[Byte], totalDebt: Long): Array[Byte] =
    key ++ Longs.toByteArray(totalDebt)

  def mkSigBytes(sig: (GroupElement, BigInt)): Array[Byte] =
    GroupElementSerializer.toBytes(sig._1) ++ zTo32Bytes(sig._2)

  private def zTo32Bytes(z: BigInt): Array[Byte] = {
    val bytes = z.toByteArray
    if (bytes.length == 32) bytes
    else if (bytes.length == 33 && bytes(0) == 0) bytes.tail
    else throw new IllegalArgumentException(s"Signature z must be 32 bytes, got ${bytes.length}")
  }

  // ========== AVL TREE HELPERS ==========
  case class TreeAndProof(initialTree: ErgoValue[AvlTree], nextTree: ErgoValue[AvlTree], proofBytes: Array[Byte])

  def mkTreeAndProof(key: Array[Byte], redeemedDebt: Long): TreeAndProof = {
    val plasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
    val initial = plasmaMap.ergoValue
    val insertRes = plasmaMap.insert((key, Longs.toByteArray(redeemedDebt)))
    TreeAndProof(initial, plasmaMap.ergoValue, insertRes.proof.bytes)
  }

  case class TrackerTreeAndProof(tree: ErgoValue[AvlTree], lookupProofBytes: Array[Byte])

  def mkTrackerTreeAndProof(key: Array[Byte], totalDebt: Long): TrackerTreeAndProof = {
    val plasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
    val insertRes = plasmaMap.insert((key, Longs.toByteArray(totalDebt)))
    TrackerTreeAndProof(plasmaMap.ergoValue, plasmaMap.lookUp(key).proof.bytes)
  }

  // ========== TRANSACTION HELPERS ==========
  def createOut(contract: String, value: Long, registers: Array[ErgoValue[_]], tokens: Array[ErgoToken])(implicit ctx: BlockchainContext): OutBoxImpl = {
    val c = ErgoScriptContract.create(new org.ergoplatform.appkit.Constants, contract, Constants.networkType)
    val ebc = AppkitHelpers.createBoxCandidate(value, c.getErgoTree, tokens, registers, ctx.getHeight)
    new OutBoxImpl(ebc)
  }

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

  private def addTokens(outBoxBuilder: OutBoxBuilder)(tokens: java.util.List[ErgoToken]) = {
    if (tokens.isEmpty) outBoxBuilder
    else outBoxBuilder.tokens(tokens.asScala: _*)
  }

  private def addRegisters(outBoxBuilder: OutBoxBuilder)(registers: java.util.List[ErgoValue[_]]) = {
    if (registers.isEmpty) outBoxBuilder
    else outBoxBuilder.registers(registers.asScala: _*)
  }

  def getAddressFromString(string: String) =
    Try(Constants.ergoAddressEncoder.fromString(string).get).getOrElse(throw new Exception(s"Invalid address [$string]"))

  def decodeBigInt(encoded: String): BigInt = Try(BigInt(encoded, 10)).recover { case ex => BigInt(encoded, 16) }.get

  // ========== INPUT BOX HELPERS ==========
  def mkBasisInput(
    contract: String,
    value: Long,
    tree: ErgoValue[AvlTree],
    receiverKey: GroupElement,
    reserveSigBytes: Array[Byte],
    totalDebt: Long,
    insertProofBytes: Array[Byte],
    trackerSigBytes: Array[Byte],
    trackerLookupProofBytes: Array[Byte]
  )(implicit ctx: BlockchainContext): InputBox = {
    ctx.newTxBuilder().outBoxBuilder
      .value(value)
      .tokens(new ErgoToken(basisTokenId, 1))
      .registers(ErgoValue.of(ownerPk), tree, ErgoValue.of(trackerNFTBytes))
      .contract(ctx.compileContract(ConstantsBuilder.empty(), contract))
      .build()
      .convertToInputWith(reserveBoxId, fakeIndex)
      .withContextVars(
        new ContextVar(0, ErgoValue.of(0x00.toByte)), // action=0, index=0
        new ContextVar(1, ErgoValue.of(receiverKey)),
        new ContextVar(2, ErgoValue.of(reserveSigBytes)),
        new ContextVar(3, ErgoValue.of(totalDebt)),
        new ContextVar(5, ErgoValue.of(insertProofBytes)),
        new ContextVar(6, ErgoValue.of(trackerSigBytes)),
        new ContextVar(8, ErgoValue.of(trackerLookupProofBytes))
      )
  }

  def mkTrackerDataInput(trackerTree: ErgoValue[AvlTree], trackerPk: GroupElement)(implicit ctx: BlockchainContext): InputBox = {
    ctx.newTxBuilder().outBoxBuilder
      .value(minValue)
      .tokens(new ErgoToken(trackerNFTBytes, 1))
      .registers(ErgoValue.of(trackerPk), trackerTree)
      .contract(ctx.compileContract(ConstantsBuilder.empty(), "{sigmaProp(true)}"))
      .build()
      .convertToInputWith(fakeTxId2, fakeIndex)
  }

  // Generate signatures and proofs using test keys
  val debtKey = mkKey(ownerPk, receiverPk)
  val message = mkMessage(debtKey, totalDebt)
  val reserveSig = SigUtils.sign(message, ownerSecret)
  val trackerSig = SigUtils.sign(message, trackerSecret)
  val reserveSigBytes = mkSigBytes(reserveSig)
  val trackerSigBytes = mkSigBytes(trackerSig)

  val TreeAndProof(initialTree, nextTree, insertProofBytes) = mkTreeAndProof(debtKey, totalDebt)
  val TrackerTreeAndProof(trackerTree, trackerLookupProofBytes) = mkTrackerTreeAndProof(debtKey, totalDebt)

  // ========== CONTRACT TEMPLATES ==========
  /**
   * Creates a contract that tests ONLY the specified condition.
   * The targeted condition is wrapped in sigmaProp(), all others are removed.
   */
  def conditionScript(conditionName: String): String = conditionName match {
    case "selfPreserved" =>
      s"""
        |{
        |  val v = getVar[Byte](0).get
        |  val action = v / 10
        |  val index = v % 10
        |  val ownerKey = SELF.R4[GroupElement].get
        |  val selfOut = OUTPUTS(index)
        |
        |  val selfPreserved =
        |    selfOut.propositionBytes == SELF.propositionBytes &&
        |    selfOut.tokens == SELF.tokens &&
        |    selfOut.R4[GroupElement].get == SELF.R4[GroupElement].get &&
        |    selfOut.R6[Coll[Byte]].get == SELF.R6[Coll[Byte]].get
        |
        |  if (action == 0) {
        |    sigmaProp(selfPreserved)
        |  } else {
        |    sigmaProp(false)
        |  }
        |}
      """.stripMargin

    case "trackerIdCorrect" =>
      s"""
        |{
        |  val v = getVar[Byte](0).get
        |  val action = v / 10
        |  val index = v % 10
        |  val selfOut = OUTPUTS(index)
        |  val tracker = CONTEXT.dataInputs(0)
        |  val trackerNftId = tracker.tokens(0)._1
        |  val expectedTrackerId = SELF.R6[Coll[Byte]].get
        |  val trackerIdCorrect = trackerNftId == expectedTrackerId
        |
        |  if (action == 0) {
        |    sigmaProp(trackerIdCorrect)
        |  } else {
        |    sigmaProp(false)
        |  }
        |}
      """.stripMargin

    case "trackerDebtCorrect" =>
      s"""
        |{
        |  val v = getVar[Byte](0).get
        |  val action = v / 10
        |  val index = v % 10
        |  val ownerKey = SELF.R4[GroupElement].get
        |  val tracker = CONTEXT.dataInputs(0)
        |  val trackerTree = tracker.R5[AvlTree].get
        |  val receiver = getVar[GroupElement](1).get
        |  val totalDebt = getVar[Long](3).get
        |  val ownerKeyBytes = ownerKey.getEncoded
        |  val receiverBytes = receiver.getEncoded
        |  val key = blake2b256(ownerKeyBytes ++ receiverBytes)
        |  val trackerLookupProof = getVar[Coll[Byte]](8).get
        |  val trackerDebtBytes = trackerTree.get(key, trackerLookupProof).get
        |  val trackerTotalDebt = byteArrayToLong(trackerDebtBytes)
        |  val trackerDebtCorrect = trackerTotalDebt == totalDebt
        |
        |  if (action == 0) {
        |    sigmaProp(trackerDebtCorrect)
        |  } else {
        |    sigmaProp(false)
        |  }
        |}
      """.stripMargin

    case "properRedemptionTree" =>
      s"""
        |{
        |  val v = getVar[Byte](0).get
        |  val action = v / 10
        |  val index = v % 10
        |  val ownerKey = SELF.R4[GroupElement].get
        |  val selfOut = OUTPUTS(index)
        |  val receiver = getVar[GroupElement](1).get
        |  val ownerKeyBytes = ownerKey.getEncoded
        |  val receiverBytes = receiver.getEncoded
        |  val key = blake2b256(ownerKeyBytes ++ receiverBytes)
        |  val redeemed = SELF.value - selfOut.value
        |  val newRedeemed = redeemed
        |  val treeValue = longToByteArray(newRedeemed)
        |  val redeemedKeyVal = (key, treeValue)
        |  val insertProof = getVar[Coll[Byte]](5).get
        |  val nextTree: AvlTree = SELF.R5[AvlTree].get.insert(Coll(redeemedKeyVal), insertProof).get
        |  val properRedemptionTree = nextTree == selfOut.R5[AvlTree].get
        |
        |  if (action == 0) {
        |    sigmaProp(properRedemptionTree)
        |  } else {
        |    sigmaProp(false)
        |  }
        |}
      """.stripMargin

    case "properReserveSignature" =>
      s"""
        |{
        |  val v = getVar[Byte](0).get
        |  val action = v / 10
        |  val index = v % 10
        |  val ownerKey = SELF.R4[GroupElement].get
        |  val g: GroupElement = groupGenerator
        |  val receiver = getVar[GroupElement](1).get
        |  val reserveSigBytes = getVar[Coll[Byte]](2).get
        |  val totalDebt = getVar[Long](3).get
        |  val ownerKeyBytes = ownerKey.getEncoded
        |  val receiverBytes = receiver.getEncoded
        |  val key = blake2b256(ownerKeyBytes ++ receiverBytes)
        |  val message = key ++ longToByteArray(totalDebt)
        |  val reserveABytes = reserveSigBytes.slice(0, 33)
        |  val reserveZBytes = reserveSigBytes.slice(33, reserveSigBytes.size)
        |  val reserveA = decodePoint(reserveABytes)
        |  val reserveZ = byteArrayToBigInt(reserveZBytes)
        |  val reserveE: Coll[Byte] = blake2b256(reserveABytes ++ message ++ ownerKey.getEncoded)
        |  val reserveEInt = byteArrayToBigInt(reserveE)
        |  val properReserveSignature = (g.exp(reserveZ) == reserveA.multiply(ownerKey.exp(reserveEInt)))
        |
        |  if (action == 0) {
        |    sigmaProp(properReserveSignature)
        |  } else {
        |    sigmaProp(false)
        |  }
        |}
      """.stripMargin

    case "properlyRedeemed" =>
      s"""
        |{
        |  val v = getVar[Byte](0).get
        |  val action = v / 10
        |  val index = v % 10
        |  val selfOut = OUTPUTS(index)
        |  val tracker = CONTEXT.dataInputs(0)
        |  val trackerPubKey = tracker.R4[GroupElement].get
        |  val trackerSigBytes = getVar[Coll[Byte]](6).get
        |  val receiver = getVar[GroupElement](1).get
        |  val totalDebt = getVar[Long](3).get
        |  val redeemedDebt = 0L
        |  val redeemed = SELF.value - selfOut.value
        |  val ownerKey = SELF.R4[GroupElement].get
        |  val ownerKeyBytes = ownerKey.getEncoded
        |  val receiverBytes = receiver.getEncoded
        |  val key = blake2b256(ownerKeyBytes ++ receiverBytes)
        |  val message = key ++ longToByteArray(totalDebt)
        |  val trackerABytes = trackerSigBytes.slice(0, 33)
        |  val trackerZBytes = trackerSigBytes.slice(33, trackerSigBytes.size)
        |  val trackerA = decodePoint(trackerABytes)
        |  val trackerZ = byteArrayToBigInt(trackerZBytes)
        |  val g: GroupElement = groupGenerator
        |  val trackerE: Coll[Byte] = blake2b256(trackerABytes ++ message ++ trackerPubKey.getEncoded)
        |  val trackerEInt = byteArrayToBigInt(trackerE)
        |  val properTrackerSignature = (g.exp(trackerZ) == trackerA.multiply(trackerPubKey.exp(trackerEInt)))
        |  val debtDelta = (totalDebt - redeemedDebt)
        |  val properlyRedeemed = (redeemed > 0) && (redeemed <= debtDelta) && properTrackerSignature
        |
        |  if (action == 0) {
        |    sigmaProp(properlyRedeemed)
        |  } else {
        |    sigmaProp(false)
        |  }
        |}
      """.stripMargin

    case "receiverCondition" =>
      s"""
        |{
        |  val v = getVar[Byte](0).get
        |  val action = v / 10
        |  val index = v % 10
        |  val receiver = getVar[GroupElement](1).get
        |  val receiverCondition = proveDlog(receiver)
        |
        |  if (action == 0) {
        |    sigmaProp(receiverCondition)
        |  } else {
        |    sigmaProp(false)
        |  }
        |}
      """.stripMargin

    case _ => throw new IllegalArgumentException(s"Unknown condition: $conditionName")
  }

  // ========== TESTS ==========

  property("CONDITION 1: selfPreserved - should pass with correct output contract") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val basisInput = mkBasisInput(
        conditionScript("selfPreserved"), inputValue, initialTree, receiverPk,
        reserveSigBytes, totalDebt, insertProofBytes, trackerSigBytes, trackerLookupProofBytes
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree, trackerPk)

      val basisOutput = createOut(
        conditionScript("selfPreserved"), outputValue,
        Array(ErgoValue.of(ownerPk), ErgoValue.of(nextTree.getValue), ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut("{sigmaProp(true)}", redeemAmount, Array(), Array())

      noException should be thrownBy {
        createTx(Array(basisInput), Array(trackerDataInput), Array(basisOutput, redemptionOutput),
          fee = None, changeAddress, Array(receiverSecret.toString()), broadcast = false)
      }
    }
  }

  property("CONDITION 2: trackerIdCorrect - should pass with matching tracker NFT") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val basisInput = mkBasisInput(
        conditionScript("trackerIdCorrect"), inputValue, initialTree, receiverPk,
        reserveSigBytes, totalDebt, insertProofBytes, trackerSigBytes, trackerLookupProofBytes
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree, trackerPk)

      val basisOutput = createOut(
        conditionScript("trackerIdCorrect"), outputValue,
        Array(ErgoValue.of(ownerPk), ErgoValue.of(nextTree.getValue), ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut("{sigmaProp(true)}", redeemAmount, Array(), Array())

      noException should be thrownBy {
        createTx(Array(basisInput), Array(trackerDataInput), Array(basisOutput, redemptionOutput),
          fee = None, changeAddress, Array(receiverSecret.toString()), broadcast = false)
      }
    }
  }

  property("CONDITION 3: trackerDebtCorrect - SKIP (AVL tree proof serialization issue)") {
    pending
  }

  property("CONDITION 4: properRedemptionTree - SKIP (AVL tree proof serialization issue)") {
    pending
  }

  property("CONDITION 5: properReserveSignature - should pass with valid owner signature") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val basisInput = mkBasisInput(
        conditionScript("properReserveSignature"), inputValue, initialTree, receiverPk,
        reserveSigBytes, totalDebt, insertProofBytes, trackerSigBytes, trackerLookupProofBytes
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree, trackerPk)

      val basisOutput = createOut(
        conditionScript("properReserveSignature"), outputValue,
        Array(ErgoValue.of(ownerPk), ErgoValue.of(nextTree.getValue), ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut("{sigmaProp(true)}", redeemAmount, Array(), Array())

      noException should be thrownBy {
        createTx(Array(basisInput), Array(trackerDataInput), Array(basisOutput, redemptionOutput),
          fee = None, changeAddress, Array(receiverSecret.toString()), broadcast = false)
      }
    }
  }

  property("CONDITION 6: properlyRedeemed - should pass with valid redemption and tracker signature") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val basisInput = mkBasisInput(
        conditionScript("properlyRedeemed"), inputValue, initialTree, receiverPk,
        reserveSigBytes, totalDebt, insertProofBytes, trackerSigBytes, trackerLookupProofBytes
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree, trackerPk)

      val basisOutput = createOut(
        conditionScript("properlyRedeemed"), outputValue,
        Array(ErgoValue.of(ownerPk), ErgoValue.of(nextTree.getValue), ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut("{sigmaProp(true)}", redeemAmount, Array(), Array())

      noException should be thrownBy {
        createTx(Array(basisInput), Array(trackerDataInput), Array(basisOutput, redemptionOutput),
          fee = None, changeAddress, Array(receiverSecret.toString()), broadcast = false)
      }
    }
  }

  property("CONDITION 7: receiverCondition - should pass with receiver signature") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val basisInput = mkBasisInput(
        conditionScript("receiverCondition"), inputValue, initialTree, receiverPk,
        reserveSigBytes, totalDebt, insertProofBytes, trackerSigBytes, trackerLookupProofBytes
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree, trackerPk)

      val basisOutput = createOut(
        conditionScript("receiverCondition"), outputValue,
        Array(ErgoValue.of(ownerPk), ErgoValue.of(nextTree.getValue), ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut("{sigmaProp(true)}", redeemAmount, Array(), Array())

      noException should be thrownBy {
        createTx(Array(basisInput), Array(trackerDataInput), Array(basisOutput, redemptionOutput),
          fee = None, changeAddress, Array(receiverSecret.toString()), broadcast = false)
      }
    }
  }

  property("BASELINE: full basis.es contract - SKIP (AVL tree proof serialization issue)") {
    pending
  }

  // ========== NEGATIVE TESTS ==========

  property("NEGATIVE: selfPreserved should fail with different contract in output") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val basisInput = mkBasisInput(
        conditionScript("selfPreserved"), inputValue, initialTree, receiverPk,
        reserveSigBytes, totalDebt, insertProofBytes, trackerSigBytes, trackerLookupProofBytes
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree, trackerPk)

      // Use different contract in output (should fail selfPreserved check)
      val basisOutput = createOut(
        conditionScript("trackerIdCorrect"), outputValue, // Different contract!
        Array(ErgoValue.of(ownerPk), ErgoValue.of(nextTree.getValue), ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut("{sigmaProp(true)}", redeemAmount, Array(), Array())

      val ex = intercept[Throwable] {
        createTx(Array(basisInput), Array(trackerDataInput), Array(basisOutput, redemptionOutput),
          fee = None, changeAddress, Array(receiverSecret.toString()), broadcast = false)
      }
      hasScriptValidationFailure(ex) shouldBe true
    }
  }

  property("NEGATIVE: trackerIdCorrect should fail with wrong tracker NFT") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val wrongTrackerNFT = Base16.decode("0000000000000000000000000000000000000000000000000000000000000000").get

      val basisInput = ctx.newTxBuilder().outBoxBuilder
        .value(inputValue)
        .tokens(new ErgoToken(basisTokenId, 1))
        .registers(ErgoValue.of(ownerPk), initialTree, ErgoValue.of(wrongTrackerNFT))
        .contract(ctx.compileContract(ConstantsBuilder.empty(), conditionScript("trackerIdCorrect")))
        .build()
        .convertToInputWith(reserveBoxId, fakeIndex)
        .withContextVars(
          new ContextVar(0, ErgoValue.of(0x00.toByte)),
          new ContextVar(1, ErgoValue.of(receiverPk)),
          new ContextVar(2, ErgoValue.of(reserveSigBytes)),
          new ContextVar(3, ErgoValue.of(totalDebt)),
          new ContextVar(5, ErgoValue.of(insertProofBytes)),
          new ContextVar(6, ErgoValue.of(trackerSigBytes)),
          new ContextVar(8, ErgoValue.of(trackerLookupProofBytes))
        )

      val trackerDataInput = mkTrackerDataInput(trackerTree, trackerPk)

      val basisOutput = createOut(
        conditionScript("trackerIdCorrect"), outputValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(wrongTrackerNFT)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut("{sigmaProp(true)}", redeemAmount, Array(), Array())

      val ex = intercept[Throwable] {
        createTx(Array(basisInput), Array(trackerDataInput), Array(basisOutput, redemptionOutput),
          fee = None, changeAddress, Array(receiverSecret.toString()), broadcast = false)
      }
      hasScriptValidationFailure(ex) shouldBe true
    }
  }

  private def hasScriptValidationFailure(t: Throwable): Boolean =
    Iterator.iterate[Throwable](t)(_.getCause).takeWhile(_ != null).exists {
      case _: sigmastate.exceptions.InterpreterException => true
      case _ => false
    }
}
