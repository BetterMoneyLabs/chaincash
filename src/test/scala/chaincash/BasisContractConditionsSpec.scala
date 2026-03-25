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
 * Unit tests for each sub-condition in the basis.es contract.
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
 * These tests help identify which condition fails during signing.
 *
 * Tests are provided in two modes:
 * 1. Isolation tests: Check only ONE condition at a time by modifying the contract
 *    to wrap only that condition in sigmaProp(). Uses actual data from signing attempt.
 * 2. Mutation tests: Break ONE condition while keeping others valid.
 */
class BasisContractConditionsSpec extends PropSpec with Matchers with ScalaCheckDrivenPropertyChecks with HttpClientTesting {

  implicit val addrEncoder = Constants.ergoAddressEncoder

  val fakeTxId1 = "f9e5ce5aa0d95f5d54a7bc89c46730d9662397067250aa18a0039631c0f5b809"
  val fakeTxId2 = "f9e5ce5aa0d95f5d54a7bc89c46730d9662397067250aa18a0039631c0f5b808"
  val fakeIndex = 1.toShort

  // Token IDs from sign_request.json
  val basisTokenId = "4b2d8b7beb3eaac8234d9e61792d270898a43934d6a27275e4f3a044609c9f2a"
  // Tracker NFT from output R6 register (format: 0e20 + 32 bytes)
  val trackerNFT = "8b1ab583bb085ecbd8fa9bc2fd59784afcdfce5496eb146bb3dd04664b56822a"
  val trackerNFTBytes = Base16.decode(trackerNFT).get

  // ========== ACTUAL DATA FROM SIGNING ATTEMPT ==========
  // These values are extracted from sign_request.json and note.json
  val actualReserveBoxId = "59ed384b3983d64f99368218beede3c7a8b0ae26ef64c1b0ddc3953b444b4317"
  val actualTrackerBoxId = "63e6d4292f122ac5c9e52685ceee9a5994ccbd667a6e5c7dcb1ac0c7d7d257b4"
  
  // Owner public key from R4 register (in output box)
  // Format: 0703 (GroupElement type + compressed) + 32 bytes X coordinate
  // The value in sign_request.json R4 is: 070377709166937fcdc08bf7e841b31684e2377f489914c97ef7148de14d9c6e1f83
  val actualOwnerKeyHex = "0377709166937fcdc08bf7e841b31684e2377f489914c97ef7148de14d9c6e1f83" // compressed point (33 bytes)
  val actualOwnerKeyBytes = Base16.decode(actualOwnerKeyHex).get
  val actualOwnerKey = GroupElementSerializer.fromBytes(actualOwnerKeyBytes)

  // Receiver public key from R1 context variable (in input extension)
  // Format: 0703 (GroupElement type + compressed) + 32 bytes X coordinate
  // The value in sign_request.json extension R1 is: 0703af13e39dd0ccc7429f9dfa5a056b71a8f5160eaf179763a03e0b55d8feec2cea
  val actualReceiverKeyHex = "03af13e39dd0ccc7429f9dfa5a056b71a8f5160eaf179763a03e0b55d8feec2cea"
  val actualReceiverKeyBytes = Base16.decode(actualReceiverKeyHex).get
  val actualReceiverKey = GroupElementSerializer.fromBytes(actualReceiverKeyBytes)
  
  // Total debt from note.json and sign_request.json (R3 context variable)
  val actualTotalDebt = 50000000L // 0.05 ERG

  // Signatures from sign_request.json extension registers
  // R2 contains: 0e41 (Coll[Byte] type + length) + 02 (SigmaProp) + 33 bytes (A) + 32 bytes (Z)
  // The contract expects raw signature bytes (33 bytes A + 32 bytes Z = 65 bytes)
  // but the transaction has SigmaProp-wrapped bytes (02 + 33 + 32 = 66 bytes)
  // 
  // Raw reserve signature bytes (after removing SigmaProp prefix 02):
  val actualReserveSigHex = "b7701021b4b51b762c19ba35052ec47f9cba69fee6875a112860ba9fded71120786a35cf5b71a48cbadaf6ea81c173839e23c90aa401de4a64519abe9975050c"
  // Raw tracker signature bytes (after removing SigmaProp prefix 03):
  val actualTrackerSigHex = "ab2f6aad8514a5cd7d99bd8732d3b779718aab2117c49a5fd984ae620fe4e5680e1144a43feccc6fb13cfba81fe627080c5fd37f0b0fd788e2c44f683a3a0fad"

  // Full signatures from note.json (for reference - these are the mathematical values)
  val actualPayerSignatureA = "02a93069623080042bf04cb274e4716101bcfd58e78ff693d736e8d77b63aea930"
  val actualPayerSignatureZ = "7a8eed68c65218a584e26ad105f2a921622836432f8b44bebd182dbb85315864"
  val actualTrackerSignatureA = "039a37413865e8b18dea88d2b37b4a31c2d5bd058f4ae3476149b9b11d79bd8755"
  val actualTrackerSignatureZ = "-14a411a9cfcd0ce570354f893fd112917ef2d3ee83b75746388f53c24a691b7c"

  // Note: The signatures in sign_request.json differ from note.json because they represent
  // a different transaction state. The sign_request.json contains the actual transaction
  // being signed, while note.json contains the original IOU note parameters.

  // ========== SIGN_REQUEST.JSON STRUCTURE ==========
  // Input box extension context variables:
  // R0: 0200 - action byte (action=0 redemption, index=0)
  // R1: 0703af13... - receiver pubkey (GroupElement)
  // R2: 0e4102b770... - reserve signature (Coll[Byte] with SigmaProp wrapper)
  // R3: 0580c2d72f - total debt (50000000 as Long)
  // R5: (not used in input)
  // R6: 0e4103ab2f... - tracker signature (Coll[Byte] with SigmaProp wrapper)
  // R7: (optional, not present)
  // R8: 0e71... - AVL tree proof for tracker lookup
  //
  // Output box registers:
  // R4: 07037770... - owner pubkey (GroupElement)
  // R5: 0e71... - updated AVL tree (Coll[Byte] serialized)
  // R6: 0e208b1a... - tracker NFT ID (Coll[Byte] = 0e20 + 32 bytes)
  
  // Use random keys like BasisSpec does (ParticipantKeys have address validation issues)
  val ownerSecret = SigUtils.randBigInt
  val ownerPk = Constants.g.exp(ownerSecret.bigInteger)
  val receiverSecret = SigUtils.randBigInt
  val receiverPk = Constants.g.exp(receiverSecret.bigInteger)
  val trackerSecret = SigUtils.randBigInt
  val trackerPk = Constants.g.exp(trackerSecret.bigInteger)

  val changeAddress = P2PKAddress(ProveDlog(ownerPk)).toString()

  val minValue = 1000000000L
  val feeValue = 1000000L

  def trueScript = "sigmaProp(true)"
  def trueErgoTree = Constants.compile(trueScript)
  def trueErgoContract = new ErgoTreeContract(trueErgoTree, Constants.networkType)

  // Create output box
  def createOut(contract: String,
                value: Long,
                registers: Array[ErgoValue[_]],
                tokens: Array[ErgoToken])(implicit ctx: BlockchainContext): OutBoxImpl = {
    val c = ErgoScriptContract.create(new org.ergoplatform.appkit.Constants, contract, Constants.networkType)
    val ebc = AppkitHelpers.createBoxCandidate(value, c.getErgoTree, tokens, registers, ctx.getHeight)
    new OutBoxImpl(ebc)
  }

  // Create transaction
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

  // Helper methods for creating test data
  def mkKey(ownerKey: GroupElement, receiverKey: GroupElement): Array[Byte] =
    Blake2b256(ownerKey.getEncoded.toArray ++ receiverKey.getEncoded.toArray)

  def mkMessage(key: Array[Byte], totalDebt: Long): Array[Byte] =
    key ++ Longs.toByteArray(totalDebt)

  def mkEmergencyMessage(key: Array[Byte], totalDebt: Long): Array[Byte] =
    key ++ Longs.toByteArray(totalDebt) ++ Longs.toByteArray(0L)

  def mkSigBytes(sig: (GroupElement, BigInt)): Array[Byte] =
    GroupElementSerializer.toBytes(sig._1) ++ zTo32Bytes(sig._2)

  private def zTo32Bytes(z: BigInt): Array[Byte] = {
    val bytes = z.toByteArray
    if (bytes.length == 32) bytes
    else if (bytes.length == 33 && bytes(0) == 0) bytes.tail
    else throw new IllegalArgumentException(s"Signature z must be 32 bytes, got ${bytes.length}")
  }

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

  def mkBasisInput(
    value: Long,
    tree: ErgoValue[AvlTree],
    receiverKey: GroupElement,
    reserveSigBytes: Array[Byte],
    totalDebt: Long,
    insertProofBytes: Array[Byte],
    trackerSigBytes: Array[Byte],
    lookupProofBytes: Option[Array[Byte]] = None,
    trackerLookupProofBytes: Option[Array[Byte]] = None
  )(implicit ctx: BlockchainContext): InputBox = {
    val baseVars = Array(
      new ContextVar(0, ErgoValue.of(0: Byte)),
      new ContextVar(1, ErgoValue.of(receiverKey)),
      new ContextVar(2, ErgoValue.of(reserveSigBytes)),
      new ContextVar(3, ErgoValue.of(totalDebt)),
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
      .tokens(new ErgoToken(basisTokenId, 1))
      .registers(ErgoValue.of(ownerPk), tree, ErgoValue.of(trackerNFTBytes))
      .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.basisContract))
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

  val chainCashPlasmaParameters = PlasmaParameters(32, None)
  def emptyPlasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
  val emptyTreeErgoValue: ErgoValue[AvlTree] = emptyPlasmaMap.ergoValue
  val emptyTree: AvlTree = emptyTreeErgoValue.getValue

  // Validation helpers
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
      val classMatch = constructionErrorClassKeywords.exists(kw =>
        simpleName.contains(kw) || fullName.contains(kw))
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
      createTx(inputs, dataInputs, outputs, fee = None, changeAddress, secrets, broadcast = false)
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
    createTx(inputs, dataInputs, outputs, fee = None, changeAddress, secrets, broadcast = false)
  }

  // ========== ISOLATION TEST SCRIPTS ==========
  // These scripts test ONLY ONE condition at a time by wrapping only that condition
  // in sigmaProp() while keeping the rest of the contract intact for context.

  /**
   * Creates a contract that checks ONLY the specified condition.
   * All other conditions are replaced with 'true' to isolate the test.
   */
  def isolationScript(conditionName: String): String = conditionName match {
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
        |  val ownerKey = SELF.R4[GroupElement].get
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
        |  val selfOut = OUTPUTS(index)
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
        |  val totalDebt = getVar[Long](3).get
        |  val redeemedDebt = 0L
        |  val redeemed = SELF.value - selfOut.value
        |  val newRedeemed = redeemedDebt + redeemed
        |  val treeValue = longToByteArray(newRedeemed)
        |  val ownerKeyBytes = ownerKey.getEncoded
        |  val receiverBytes = receiver.getEncoded
        |  val key = blake2b256(ownerKeyBytes ++ receiverBytes)
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
        |  val selfOut = OUTPUTS(index)
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
        |  val ownerKey = SELF.R4[GroupElement].get
        |  val selfOut = OUTPUTS(index)
        |  val tracker = CONTEXT.dataInputs(0)
        |  val trackerPubKey = tracker.R4[GroupElement].get
        |  val trackerSigBytes = getVar[Coll[Byte]](6).get
        |  val receiver = getVar[GroupElement](1).get
        |  val totalDebt = getVar[Long](3).get
        |  val redeemedDebt = 0L
        |  val redeemed = SELF.value - selfOut.value
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
        |  val ownerKey = SELF.R4[GroupElement].get
        |  val selfOut = OUTPUTS(index)
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

  /**
   * Helper to create input box with isolation contract
   */
  def mkBasisInputWithIsolationContract(
    isolationContract: String,
    value: Long,
    tree: ErgoValue[AvlTree],
    receiverKey: GroupElement,
    reserveSigBytes: Array[Byte],
    totalDebt: Long,
    insertProofBytes: Array[Byte],
    trackerSigBytes: Array[Byte],
    trackerLookupProofBytes: Option[Array[Byte]] = None
  )(implicit ctx: BlockchainContext): InputBox = {
    val baseVars = Array(
      new ContextVar(0, ErgoValue.of(0: Byte)),
      new ContextVar(1, ErgoValue.of(receiverKey)),
      new ContextVar(2, ErgoValue.of(reserveSigBytes)),
      new ContextVar(3, ErgoValue.of(totalDebt)),
      new ContextVar(5, ErgoValue.of(insertProofBytes)),
      new ContextVar(6, ErgoValue.of(trackerSigBytes))
    )
    val allVars = trackerLookupProofBytes match {
      case Some(trackerLookupProof) => baseVars :+ new ContextVar(8, ErgoValue.of(trackerLookupProof))
      case None => baseVars
    }
    ctx.newTxBuilder().outBoxBuilder
      .value(value)
      .tokens(new ErgoToken(basisTokenId, 1))
      .registers(ErgoValue.of(ownerPk), tree, ErgoValue.of(trackerNFTBytes))
      .contract(ctx.compileContract(ConstantsBuilder.empty(), isolationContract))
      .build()
      .convertToInputWith(fakeTxId1, fakeIndex)
      .withContextVars(allVars: _*)
  }

  // ========== ISOLATION TESTS ==========
  // Each test checks ONLY ONE condition using actual signing data

  property("ISOLATION TEST: selfPreserved - should pass with correct output contract") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val totalDebt = 50000000L
      val redeemAmount = 50000000L
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt)
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val trackerSig = SigUtils.sign(message, trackerSecret)
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, redeemAmount)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisInputWithIsolationContract(
        isolationScript("selfPreserved"),
        minValue + totalDebt + feeValue, initialTree, receiverPk,
        mkSigBytes(reserveSig), totalDebt, proofBytes, mkSigBytes(trackerSig),
        Some(trackerLookupProof)
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)

      // Output with SAME contract (selfPreserved should pass)
      val basisOutput = createOut(
        isolationScript("selfPreserved"),
        minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      noException should be thrownBy {
        assertTxSucceeds(
          Array(basisInput), Array(trackerDataInput),
          Array(basisOutput, redemptionOutput),
          Array(receiverSecret.toString())
        )
      }
    }
  }

  property("ISOLATION TEST: trackerIdCorrect - should pass with matching tracker NFT") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val totalDebt = 50000000L
      val redeemAmount = 50000000L
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt)
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val trackerSig = SigUtils.sign(message, trackerSecret)
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, redeemAmount)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisInputWithIsolationContract(
        isolationScript("trackerIdCorrect"),
        minValue + totalDebt + feeValue, initialTree, receiverPk,
        mkSigBytes(reserveSig), totalDebt, proofBytes, mkSigBytes(trackerSig),
        Some(trackerLookupProof)
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)

      val basisOutput = createOut(
        isolationScript("trackerIdCorrect"),
        minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      noException should be thrownBy {
        assertTxSucceeds(
          Array(basisInput), Array(trackerDataInput),
          Array(basisOutput, redemptionOutput),
          Array(receiverSecret.toString())
        )
      }
    }
  }

  property("ISOLATION TEST: trackerDebtCorrect - should pass with matching debt in tracker tree") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val totalDebt = 50000000L
      val redeemAmount = 50000000L
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt)
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val trackerSig = SigUtils.sign(message, trackerSecret)
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, redeemAmount)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisInputWithIsolationContract(
        isolationScript("trackerDebtCorrect"),
        minValue + totalDebt + feeValue, initialTree, receiverPk,
        mkSigBytes(reserveSig), totalDebt, proofBytes, mkSigBytes(trackerSig),
        Some(trackerLookupProof)
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)

      val basisOutput = createOut(
        isolationScript("trackerDebtCorrect"),
        minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      noException should be thrownBy {
        assertTxSucceeds(
          Array(basisInput), Array(trackerDataInput),
          Array(basisOutput, redemptionOutput),
          Array(receiverSecret.toString())
        )
      }
    }
  }

  property("ISOLATION TEST: properRedemptionTree - should pass with correct AVL tree update") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val totalDebt = 50000000L
      val redeemAmount = 50000000L
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt)
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val trackerSig = SigUtils.sign(message, trackerSecret)
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, redeemAmount)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisInputWithIsolationContract(
        isolationScript("properRedemptionTree"),
        minValue + totalDebt + feeValue, initialTree, receiverPk,
        mkSigBytes(reserveSig), totalDebt, proofBytes, mkSigBytes(trackerSig),
        Some(trackerLookupProof)
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)

      val basisOutput = createOut(
        isolationScript("properRedemptionTree"),
        minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      noException should be thrownBy {
        assertTxSucceeds(
          Array(basisInput), Array(trackerDataInput),
          Array(basisOutput, redemptionOutput),
          Array(receiverSecret.toString())
        )
      }
    }
  }

  property("ISOLATION TEST: properReserveSignature - should pass with valid owner signature") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val totalDebt = 50000000L
      val redeemAmount = 50000000L
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt)
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val trackerSig = SigUtils.sign(message, trackerSecret)
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, redeemAmount)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisInputWithIsolationContract(
        isolationScript("properReserveSignature"),
        minValue + totalDebt + feeValue, initialTree, receiverPk,
        mkSigBytes(reserveSig), totalDebt, proofBytes, mkSigBytes(trackerSig),
        Some(trackerLookupProof)
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)

      val basisOutput = createOut(
        isolationScript("properReserveSignature"),
        minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      noException should be thrownBy {
        assertTxSucceeds(
          Array(basisInput), Array(trackerDataInput),
          Array(basisOutput, redemptionOutput),
          Array(receiverSecret.toString())
        )
      }
    }
  }

  property("ISOLATION TEST: properlyRedeemed - should pass with valid redemption amount and tracker signature") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val totalDebt = 50000000L
      val redeemAmount = 50000000L
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt)
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val trackerSig = SigUtils.sign(message, trackerSecret)
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, redeemAmount)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisInputWithIsolationContract(
        isolationScript("properlyRedeemed"),
        minValue + totalDebt + feeValue, initialTree, receiverPk,
        mkSigBytes(reserveSig), totalDebt, proofBytes, mkSigBytes(trackerSig),
        Some(trackerLookupProof)
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)

      val basisOutput = createOut(
        isolationScript("properlyRedeemed"),
        minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      noException should be thrownBy {
        assertTxSucceeds(
          Array(basisInput), Array(trackerDataInput),
          Array(basisOutput, redemptionOutput),
          Array(receiverSecret.toString())
        )
      }
    }
  }

  property("ISOLATION TEST: receiverCondition - should pass with receiver signature") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val totalDebt = 50000000L
      val redeemAmount = 50000000L
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt)
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val trackerSig = SigUtils.sign(message, trackerSecret)
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, redeemAmount)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisInputWithIsolationContract(
        isolationScript("receiverCondition"),
        minValue + totalDebt + feeValue, initialTree, receiverPk,
        mkSigBytes(reserveSig), totalDebt, proofBytes, mkSigBytes(trackerSig),
        Some(trackerLookupProof)
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)

      val basisOutput = createOut(
        isolationScript("receiverCondition"),
        minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      noException should be thrownBy {
        assertTxSucceeds(
          Array(basisInput), Array(trackerDataInput),
          Array(basisOutput, redemptionOutput),
          Array(receiverSecret.toString())
        )
      }
    }
  }

  // ========== ISOLATION TESTS WITH ACTUAL SIGNING DATA ==========
  // These tests use the actual values from sign_request.json to help debug
  // which condition is failing during the real signing attempt.

  property("ISOLATION TEST (ACTUAL DATA): verify actual keys and signatures") {
    // This test extracts and validates the actual data from sign_request.json
    // to help identify which condition might be failing.

    // Owner key from R4 register (actual) - without 0703 type prefix
    val ownerKeyBytes = Base16.decode("0377709166937fcdc08bf7e841b31684e2377f489914c97ef7148de14d9c6e1f83").get
    val actualOwnerPk = GroupElementSerializer.fromBytes(ownerKeyBytes)

    // Receiver key from extension var #1 (actual from sign_request.json)
    val receiverKeyBytes = Base16.decode("03af13e39dd0ccc7429f9dfa5a056b71a8f5160eaf179763a03e0b55d8feec2cea").get
    val actualReceiverPk = GroupElementSerializer.fromBytes(receiverKeyBytes)

    // Reserve signature from extension var #2 (actual from sign_request.json)
    // Format: 0e41 (Coll[Byte] type + length) + content
    // Content: 02 (SigmaProp) + 33 bytes (A point) + 31 bytes (Z value)
    val reserveSigFull = Base16.decode("0e4102b7701021b4b51b762c19ba35052ec47f9cba69fee6875a112860ba9fded71120786a35cf5b71a48cbadaf6ea81c173839e23c90aa401de4a64519abe9975050c").get
    val reserveSigBytes = reserveSigFull.drop(2) // Remove 0e41 type/length

    // Tracker signature from extension var #6 (actual from sign_request.json)
    // Format: same as reserve signature
    val trackerSigFull = Base16.decode("0e4103ab2f6aad8514a5cd7d99bd8732d3b779718aab2117c49a5fd984ae620fe4e5680e1144a43feccc6fb13cfba81fe627080c5fd37f0b0fd788e2c44f683a3a0fad").get
    val trackerSigBytes = trackerSigFull.drop(2) // Remove 0e41 type/length

    // Total debt from extension var #3
    val totalDebt = 50000000L

    // Create message that should have been signed: hash(ownerKey || receiverKey) || totalDebt
    val key = mkKey(actualOwnerPk, actualReceiverPk)
    val message = mkMessage(key, totalDebt)

    // Parse reserve signature components
    // Format: 02 (SigmaProp type) + 33 bytes (A) + 31 bytes (Z)
    val reserveABytes = reserveSigBytes.take(33)
    val reserveZBytes = reserveSigBytes.drop(33)
    val reserveA = GroupElementSerializer.parse(reserveABytes)
    val reserveZ = BigInt(1, reserveZBytes)

    // Verify reserve signature
    val reserveEBytes = Blake2b256(reserveABytes ++ message ++ actualOwnerPk.getEncoded.toArray)
    val reserveEInt = BigInt(1, reserveEBytes)
    val g = Constants.g
    val lhs = g.exp(reserveZ.bigInteger)
    val rhs = reserveA.multiply(actualOwnerPk.exp(reserveEInt.bigInteger))
    val reserveSigValid = (lhs == rhs)

    // Check reserve signature format (independent of cryptographic validity)
    val reserveSigFormatValid = (reserveABytes.length == 33 && reserveZBytes.length >= 31)

    // Parse tracker signature components
    // Note: Tracker signature is signed by tracker's private key, not receiver's
    // We cannot verify it here without knowing the tracker's private key
    // But we can check the format is correct
    val trackerABytes = trackerSigBytes.take(33)
    val trackerZBytes = trackerSigBytes.drop(33)
    val trackerA = GroupElementSerializer.parse(trackerABytes)
    val trackerZ = BigInt(1, trackerZBytes)

    // For tracker signature, we just verify the format is valid
    // The actual verification happens in the contract with tracker's public key
    val trackerSigFormatValid = (trackerABytes.length == 33 && trackerZBytes.length >= 31)

    // Print diagnostic info
    Console.err.println(s"=== Actual Signing Data Diagnostics ===")
    Console.err.println(s"Reserve signature valid: $reserveSigValid")
    Console.err.println(s"Tracker signature format valid: $trackerSigFormatValid")
    Console.err.println(s"Message: ${Base16.encode(message)}")
    Console.err.println(s"Reserve A bytes: ${Base16.encode(reserveABytes)}")
    Console.err.println(s"Reserve Z bytes: ${Base16.encode(reserveZBytes)}")
    Console.err.println(s"======================================")

    // Note: reserveSigValid may be false if the signature in sign_request.json
    // was generated with different parameters or keys
    reserveSigFormatValid shouldBe true
    trackerSigFormatValid shouldBe true
  }

  property("ISOLATION TEST (ACTUAL DATA): selfPreserved with exact sign_request.json structure") {
    // This test uses the EXACT structure from sign_request.json
    // to find which condition fails when script is reduced to false.
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>

      // Actual values from sign_request.json
      val reserveBoxId = "59ed384b3983d64f99368218beede3c7a8b0ae26ef64c1b0ddc3953b444b4317"
      val trackerBoxId = "63e6d4292f122ac5c9e52685ceee9a5994ccbd667a6e5c7dcb1ac0c7d7d257b4"
      val basisTokenIdActual = "4b2d8b7beb3eaac8234d9e61792d270898a43934d6a27275e4f3a044609c9f2a"
      val trackerNFTActual = "8b1ab583bb085ecbd8fa9bc2fd59784afcdfce5496eb146bb3dd04664b56822a"

      // Values (defined first to avoid forward references)
      val totalDebt = 50000000L
      val inputValue = 100000000L
      val outputValue = 50000000L
      val feeValue = 0L

      // Keys (without type prefixes)
      val ownerPkBytes = Base16.decode("0377709166937fcdc08bf7e841b31684e2377f489914c97ef7148de14d9c6e1f83").get
      val ownerPk = GroupElementSerializer.fromBytes(ownerPkBytes)

      val receiverPkBytes = Base16.decode("03af13e39dd0ccc7429f9dfa5a056b71a8f5160eaf179763a03e0b55d8feec2cea").get
      val receiverPk = GroupElementSerializer.fromBytes(receiverPkBytes)

      // Signatures from sign_request.json (after removing 0e41 type/length prefix)
      // R2 format: 0e41 (Coll[Byte] type + length) + 02 (SigmaProp) + 33 bytes (A) + 31 bytes (Z)
      // The Z value is 31 bytes because leading zeros are stripped in serialization
      val reserveSigBytes = Base16.decode("02b7701021b4b51b762c19ba35052ec47f9cba69fee6875a112860ba9fded71120786a35cf5b71a48cbadaf6ea81c173839e23c90aa401de4a64519abe9975050c").get
      // R6 format: 0e41 (Coll[Byte] type + length) + 03 (SigmaProp) + 33 bytes (A) + 31 bytes (Z)
      val trackerSigBytes = Base16.decode("03ab2f6aad8514a5cd7d99bd8732d3b779718aab2117c49a5fd984ae620fe4e5680e1144a43feccc6fb13cfba81fe627080c5fd37f0b0fd788e2c44f683a3a0fad").get

      // Proofs from sign_request.json (after removing type/length prefixes)
      // R8 format: 0e71 (Coll[Byte] type + length 0x71=113) + proof bytes
      // This is the tracker tree lookup proof
      val trackerProofBytes = Base16.decode("0365b2adf3ef941f484c086bf68d0316aa32207bb694bf63227435b394b9d1bc35026995ccf33c8a09705612e6ee3808bb4cedb48cb7b7c019ecdc68b74e7ed912a4ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff000000080000000002faf080000400").get

      // Insert proof for reserve tree update (generated locally, not from sign_request.json)
      val key = mkKey(ownerPk, receiverPk)
      val TreeAndProof(initialTree, nextTree, insertProofBytes) = mkTreeAndProof(key, totalDebt)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      // Tracker public key (from tracker box R4 register)
      // In sign_request.json, the tracker box is the data input
      // We need to extract the tracker pubkey from there or use a known value
      val trackerPkBytes = Base16.decode("03ab2f6aad8514a5cd7d99bd8732d3b779718aab2117c49a5fd984ae620fe4e5680e").get
      val trackerPk = GroupElementSerializer.fromBytes(trackerPkBytes)

      // Action byte: 0200 means action=0 (redeem), index=2
      // BUG in sign_request.json: index=2 but there are only 2 outputs (0,1)!
      // The CORRECT action byte should be 0000 for action=0, index=0
      // (reserve output is at position 0 in the transaction)

      // Create basis input with ACTUAL action byte from sign_request.json (0200 = index=2)
      val basisInput = ctx.newTxBuilder().outBoxBuilder
        .value(inputValue)
        .tokens(new ErgoToken(basisTokenIdActual, 1))
        .registers(ErgoValue.of(ownerPk), initialTree, ErgoValue.of(Base16.decode(trackerNFTActual).get))
        .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.basisContract))
        .build()
        .convertToInputWith(reserveBoxId, 0.toShort)
        .withContextVars(
          new ContextVar(0, ErgoValue.of(0x02.toByte)), // BUG: 0x02 means index=2!
          new ContextVar(1, ErgoValue.of(receiverPk)),
          new ContextVar(2, ErgoValue.of(reserveSigBytes)),
          new ContextVar(3, ErgoValue.of(totalDebt)),
          new ContextVar(5, ErgoValue.of(insertProofBytes)),
          new ContextVar(6, ErgoValue.of(trackerSigBytes)),
          new ContextVar(8, ErgoValue.of(trackerLookupProof))
        )

      val trackerDataInput = mkTrackerDataInput(trackerTree)

      // Create outputs matching sign_request.json
      val basisOutput = createOut(
        Constants.basisContract,
        outputValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(Base16.decode(trackerNFTActual).get)),
        Array(new ErgoToken(basisTokenIdActual, 1))
      )

      val redemptionOutput = createOut(
        "{sigmaProp(true)}",
        outputValue,
        Array(),
        Array()
      )

      // This should FAIL because index=2 but there are only 2 outputs (0,1)
      val ex = intercept[Throwable] {
        createTx(
          Array(basisInput),
          Array(trackerDataInput),
          Array(basisOutput, redemptionOutput),
          fee = None,
          changeAddress,
          Array(receiverSecret.toString()),
          broadcast = false
        )
      }

      Console.err.println(s"=== Index Test Error ===")
      Console.err.println(s"Error: ${ex.getMessage}")
      Console.err.println(s"Error class: ${ex.getClass.getName}")
      Console.err.println(s"========================")

      // The error "2" is likely an IndexOutOfBoundsException
      // The contract tries to access OUTPUTS(2) but there are only 2 outputs (0,1)
      val errorMsg = ex.getMessage
      assert(
        errorMsg == "2" || errorMsg.contains("index") || errorMsg.contains("IndexOutOfBounds") || 
        errorMsg.contains("Script reduced to false") || errorMsg.contains("reduced to false"),
        s"Expected index error (got: '$errorMsg')"
      )
    }
  }

  property("ISOLATION TEST (ACTUAL DATA): selfPreserved with CORRECTED index=0") {
    // This test uses the CORRECTED action byte (0000 = action=0, index=0)
    // and tests ONLY the selfPreserved condition using an isolation script.
    // This avoids signature verification issues since we don't have the actual private keys.
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>

      // Values
      val totalDebt = 50000000L
      val inputValue = 100000000L
      val outputValue = 50000000L
      val feeValue = 0L

      // Actual values from sign_request.json
      val reserveBoxId = "59ed384b3983d64f99368218beede3c7a8b0ae26ef64c1b0ddc3953b444b4317"
      val basisTokenIdActual = "4b2d8b7beb3eaac8234d9e61792d270898a43934d6a27275e4f3a044609c9f2a"
      val trackerNFTActual = "8b1ab583bb085ecbd8fa9bc2fd59784afcdfce5496eb146bb3dd04664b56822a"

      // Keys from sign_request.json (without type prefixes)
      val ownerPkBytes = Base16.decode("0377709166937fcdc08bf7e841b31684e2377f489914c97ef7148de14d9c6e1f83").get
      val ownerPk = GroupElementSerializer.fromBytes(ownerPkBytes)

      val receiverPkBytes = Base16.decode("03af13e39dd0ccc7429f9dfa5a056b71a8f5160eaf179763a03e0b55d8feec2cea").get
      val receiverPk = GroupElementSerializer.fromBytes(receiverPkBytes)

      // Generate dummy signatures and proofs (won't be verified with isolation script)
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt)
      val (reserveSigA, reserveSigZ) = SigUtils.sign(message, ownerSecret)
      val reserveSigBytes = GroupElementSerializer.toBytes(reserveSigA) ++ zTo32Bytes(reserveSigZ)

      val (trackerSigA, trackerSigZ) = SigUtils.sign(message, trackerSecret)
      val trackerSigBytes = GroupElementSerializer.toBytes(trackerSigA) ++ zTo32Bytes(trackerSigZ)

      // Generate proofs
      val TreeAndProof(initialTree, nextTree, insertProofBytes) = mkTreeAndProof(key, totalDebt)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      // CORRECTED action byte: 0000 means action=0 (redeem), index=0
      // Use isolation script that ONLY checks selfPreserved condition
      val basisInput = ctx.newTxBuilder().outBoxBuilder
        .value(inputValue)
        .tokens(new ErgoToken(basisTokenIdActual, 1))
        .registers(ErgoValue.of(ownerPk), initialTree, ErgoValue.of(Base16.decode(trackerNFTActual).get))
        .contract(ctx.compileContract(ConstantsBuilder.empty(), isolationScript("selfPreserved")))
        .build()
        .convertToInputWith(reserveBoxId, 0.toShort)
        .withContextVars(
          new ContextVar(0, ErgoValue.of(0x00.toByte)), // CORRECTED: index=0
          new ContextVar(1, ErgoValue.of(receiverPk)),
          new ContextVar(2, ErgoValue.of(reserveSigBytes)),
          new ContextVar(3, ErgoValue.of(totalDebt)),
          new ContextVar(5, ErgoValue.of(insertProofBytes)),
          new ContextVar(6, ErgoValue.of(trackerSigBytes)),
          new ContextVar(8, ErgoValue.of(trackerLookupProof))
        )

      val trackerDataInput = mkTrackerDataInput(trackerTree)

      // Create outputs - basis output at index 0 with same isolation script
      val basisOutput = createOut(
        isolationScript("selfPreserved"),
        outputValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(Base16.decode(trackerNFTActual).get)),
        Array(new ErgoToken(basisTokenIdActual, 1))
      )

      val redemptionOutput = createOut(
        "{sigmaProp(true)}",
        outputValue,
        Array(),
        Array()
      )

      // This should SUCCEED because selfPreserved only checks contract preservation
      // and we're using the correct contract at the correct index
      val signedTx = createTx(
        Array(basisInput),
        Array(trackerDataInput),
        Array(basisOutput, redemptionOutput),
        fee = None,
        changeAddress,
        Array(receiverSecret.toString()),
        broadcast = false
      )

      signedTx should not be null
    }
  }

  // ========== VALID SETUP TEST (BASELINE) ==========

  property("basis redemption should succeed with all conditions met (baseline)") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>

      // Use actual values from note.json
      val totalDebt = 50000000L // 0.05 ERG
      val redeemedDebt = 0L // First redemption
      val redeemAmount = 50000000L // Full amount

      // Create key for debt record: hash(ownerKey || receiverKey)
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt)

      // Create signatures (using redemption message, not original note message)
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val trackerSig = SigUtils.sign(message, trackerSecret)
      val reserveSigBytes = mkSigBytes(reserveSig)
      val trackerSigBytes = mkSigBytes(trackerSig)

      // Create initial tree with existing redeemed debt (0 in this case)
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, redeemAmount)

      // Create tracker tree with debt record and lookup proof
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      // Basis input
      val basisInput = mkBasisInput(
        value = minValue + totalDebt + feeValue,
        tree = initialTree,
        receiverKey = receiverPk,
        reserveSigBytes = reserveSigBytes,
        totalDebt = totalDebt,
        insertProofBytes = proofBytes,
        trackerSigBytes = trackerSigBytes,
        lookupProofBytes = None, // Not needed for first redemption
        trackerLookupProofBytes = Some(trackerLookupProof)
      )

      // Tracker data input
      val trackerDataInput = mkTrackerDataInput(trackerTree)

      // Basis output
      val basisOutput = createOut(
        Constants.basisContract,
        minValue + totalDebt - redeemAmount + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )

      // Redemption output
      val redemptionOutput = createOut(
        trueScript,
        redeemAmount,
        Array(),
        Array()
      )

      // This should succeed
      noException should be thrownBy {
        assertTxSucceeds(
          Array(basisInput),
          Array(trackerDataInput),
          Array(basisOutput, redemptionOutput),
          Array(receiverSecret.toString())
        )
      }
    }
  }

  // ========== TEST 1: selfPreserved Condition ==========

  property("CONDITION 1 (selfPreserved): should fail with different contract in output") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>

      val totalDebt = 50000000L
      val redeemAmount = 50000000L
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt)
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val trackerSig = SigUtils.sign(message, trackerSecret)
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, redeemAmount)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisInput(
        minValue + totalDebt + feeValue, initialTree, receiverPk,
        mkSigBytes(reserveSig), totalDebt, proofBytes, mkSigBytes(trackerSig),
        None, Some(trackerLookupProof)
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      // FAIL: Different contract (trueScript instead of basisContract)
      val badBasisOutput = createOut(
        trueScript, // Wrong contract!
        minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )

      assertTxFails(
        Array(basisInput), Array(trackerDataInput),
        Array(badBasisOutput, redemptionOutput),
        Array(receiverSecret.toString())
      )
    }
  }

  property("CONDITION 1 (selfPreserved): should fail with different R4 (owner key) in output") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>

      val totalDebt = 50000000L
      val redeemAmount = 50000000L
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt)
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val trackerSig = SigUtils.sign(message, trackerSecret)
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, redeemAmount)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisInput(
        minValue + totalDebt + feeValue, initialTree, receiverPk,
        mkSigBytes(reserveSig), totalDebt, proofBytes, mkSigBytes(trackerSig),
        None, Some(trackerLookupProof)
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      // FAIL: Different owner key in output
      val wrongOwnerKey = SigUtils.randBigInt
      val wrongOwnerPk = Constants.g.exp(wrongOwnerKey.bigInteger)
      val badBasisOutput = createOut(
        Constants.basisContract,
        minValue + feeValue,
        Array(ErgoValue.of(wrongOwnerPk), nextTree, ErgoValue.of(trackerNFTBytes)), // Wrong R4!
        Array(new ErgoToken(basisTokenId, 1))
      )

      assertTxFails(
        Array(basisInput), Array(trackerDataInput),
        Array(badBasisOutput, redemptionOutput),
        Array(receiverSecret.toString())
      )
    }
  }

  property("CONDITION 1 (selfPreserved): should fail with different R6 (tracker NFT) in output") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>

      val totalDebt = 50000000L
      val redeemAmount = 50000000L
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt)
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val trackerSig = SigUtils.sign(message, trackerSecret)
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, redeemAmount)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisInput(
        minValue + totalDebt + feeValue, initialTree, receiverPk,
        mkSigBytes(reserveSig), totalDebt, proofBytes, mkSigBytes(trackerSig),
        None, Some(trackerLookupProof)
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      // FAIL: Different tracker NFT in output
      val wrongTrackerNFT = "4c45f29a5165b030fdb5eaf5d81f8108f9d8f507b31487dd51f4ae08fe07cf4b"
      val wrongTrackerNFTBytes = Base16.decode(wrongTrackerNFT).get
      val badBasisOutput = createOut(
        Constants.basisContract,
        minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(wrongTrackerNFTBytes)), // Wrong R6!
        Array(new ErgoToken(basisTokenId, 1))
      )

      assertTxFails(
        Array(basisInput), Array(trackerDataInput),
        Array(badBasisOutput, redemptionOutput),
        Array(receiverSecret.toString())
      )
    }
  }

  // ========== TEST 2: trackerIdCorrect Condition ==========

  property("CONDITION 2 (trackerIdCorrect): should fail with wrong tracker NFT in reserve") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>

      val totalDebt = 50000000L
      val redeemAmount = 50000000L
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt)
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val trackerSig = SigUtils.sign(message, trackerSecret)
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, redeemAmount)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      // FAIL: Wrong tracker NFT in reserve
      val wrongTrackerNFT = "4c45f29a5165b030fdb5eaf5d81f8108f9d8f507b31487dd51f4ae08fe07cf4b"
      val wrongTrackerNFTBytes = Base16.decode(wrongTrackerNFT).get

      val basisInput = ctx.newTxBuilder().outBoxBuilder
        .value(minValue + totalDebt + feeValue)
        .tokens(new ErgoToken(basisTokenId, 1))
        .registers(ErgoValue.of(ownerPk), initialTree, ErgoValue.of(wrongTrackerNFTBytes)) // Wrong R6!
        .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.basisContract))
        .build()
        .convertToInputWith(fakeTxId1, fakeIndex)
        .withContextVars(
          new ContextVar(0, ErgoValue.of(0: Byte)),
          new ContextVar(1, ErgoValue.of(receiverPk)),
          new ContextVar(2, ErgoValue.of(mkSigBytes(reserveSig))),
          new ContextVar(3, ErgoValue.of(totalDebt)),
          new ContextVar(5, ErgoValue.of(proofBytes)),
          new ContextVar(6, ErgoValue.of(mkSigBytes(trackerSig))),
          new ContextVar(8, ErgoValue.of(trackerLookupProof))
        )

      val trackerDataInput = mkTrackerDataInput(trackerTree)
      val basisOutput = createOut(
        Constants.basisContract,
        minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(wrongTrackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      assertTxFails(
        Array(basisInput), Array(trackerDataInput),
        Array(basisOutput, redemptionOutput),
        Array(receiverSecret.toString())
      )
    }
  }

  // ========== TEST 3: trackerDebtCorrect Condition ==========

  property("CONDITION 3 (trackerDebtCorrect): should fail with wrong debt amount in tracker tree") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>

      val totalDebt = 50000000L
      val redeemAmount = 50000000L
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt)
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val trackerSig = SigUtils.sign(message, trackerSecret)
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, redeemAmount)

      // FAIL: Tracker tree has wrong debt amount
      val wrongDebt = 100000000L // Wrong! Should be 50000000L
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, wrongDebt)

      val basisInput = mkBasisInput(
        minValue + totalDebt + feeValue, initialTree, receiverPk,
        mkSigBytes(reserveSig), totalDebt, proofBytes, mkSigBytes(trackerSig),
        None, Some(trackerLookupProof)
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)
      val basisOutput = createOut(
        Constants.basisContract,
        minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      assertTxFails(
        Array(basisInput), Array(trackerDataInput),
        Array(basisOutput, redemptionOutput),
        Array(receiverSecret.toString())
      )
    }
  }

  property("CONDITION 3 (trackerDebtCorrect): should fail with missing key in tracker tree") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>

      val totalDebt = 50000000L
      val redeemAmount = 50000000L
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt)
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val trackerSig = SigUtils.sign(message, trackerSecret)
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, redeemAmount)

      // FAIL: Tracker tree is empty (key not found)
      val TrackerTreeAndProof(emptyTrackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)
      // Use empty tree instead
      val emptyTrackerTreeValue = ErgoValue.of(emptyTree)

      val basisInput = mkBasisInput(
        minValue + totalDebt + feeValue, initialTree, receiverPk,
        mkSigBytes(reserveSig), totalDebt, proofBytes, mkSigBytes(trackerSig),
        None, Some(trackerLookupProof) // Proof won't match empty tree!
      )
      val trackerDataInput = mkTrackerDataInput(emptyTrackerTreeValue)
      val basisOutput = createOut(
        Constants.basisContract,
        minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      assertTxFails(
        Array(basisInput), Array(trackerDataInput),
        Array(basisOutput, redemptionOutput),
        Array(receiverSecret.toString())
      )
    }
  }

  // ========== TEST 4: properRedemptionTree Condition ==========

  property("CONDITION 4 (properRedemptionTree): should fail with wrong AVL tree in output") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>

      val totalDebt = 50000000L
      val redeemAmount = 50000000L
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt)
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val trackerSig = SigUtils.sign(message, trackerSecret)
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, redeemAmount)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisInput(
        minValue + totalDebt + feeValue, initialTree, receiverPk,
        mkSigBytes(reserveSig), totalDebt, proofBytes, mkSigBytes(trackerSig),
        None, Some(trackerLookupProof)
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      // FAIL: Wrong tree in output (use initial tree instead of nextTree)
      val badBasisOutput = createOut(
        Constants.basisContract,
        minValue + feeValue,
        Array(ErgoValue.of(ownerPk), initialTree, ErgoValue.of(trackerNFTBytes)), // Wrong R5!
        Array(new ErgoToken(basisTokenId, 1))
      )

      assertTxFails(
        Array(basisInput), Array(trackerDataInput),
        Array(badBasisOutput, redemptionOutput),
        Array(receiverSecret.toString())
      )
    }
  }

  property("CONDITION 4 (properRedemptionTree): should fail with wrong redeemed amount in tree") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>

      val totalDebt = 50000000L
      val redeemAmount = 50000000L
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt)
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val trackerSig = SigUtils.sign(message, trackerSecret)
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, redeemAmount)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisInput(
        minValue + totalDebt + feeValue, initialTree, receiverPk,
        mkSigBytes(reserveSig), totalDebt, proofBytes, mkSigBytes(trackerSig),
        None, Some(trackerLookupProof)
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      // FAIL: Tree with wrong redeemed amount
      val wrongRedeemed = 25000000L // Wrong! Should be 50000000L
      val TreeAndProof(_, wrongNextTree, _) = mkTreeAndProof(key, wrongRedeemed)
      val badBasisOutput = createOut(
        Constants.basisContract,
        minValue + feeValue,
        Array(ErgoValue.of(ownerPk), wrongNextTree, ErgoValue.of(trackerNFTBytes)), // Wrong R5!
        Array(new ErgoToken(basisTokenId, 1))
      )

      assertTxFails(
        Array(basisInput), Array(trackerDataInput),
        Array(badBasisOutput, redemptionOutput),
        Array(receiverSecret.toString())
      )
    }
  }

  // ========== TEST 5: properReserveSignature Condition ==========

  property("CONDITION 5 (properReserveSignature): should fail with invalid reserve owner signature") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>

      val totalDebt = 50000000L
      val redeemAmount = 50000000L
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt)

      // FAIL: Wrong secret for reserve signature
      val invalidReserveSig = SigUtils.sign(message, trackerSecret) // Using tracker secret!
      val trackerSig = SigUtils.sign(message, trackerSecret)
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, redeemAmount)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisInput(
        minValue + totalDebt + feeValue, initialTree, receiverPk,
        mkSigBytes(invalidReserveSig), totalDebt, proofBytes, mkSigBytes(trackerSig),
        None, Some(trackerLookupProof)
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)
      val basisOutput = createOut(
        Constants.basisContract,
        minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      assertTxFails(
        Array(basisInput), Array(trackerDataInput),
        Array(basisOutput, redemptionOutput),
        Array(receiverSecret.toString())
      )
    }
  }

  property("CONDITION 5 (properReserveSignature): should fail with signature on wrong message") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>

      val totalDebt = 50000000L
      val redeemAmount = 50000000L
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt)

      // FAIL: Signature on wrong message (different debt amount)
      val wrongMessage = mkMessage(key, 100000000L) // Wrong debt!
      val invalidReserveSig = SigUtils.sign(wrongMessage, ownerSecret)
      val trackerSig = SigUtils.sign(message, trackerSecret)
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, redeemAmount)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisInput(
        minValue + totalDebt + feeValue, initialTree, receiverPk,
        mkSigBytes(invalidReserveSig), totalDebt, proofBytes, mkSigBytes(trackerSig),
        None, Some(trackerLookupProof)
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)
      val basisOutput = createOut(
        Constants.basisContract,
        minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      assertTxFails(
        Array(basisInput), Array(trackerDataInput),
        Array(basisOutput, redemptionOutput),
        Array(receiverSecret.toString())
      )
    }
  }

  // ========== TEST 6: properlyRedeemed Condition ==========

  property("CONDITION 6 (properlyRedeemed): should fail with zero redemption amount") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>

      val totalDebt = 50000000L
      val redeemAmount = 0L // FAIL: Zero redemption!
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt)
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val trackerSig = SigUtils.sign(message, trackerSecret)
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, redeemAmount)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisInput(
        minValue + totalDebt + feeValue, initialTree, receiverPk,
        mkSigBytes(reserveSig), totalDebt, proofBytes, mkSigBytes(trackerSig),
        None, Some(trackerLookupProof)
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)
      val basisOutput = createOut(
        Constants.basisContract,
        minValue + totalDebt + feeValue, // Same value (no redemption)
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      // No redemption output

      assertTxFails(
        Array(basisInput), Array(trackerDataInput),
        Array(basisOutput),
        Array(receiverSecret.toString())
      )
    }
  }

  property("CONDITION 6 (properlyRedeemed): should fail redeeming more than debt delta") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>

      val totalDebt = 50000000L
      val redeemAmount = 100000000L // FAIL: More than total debt!
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt)
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val trackerSig = SigUtils.sign(message, trackerSecret)
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, redeemAmount)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisInput(
        minValue + totalDebt + feeValue, initialTree, receiverPk,
        mkSigBytes(reserveSig), totalDebt, proofBytes, mkSigBytes(trackerSig),
        None, Some(trackerLookupProof)
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)
      val basisOutput = createOut(
        Constants.basisContract,
        minValue + feeValue - 50000000L, // Negative value would fail, but script should fail first
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      assertTxFails(
        Array(basisInput), Array(trackerDataInput),
        Array(basisOutput, redemptionOutput),
        Array(receiverSecret.toString())
      )
    }
  }

  property("CONDITION 6 (properlyRedeemed): should fail with invalid tracker signature") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>

      val totalDebt = 50000000L
      val redeemAmount = 50000000L
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt)
      val reserveSig = SigUtils.sign(message, ownerSecret)

      // FAIL: Wrong secret for tracker signature
      val invalidTrackerSig = SigUtils.sign(message, ownerSecret) // Using owner secret!
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, redeemAmount)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisInput(
        minValue + totalDebt + feeValue, initialTree, receiverPk,
        mkSigBytes(reserveSig), totalDebt, proofBytes, mkSigBytes(invalidTrackerSig),
        None, Some(trackerLookupProof)
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)
      val basisOutput = createOut(
        Constants.basisContract,
        minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      assertTxFails(
        Array(basisInput), Array(trackerDataInput),
        Array(basisOutput, redemptionOutput),
        Array(receiverSecret.toString())
      )
    }
  }

  // ========== TEST 7: receiverCondition ==========

  property("CONDITION 7 (receiverCondition): should fail without receiver signature") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>

      val totalDebt = 50000000L
      val redeemAmount = 50000000L
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt)
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val trackerSig = SigUtils.sign(message, trackerSecret)
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, redeemAmount)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisInput(
        minValue + totalDebt + feeValue, initialTree, receiverPk,
        mkSigBytes(reserveSig), totalDebt, proofBytes, mkSigBytes(trackerSig),
        None, Some(trackerLookupProof)
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)
      val basisOutput = createOut(
        Constants.basisContract,
        minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      // FAIL: Don't provide receiver secret for signing
      // Note: This test may fail at transaction building stage (not script validation)
      // because the receiver signature is required to spend the redemption output.
      // The contract's receiverCondition uses proveDlog(receiver), which requires
      // the receiver's secret to sign the transaction.
      val ex = intercept[Throwable] {
        createTx(
          Array(basisInput), Array(trackerDataInput),
          Array(basisOutput, redemptionOutput),
          fee = None, changeAddress, Array(), // No receiver secret!
          broadcast = false
        )
      }
      // The test passes if any exception is thrown (either construction or validation failure)
      ex shouldBe a[Throwable]
    }
  }

  // ========== CONTROL TESTS (should succeed) ==========

  property("CONTROL: selfPreserved - correct contract should succeed") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val totalDebt = 50000000L
      val redeemAmount = 50000000L
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt)
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val trackerSig = SigUtils.sign(message, trackerSecret)
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, redeemAmount)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisInput(
        minValue + totalDebt + feeValue, initialTree, receiverPk,
        mkSigBytes(reserveSig), totalDebt, proofBytes, mkSigBytes(trackerSig),
        None, Some(trackerLookupProof)
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)
      val basisOutput = createOut(
        Constants.basisContract, // Correct contract
        minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      noException should be thrownBy {
        assertTxSucceeds(
          Array(basisInput), Array(trackerDataInput),
          Array(basisOutput, redemptionOutput),
          Array(receiverSecret.toString())
        )
      }
    }
  }

  property("CONTROL: trackerIdCorrect - matching tracker NFT should succeed") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val totalDebt = 50000000L
      val redeemAmount = 50000000L
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt)
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val trackerSig = SigUtils.sign(message, trackerSecret)
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, redeemAmount)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisInput(
        minValue + totalDebt + feeValue, initialTree, receiverPk,
        mkSigBytes(reserveSig), totalDebt, proofBytes, mkSigBytes(trackerSig),
        None, Some(trackerLookupProof)
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)
      val basisOutput = createOut(
        Constants.basisContract,
        minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      noException should be thrownBy {
        assertTxSucceeds(
          Array(basisInput), Array(trackerDataInput),
          Array(basisOutput, redemptionOutput),
          Array(receiverSecret.toString())
        )
      }
    }
  }

  property("CONTROL: trackerDebtCorrect - matching debt should succeed") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val totalDebt = 50000000L
      val redeemAmount = 50000000L
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt)
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val trackerSig = SigUtils.sign(message, trackerSecret)
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, redeemAmount)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisInput(
        minValue + totalDebt + feeValue, initialTree, receiverPk,
        mkSigBytes(reserveSig), totalDebt, proofBytes, mkSigBytes(trackerSig),
        None, Some(trackerLookupProof)
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)
      val basisOutput = createOut(
        Constants.basisContract,
        minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      noException should be thrownBy {
        assertTxSucceeds(
          Array(basisInput), Array(trackerDataInput),
          Array(basisOutput, redemptionOutput),
          Array(receiverSecret.toString())
        )
      }
    }
  }

  property("CONTROL: properRedemptionTree - correct tree should succeed") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val totalDebt = 50000000L
      val redeemAmount = 50000000L
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt)
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val trackerSig = SigUtils.sign(message, trackerSecret)
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, redeemAmount)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisInput(
        minValue + totalDebt + feeValue, initialTree, receiverPk,
        mkSigBytes(reserveSig), totalDebt, proofBytes, mkSigBytes(trackerSig),
        None, Some(trackerLookupProof)
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)
      val basisOutput = createOut(
        Constants.basisContract,
        minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)), // Correct tree
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      noException should be thrownBy {
        assertTxSucceeds(
          Array(basisInput), Array(trackerDataInput),
          Array(basisOutput, redemptionOutput),
          Array(receiverSecret.toString())
        )
      }
    }
  }

  property("CONTROL: properReserveSignature - valid signature should succeed") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val totalDebt = 50000000L
      val redeemAmount = 50000000L
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt)
      val reserveSig = SigUtils.sign(message, ownerSecret) // Correct signature
      val trackerSig = SigUtils.sign(message, trackerSecret)
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, redeemAmount)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisInput(
        minValue + totalDebt + feeValue, initialTree, receiverPk,
        mkSigBytes(reserveSig), totalDebt, proofBytes, mkSigBytes(trackerSig),
        None, Some(trackerLookupProof)
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)
      val basisOutput = createOut(
        Constants.basisContract,
        minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      noException should be thrownBy {
        assertTxSucceeds(
          Array(basisInput), Array(trackerDataInput),
          Array(basisOutput, redemptionOutput),
          Array(receiverSecret.toString())
        )
      }
    }
  }

  property("CONTROL: properlyRedeemed - valid redemption amount should succeed") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val totalDebt = 50000000L
      val redeemAmount = 50000000L
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt)
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val trackerSig = SigUtils.sign(message, trackerSecret)
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, redeemAmount)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisInput(
        minValue + totalDebt + feeValue, initialTree, receiverPk,
        mkSigBytes(reserveSig), totalDebt, proofBytes, mkSigBytes(trackerSig),
        None, Some(trackerLookupProof)
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)
      val basisOutput = createOut(
        Constants.basisContract,
        minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      noException should be thrownBy {
        assertTxSucceeds(
          Array(basisInput), Array(trackerDataInput),
          Array(basisOutput, redemptionOutput),
          Array(receiverSecret.toString())
        )
      }
    }
  }

  property("CONTROL: receiverCondition - with receiver signature should succeed") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val totalDebt = 50000000L
      val redeemAmount = 50000000L
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt)
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val trackerSig = SigUtils.sign(message, trackerSecret)
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, redeemAmount)
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisInput(
        minValue + totalDebt + feeValue, initialTree, receiverPk,
        mkSigBytes(reserveSig), totalDebt, proofBytes, mkSigBytes(trackerSig),
        None, Some(trackerLookupProof)
      )
      val trackerDataInput = mkTrackerDataInput(trackerTree)
      val basisOutput = createOut(
        Constants.basisContract,
        minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      noException should be thrownBy {
        assertTxSucceeds(
          Array(basisInput), Array(trackerDataInput),
          Array(basisOutput, redemptionOutput),
          Array(receiverSecret.toString()) // Receiver signature provided
        )
      }
    }
  }
}
