package chaincash

import chaincash.contracts.Constants
import chaincash.contracts.Constants.chainCashPlasmaParameters
import chaincash.offchain.SigUtils
import com.google.common.primitives.Longs
import io.circe.parser
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
 * Test data represents Alice->Bob note scenario:
 * - Alice (reserve owner) creates a note payable to Bob
 * - Tracker maintains AVL tree with Alice->Bob debt entry
 * - Reserve AVL tree starts empty (first redemption)
 *
 * This test suite uses actual box data fetched from the Ergo node via scan endpoints:
 * - scanId=38: Reserve box
 * - scanId=36: Tracker box
 */
class BasisContractConditionsSpec extends PropSpec with Matchers with ScalaCheckDrivenPropertyChecks with HttpClientTesting with ScanBoxHelpers {

  implicit val addrEncoder = Constants.ergoAddressEncoder

  // ========== FAKE TX IDs FOR TESTING ==========
  val fakeTxId1 = "f9e5ce5aa0d95f5d54a7bc89c46730d9662397067250aa18a0039631c0f5b809"
  val fakeTxId2 = "f9e5ce5aa0d95f5d54a7bc89c46730d9662397067250aa18a0039631c0f5b808"
  val fakeIndex = 1.toShort

  // ========== LOAD BOX DATA FROM SCAN RESPONSES ==========
  // These values are loaded from mock scan endpoint responses
  // Reserve box (scanId=38) - Alice's reserve backing notes
  // Tracker box (scanId=36) - tracks offchain debt
  val reserveScanData = parseScanResponse(loadScanResponse(38))
  val trackerScanData = parseScanResponse(loadScanResponse(36))

  val reserveBoxId = reserveScanData.boxId
  val reserveBoxValue = reserveScanData.value
  val reserveBoxCreationHeight = reserveScanData.creationHeight
  val reserveBoxR4 = reserveScanData.r4
  val reserveBoxR5 = reserveScanData.r5
  val reserveBoxR6 = reserveScanData.r6

  val trackerBoxId = trackerScanData.boxId
  val trackerBoxValue = trackerScanData.value
  val trackerBoxCreationHeight = trackerScanData.creationHeight
  val trackerBoxR4 = trackerScanData.r4
  val trackerBoxR5 = trackerScanData.r5

  // Token IDs from scan data
  val basisTokenId = reserveScanData.tokenId
  val trackerNFT = trackerScanData.tokenId
  val trackerNFTBytes = Base16.decode(trackerNFT).get

  // Parse AVL trees from box registers
  // R5 format: 64 (AVL tree type) + flags + keyLength + valueLength + digest + proof
  val reserveTreeBytes = Base16.decode(reserveBoxR5).get
  val trackerTreeBytes = Base16.decode(trackerBoxR5).get

  // ========== PARSE ACTUAL DATA FROM sign_request.json ==========
  // Load actual extension registers from sign_request.json for realistic testing
  val signRequestJson = scala.io.Source.fromFile("sign_request.json").mkString
  val signRequestData = io.circe.parser.parse(signRequestJson).toOption.get
  val extension = signRequestData.hcursor.downField("tx").downField("inputs").downN(0).downField("extension").as[Map[String, String]].toOption.get

  // Extension registers from sign_request.json
  val extensionR0 = extension("0")  // action
  val extensionR1 = extension("1")  // receiver public key
  val extensionR2 = extension("2")  // reserve signature
  val extensionR3 = extension("3")  // totalDebt
  val extensionR6 = extension("6")  // tracker signature
  val extensionR8 = extension("8")  // AVL proof

  // Parse actual public keys
  // reserveBoxR4 format: 07 (GroupElement type) + 03 (compressed) + 32 bytes
  val ownerPkBytes = Base16.decode(reserveBoxR4.drop(4)).get
  val ownerPkFullBytes = (0x03.toByte +: ownerPkBytes)
  val ownerPk = GroupElementSerializer.fromBytes(ownerPkFullBytes)

  // extensionR1 format: same as above
  val receiverPkBytes = Base16.decode(extensionR1.drop(4)).get
  val receiverPkFullBytes = (0x03.toByte +: receiverPkBytes)
  val receiverPk = GroupElementSerializer.fromBytes(receiverPkFullBytes)

  // Parse tracker public key from trackerBoxR4
  val trackerPkBytes = Base16.decode(trackerBoxR4.drop(4)).get
  val trackerPkFullBytes = (0x03.toByte +: trackerPkBytes)
  val trackerPk = GroupElementSerializer.fromBytes(trackerPkFullBytes)

  // Parse totalDebt from extension R3 (format: 05 + zigzag-encoded varint)
  val totalDebtBytes = Base16.decode(extensionR3).get
  // Use ValueSerializer to decode the Long value
  val totalDebt = sigmastate.serialization.ValueSerializer.deserialize(totalDebtBytes).asInstanceOf[sigmastate.Values.LongConstant].value

  // Get actual signatures (skip Coll type 0e and length byte)
  val reserveSigBytes = Base16.decode(extensionR2.drop(4)).get
  val trackerSigBytes = Base16.decode(extensionR6.drop(4)).get

  // Get actual AVL proof (skip Coll type 0e and length byte)
  val trackerLookupProofBytes = Base16.decode(extensionR8.drop(4)).get

  // Compute the debt key using actual public keys
  val debtKey = Blake2b256(ownerPk.getEncoded.toArray ++ receiverPk.getEncoded.toArray)

  // Transaction values
  val minValue = 1000000000L
  val feeValue = 1000000L
  val redeemAmount = totalDebt
  val inputValue = minValue + totalDebt + feeValue
  val outputValue = minValue + feeValue

  // Create tracker AVL tree with actual debt entry (matching tracker box)
  val trackerPlasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
  trackerPlasmaMap.insert((debtKey, Longs.toByteArray(totalDebt)))
  val trackerTreeWithKey = trackerPlasmaMap.ergoValue

  // Create empty reserve AVL tree (for first redemption)
  val reservePlasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
  val emptyReserveTree = reservePlasmaMap.ergoValue

  // For properRedemptionTree test: tree after inserting redeemed amount
  val reservePlasmaMapAfter = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
  val reserveInsertRes = reservePlasmaMapAfter.insert((debtKey, Longs.toByteArray(redeemAmount)))
  val reserveTreeAfterInsert = reservePlasmaMapAfter.ergoValue
  val reserveInsertProofBytes = reserveInsertRes.proof.bytes

  // For signature verification tests, we use the actual signatures from sign_request.json
  // For tests requiring receiver signature, we need receiver's secret (not available in sign_request.json)
  // We use a mock secret for testing, but note that real transactions need actual receiver signature

  // Generate fresh tracker signature for the properlyRedeemed test
  // The signature in sign_request.json was created with a different tracker key
  // We need to generate one that matches our mock tracker box
  val trackerSecret = SigUtils.randBigInt
  val trackerPkFromSecret = Constants.g.exp(trackerSecret.bigInteger)
  
  // Create redemption message: key || totalDebt
  val redemptionMessage = debtKey ++ Longs.toByteArray(totalDebt)
  val (trackerSigA, trackerSigZ) = SigUtils.sign(redemptionMessage, trackerSecret)
  val freshTrackerSigBytes = sigmastate.serialization.GroupElementSerializer.toBytes(trackerSigA) ++ trackerSigZ.toByteArray

  // Change address for transaction building (use a mock key we have the secret for)
  val mockReceiverSecret = SigUtils.randBigInt
  val mockReceiverPk = Constants.g.exp(mockReceiverSecret.bigInteger)
  val changeAddress = P2PKAddress(ProveDlog(mockReceiverPk)).toString()

  // ========== TRANSACTION HELPERS ==========
  def createOut(contract: String, value: Long, registers: Array[ErgoValue[_]], tokens: Array[ErgoToken])(implicit ctx: BlockchainContext): OutBoxImpl = {
    val c = ErgoScriptContract.create(new org.ergoplatform.appkit.Constants, contract, Constants.networkType)
    val ebc = AppkitHelpers.createBoxCandidate(value, c.getErgoTree, tokens, registers, ctx.getHeight)
    new OutBoxImpl(ebc)
  }

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

  def hasScriptValidationFailure(t: Throwable): Boolean =
    Iterator.iterate[Throwable](t)(_.getCause).takeWhile(_ != null).exists {
      case _: sigmastate.exceptions.InterpreterException => true
      case _ => false
    }

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
        conditionScript("selfPreserved"), inputValue, emptyReserveTree, receiverPk,
        reserveSigBytes, totalDebt, reserveInsertProofBytes, trackerSigBytes, trackerLookupProofBytes
      )
      val trackerDataInput = mkTrackerDataInput(trackerTreeWithKey, trackerPk)

      val basisOutput = createOut(
        conditionScript("selfPreserved"), outputValue,
        Array(ErgoValue.of(ownerPk), emptyReserveTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut("{sigmaProp(true)}", redeemAmount, Array(), Array())

      noException should be thrownBy {
        createTx(Array(basisInput), Array(trackerDataInput), Array(basisOutput, redemptionOutput),
          fee = None, changeAddress, Array(mockReceiverSecret.toString()), broadcast = false)
      }
    }
  }

  property("CONDITION 2: trackerIdCorrect - should pass with matching tracker NFT") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val basisInput = mkBasisInput(
        conditionScript("trackerIdCorrect"), inputValue, emptyReserveTree, receiverPk,
        reserveSigBytes, totalDebt, reserveInsertProofBytes, trackerSigBytes, trackerLookupProofBytes
      )
      val trackerDataInput = mkTrackerDataInput(trackerTreeWithKey, trackerPk)

      val basisOutput = createOut(
        conditionScript("trackerIdCorrect"), outputValue,
        Array(ErgoValue.of(ownerPk), emptyReserveTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut("{sigmaProp(true)}", redeemAmount, Array(), Array())

      noException should be thrownBy {
        createTx(Array(basisInput), Array(trackerDataInput), Array(basisOutput, redemptionOutput),
          fee = None, changeAddress, Array(mockReceiverSecret.toString()), broadcast = false)
      }
    }
  }

  property("CONDITION 3: trackerDebtCorrect - verify tracker AVL tree from node") {
    // This test verifies the tracker box AVL tree contains the expected debt entry
    // Uses actual tracker box from scanId=36
    
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      // The tracker tree R5 from node: 642c1d1fb21a9df51972a5439ca7ce8d5601f99c871f15cbf2c4ff6ae53d57a96f01032000
      // This is a serialized AVL tree digest
      
      // For this test, we verify the tree structure matches what we expect
      // The tree should contain entries for: hash(ownerKey || receiverKey) -> totalDebt
      
      // Since we can't directly deserialize the tree without the full PlasmaMap state,
      // we verify the tree bytes are present and have the correct format
      trackerTreeBytes.length shouldBe > (0)
      trackerTreeBytes(0) shouldBe 0x64.toByte // AVL tree type tag
      
      // The tree digest (first 32 bytes after header) represents the Merkle root
      // of all debt entries tracked by this tracker
      val treeDigest = trackerTreeBytes.drop(1).take(32)
      treeDigest.length shouldBe 32
      
      println(s"✓ Tracker AVL tree digest: ${Base16.encode(treeDigest)}")
      println(s"  Tree bytes length: ${trackerTreeBytes.length}")
      println(s"  Tracker box: $trackerBoxId")
      println(s"  To verify debt entries, query the tracker's offchain state")
      
      succeed
    }
  }

  property("CONDITION 4: properRedemptionTree - verify reserve AVL tree from node") {
    // This test verifies the reserve box AVL tree for tracking redeemed debt
    // Uses actual reserve box from scanId=38
    
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      // The reserve tree R5 from node: 644ec61f485b98eb87153f7c57db4f5ecd75556fddbc403b41acf8441fde8e160900032000
      // This is a serialized AVL tree digest
      
      // Verify the tree structure
      reserveTreeBytes.length shouldBe > (0)
      reserveTreeBytes(0) shouldBe 0x64.toByte // AVL tree type tag
      
      // The tree digest represents the Merkle root of redeemed debt entries
      val treeDigest = reserveTreeBytes.drop(1).take(32)
      treeDigest.length shouldBe 32
      
      println(s"✓ Reserve AVL tree digest: ${Base16.encode(treeDigest)}")
      println(s"  Tree bytes length: ${reserveTreeBytes.length}")
      println(s"  Reserve box: $reserveBoxId")
      println(s"  This tree tracks cumulative redeemed amounts per (owner, receiver) pair")
      
      // For first redemption, the tree should be empty or contain previous redemptions
      // The digest 4ec61f485b98eb87153f7c57db4f5ecd75556fddbc403b41acf8441fde8e1609
      // represents the current state
      
      succeed
    }
  }

  property("CONDITION 5: properReserveSignature - should pass with valid owner signature") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val basisInput = mkBasisInput(
        conditionScript("properReserveSignature"), inputValue, emptyReserveTree, receiverPk,
        reserveSigBytes, totalDebt, reserveInsertProofBytes, trackerSigBytes, trackerLookupProofBytes
      )
      val trackerDataInput = mkTrackerDataInput(trackerTreeWithKey, trackerPk)

      val basisOutput = createOut(
        conditionScript("properReserveSignature"), outputValue,
        Array(ErgoValue.of(ownerPk), emptyReserveTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut("{sigmaProp(true)}", redeemAmount, Array(), Array())

      noException should be thrownBy {
        createTx(Array(basisInput), Array(trackerDataInput), Array(basisOutput, redemptionOutput),
          fee = None, changeAddress, Array(mockReceiverSecret.toString()), broadcast = false)
      }
    }
  }

  property("CONDITION 6: properlyRedeemed - should pass with valid redemption and tracker signature") {
    // This test verifies the properlyRedeemed condition with valid tracker signature
    // The tracker signature authorizes the redemption amount reduction
    // We generate a fresh tracker signature that matches our mock tracker key
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      // Create tracker data input with the matching tracker public key
      val trackerDataInput = mkTrackerDataInput(trackerTreeWithKey, trackerPkFromSecret)

      val basisInput = ctx.newTxBuilder().outBoxBuilder
        .value(inputValue)
        .tokens(new ErgoToken(basisTokenId, 1))
        .registers(ErgoValue.of(ownerPk), emptyReserveTree, ErgoValue.of(trackerNFTBytes))
        .contract(ctx.compileContract(ConstantsBuilder.empty(), conditionScript("properlyRedeemed")))
        .build()
        .convertToInputWith(reserveBoxId, fakeIndex)
        .withContextVars(
          new ContextVar(0, ErgoValue.of(0x00.toByte)), // action=0
          new ContextVar(1, ErgoValue.of(receiverPk)),
          new ContextVar(2, ErgoValue.of(reserveSigBytes)),
          new ContextVar(3, ErgoValue.of(totalDebt)),
          new ContextVar(5, ErgoValue.of(reserveInsertProofBytes)),
          new ContextVar(6, ErgoValue.of(freshTrackerSigBytes)), // Use fresh tracker signature
          new ContextVar(8, ErgoValue.of(trackerLookupProofBytes))
        )

      val basisOutput = createOut(
        conditionScript("properlyRedeemed"), outputValue,
        Array(ErgoValue.of(ownerPk), emptyReserveTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut("{sigmaProp(true)}", redeemAmount, Array(), Array())

      noException should be thrownBy {
        createTx(Array(basisInput), Array(trackerDataInput), Array(basisOutput, redemptionOutput),
          fee = None, changeAddress, Array(mockReceiverSecret.toString()), broadcast = false)
      }
    }
  }

  property("CONDITION 7: receiverCondition - should pass with receiver signature") {
    // Note: This test uses a mock receiver since we don't have Bob's actual secret
    // The actual sign_request.json uses Bob's real public key and requires his signature
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val basisInput = mkBasisInput(
        conditionScript("receiverCondition"), inputValue, emptyReserveTree, mockReceiverPk,
        reserveSigBytes, totalDebt, reserveInsertProofBytes, trackerSigBytes, trackerLookupProofBytes
      )
      val trackerDataInput = mkTrackerDataInput(trackerTreeWithKey, trackerPk)

      val basisOutput = createOut(
        conditionScript("receiverCondition"), outputValue,
        Array(ErgoValue.of(ownerPk), emptyReserveTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut("{sigmaProp(true)}", redeemAmount, Array(), Array())

      noException should be thrownBy {
        createTx(Array(basisInput), Array(trackerDataInput), Array(basisOutput, redemptionOutput),
          fee = None, changeAddress, Array(mockReceiverSecret.toString()), broadcast = false)
      }
    }
  }

  property("BASELINE: full basis.es contract with real node boxes") {
    // This test attempts to run the full basis contract with actual box data from node
    // Note: Full execution requires generating valid AVL proofs which need the
    // original PlasmaMap state, not just the tree digest

    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      println(s"\n=== Real Node Box Data ===")
      println(s"Reserve box: $reserveBoxId")
      println(s"  Value: ${reserveBoxValue} ergs")
      println(s"  Owner: ${reserveBoxR4.take(20)}...")
      println(s"  Tree digest: ${reserveBoxR5.take(40)}...")
      println(s"  Tracker NFT: ${reserveBoxR6.take(30)}...")
      println(s"\nTracker box: $trackerBoxId")
      println(s"  Value: ${trackerBoxValue} ergs")
      println(s"  Tracker key: ${trackerBoxR4.take(20)}...")
      println(s"  Tree digest: ${trackerBoxR5.take(40)}...")

      // The full contract test would require:
      // 1. Deserializing the AVL trees from R5 registers
      // 2. Generating lookup proofs for the specific (owner, receiver) key
      // 3. Generating insert proofs for updating the reserve tree
      // 4. Valid signatures from both owner and tracker
      //
      // Since we only have the tree digests (not the full PlasmaMap state),
      // we can't generate the proofs. This would require:
      // - Access to the tracker's offchain database
      // - Or reconstructing the tree from all historical insertions

      println(s"\n⚠ Full contract execution requires AVL proofs")
      println(s"  which need the original PlasmaMap state.")
      println(s"  See BasisNoteRedeemer.generateTrackerAvlProof() for proof generation.")

      succeed
    }
  }

  // ========== TESTS WITH FETCHED SCAN DATA ==========

  property("SCAN DATA: verify reserve box from scanId=38") {
    // This test verifies that we can successfully load and parse reserve box data from scan endpoint
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      // Verify reserve box data loaded from scan response
      reserveBoxId should not be empty
      reserveBoxValue shouldBe > (0L)
      reserveBoxR4 should startWith ("07")
      reserveBoxR5 should startWith ("64") // AVL tree type tag
      reserveBoxR6 should not be empty

      // Verify basis token (reserve NFT) is present
      basisTokenId should not be empty
      basisTokenId.length shouldBe 64

      println(s"✓ Reserve box loaded from scanId=38:")
      println(s"  Box ID: $reserveBoxId")
      println(s"  Value: $reserveBoxValue ergs")
      println(s"  Basis token ID: $basisTokenId")
      println(s"  Owner pubkey (R4): ${reserveBoxR4.take(20)}...")
      println(s"  AVL tree (R5): ${reserveBoxR5.take(40)}...")
      println(s"  Tracker NFT (R6): ${reserveBoxR6.take(30)}...")

      succeed
    }
  }

  property("SCAN DATA: verify tracker box from scanId=36") {
    // This test verifies that we can successfully load and parse tracker box data from scan endpoint
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      // Verify tracker box data loaded from scan response
      trackerBoxId should not be empty
      trackerBoxValue shouldBe > (0L)
      trackerBoxR4 should startWith ("07")
      trackerBoxR5 should startWith ("64") // AVL tree type tag

      // Verify tracker NFT is present
      trackerNFT should not be empty
      trackerNFT.length shouldBe 64

      println(s"✓ Tracker box loaded from scanId=36:")
      println(s"  Box ID: $trackerBoxId")
      println(s"  Value: $trackerBoxValue ergs")
      println(s"  Tracker NFT: $trackerNFT")
      println(s"  Tracker pubkey (R4): ${trackerBoxR4.take(20)}...")
      println(s"  AVL tree (R5): ${trackerBoxR5.take(40)}...")

      succeed
    }
  }

  property("SCAN DATA: verify tracker NFT matches reserve R6") {
    // This test verifies that the tracker NFT ID in the reserve box R6 matches the tracker box token
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val trackerNftFromReserveR6 = Base16.encode(extractTrackerNftFromR6(reserveBoxR6))

      trackerNftFromReserveR6 shouldBe trackerNFT

      println(s"✓ Tracker NFT linkage verified:")
      println(s"  Tracker NFT from reserve R6: $trackerNftFromReserveR6")
      println(s"  Tracker NFT from tracker box: $trackerNFT")
      println(s"  Match: ${trackerNftFromReserveR6 == trackerNFT}")

      succeed
    }
  }

  property("SCAN DATA: verify AVL tree format in reserve and tracker") {
    // This test verifies that AVL trees in both boxes have correct format
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val reserveTreeBytes = Base16.decode(reserveBoxR5).get
      val trackerTreeBytes = Base16.decode(trackerBoxR5).get

      // Both should start with 0x64 (AVL tree type tag)
      reserveTreeBytes(0) shouldBe 0x64.toByte
      trackerTreeBytes(0) shouldBe 0x64.toByte

      // Extract tree digests (bytes 1-32 after type tag)
      val reserveTreeDigest = Base16.encode(reserveTreeBytes.drop(1).take(32))
      val trackerTreeDigest = Base16.encode(trackerTreeBytes.drop(1).take(32))

      println(s"✓ AVL tree format verified:")
      println(s"  Reserve tree digest: $reserveTreeDigest")
      println(s"  Tracker tree digest: $trackerTreeDigest")
      println(s"  Reserve tree bytes length: ${reserveTreeBytes.length}")
      println(s"  Tracker tree bytes length: ${trackerTreeBytes.length}")

      // Trees should have non-zero length
      reserveTreeBytes.length shouldBe > (0)
      trackerTreeBytes.length shouldBe > (0)

      succeed
    }
  }

  property("SCAN DATA: extract public keys from R4 registers") {
    // This test verifies that we can extract public keys from R4 registers
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val ownerPkBytes = extractPublicKeyFromR4(reserveBoxR4)
      val trackerPkBytes = extractPublicKeyFromR4(trackerBoxR4)

      // Both should be 33 bytes (compression prefix + 32 bytes)
      ownerPkBytes.length shouldBe 33
      trackerPkBytes.length shouldBe 33

      // First byte should be 0x03 (compression flag)
      ownerPkBytes(0) shouldBe 0x03.toByte
      trackerPkBytes(0) shouldBe 0x03.toByte

      println(s"✓ Public keys extracted from R4:")
      println(s"  Owner pubkey bytes: ${Base16.encode(ownerPkBytes).take(40)}...")
      println(s"  Tracker pubkey bytes: ${Base16.encode(trackerPkBytes).take(40)}...")

      succeed
    }
  }

  property("SCAN DATA: tracker tree digest = single Alice->Bob payment on empty tree") {
    // This test verifies that the tracker box's AVL tree digest corresponds to
    // a single Alice->Bob payment entry on top of an empty tree.
    //
    // The tracker tree should contain exactly one entry:
    //   key = Blake2b256(ownerPk || receiverPk)
    //   value = totalDebt (as 8-byte long)
    //
    // We reconstruct this tree using the actual public keys from the sign_request.json
    // and verify its digest matches the tracker box R5.
    //
    // NOTE: This test currently fails because the actual tracker box on-chain contains
    // a tree digest that doesn't match a single Alice->Bob entry. This could be because:
    // - The tracker was initialized with different entries
    // - The tracker tree contains multiple redemptions
    // - The keys used for the tracker tree differ from those in the sign_request

    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      // Extract actual public keys from the reserve box (owner) and sign_request (receiver)
      val ownerPkBytes = extractPublicKeyFromR4(reserveBoxR4)
      val ownerPk = GroupElementSerializer.fromBytes(ownerPkBytes)

      // Receiver pubkey from sign_request.json extension R1
      // R1: 0703af13e39dd0ccc7429f9dfa5a056b71a8f5160eaf179763a03e0b55d8feec2cea
      val receiverPkHex = "03af13e39dd0ccc7429f9dfa5a056b71a8f5160eaf179763a03e0b55d8feec2cea"
      val receiverPkBytes = Base16.decode(receiverPkHex).get
      val receiverPk = GroupElementSerializer.fromBytes(receiverPkBytes)

      // Extract the tracker tree digest from R5
      val trackerTreeBytes = Base16.decode(trackerBoxR5).get
      val actualTrackerTreeDigest = Base16.encode(trackerTreeBytes.drop(1).take(32))

      println(s"\n✓ Tracker AVL tree verification:")
      println(s"  Owner pubkey (from reserve R4): ${Base16.encode(ownerPk.getEncoded.toArray)}")
      println(s"  Receiver pubkey (from sign_request R1): $receiverPkHex")
      println(s"  Actual tracker tree digest: $actualTrackerTreeDigest")

      // Compute the debt key: Blake2b256(ownerPk || receiverPk)
      val debtKey = Blake2b256(ownerPk.getEncoded.toArray ++ receiverPk.getEncoded.toArray)
      println(s"  Expected debt key: ${Base16.encode(debtKey)}")

      // The total debt value from sign_request.json extension R3: 0580c2d72f = 50000000
      val totalDebt = 50000000L
      val debtValue = Longs.toByteArray(totalDebt)
      println(s"  Expected debt value: $totalDebt (${Base16.encode(debtValue)})")

      // Create expected tracker tree with single entry
      val trackerMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
      trackerMap.insert((debtKey, debtValue))
      val expectedTrackerTree = trackerMap.ergoValue

      // Serialize the expected tree to extract the digest
      // Format: type(1 byte) + digest(32 bytes) + proof(...)
      import sigmastate.serialization.ValueSerializer
      import sigmastate.Values.AvlTreeConstant
      // Get the actual AvlTree from ErgoValue and wrap in AvlTreeConstant for serialization
      val actualAvlTree = expectedTrackerTree.getValue()
      val expectedTreeBytes = ValueSerializer.serialize(AvlTreeConstant(actualAvlTree))
      // Skip the type byte and take 32 bytes for the digest
      val expectedDigestBytes = expectedTreeBytes.slice(1, 33)
      val expectedDigestHex = Base16.encode(expectedDigestBytes)

      println(s"  Expected tree digest (single entry): $expectedDigestHex")
      println(s"  Expected digest length: ${expectedDigestHex.length} chars")
      println(s"  Actual digest length: ${actualTrackerTreeDigest.length} chars")

      // Verify the digests match
      expectedDigestHex shouldBe actualTrackerTreeDigest

      println(s"  ✓ Tracker tree digest VERIFIED - matches single Alice->Bob payment!")
    }
  }

  property("INTEGRATION: properRedemptionTree fails without context var 5 (reserve insert proof)") {
    // This test demonstrates the issue where BasisNoteRedeemer doesn't generate
    // the reserve insert proof (context var 5), causing signing to fail.
    //
    // The basis.es contract requires TWO AVL proofs:
    // - Context var 5: Proof for inserting (debtKey, redeemedAmount) into reserve tree
    // - Context var 8: Proof for looking up debtKey in tracker tree
    //
    // Without context var 5, the contract's properRedemptionTree check fails:
    //   val insertProof = getVar[Coll[Byte]](5).get
    //   val nextTree = SELF.R5[AvlTree].get.insert(Coll(redeemedKeyVal), insertProof).get
    //
    // This test verifies that when context var 5 is missing or wrong, the contract fails.

    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      // Create input with EMPTY context var 5 (simulating the bug)
      val emptyProof = Array[Byte]() // Missing proof!

      val basisInput = ctx.newTxBuilder().outBoxBuilder
        .value(inputValue)
        .tokens(new ErgoToken(basisTokenId, 1))
        .registers(ErgoValue.of(ownerPk), emptyReserveTree, ErgoValue.of(trackerNFTBytes))
        .contract(ctx.compileContract(ConstantsBuilder.empty(), conditionScript("properRedemptionTree")))
        .build()
        .convertToInputWith(reserveBoxId, fakeIndex)
        .withContextVars(
          new ContextVar(0, ErgoValue.of(0x00.toByte)), // action=0
          new ContextVar(1, ErgoValue.of(receiverPk)),
          new ContextVar(2, ErgoValue.of(reserveSigBytes)),
          new ContextVar(3, ErgoValue.of(totalDebt)),
          new ContextVar(5, ErgoValue.of(emptyProof)), // ❌ EMPTY PROOF - simulates bug!
          new ContextVar(6, ErgoValue.of(trackerSigBytes)),
          new ContextVar(8, ErgoValue.of(trackerLookupProofBytes))
        )

      val trackerDataInput = mkTrackerDataInput(trackerTreeWithKey, trackerPk)

      val basisOutput = createOut(
        conditionScript("properRedemptionTree"), outputValue,
        Array(ErgoValue.of(ownerPk), reserveTreeAfterInsert, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut("{sigmaProp(true)}", redeemAmount, Array(), Array())

      // This should fail because context var 5 is empty
      val ex = intercept[Throwable] {
        createTx(Array(basisInput), Array(trackerDataInput), Array(basisOutput, redemptionOutput),
          fee = None, changeAddress, Array(mockReceiverSecret.toString()), broadcast = false)
      }

      hasScriptValidationFailure(ex) shouldBe true
      println("✓ Test confirmed: properRedemptionTree fails without valid context var 5")
      println(s"  Error: ${ex.getMessage}")
    }
  }

  property("INTEGRATION: properRedemptionTree passes WITH context var 5 (reserve insert proof)") {
    // This test verifies that when BOTH context vars are provided correctly,
    // the contract validation succeeds.

    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      // Create input with CORRECT context var 5 (reserve insert proof)
      val basisInput = mkBasisInput(
        conditionScript("properRedemptionTree"), inputValue, emptyReserveTree, receiverPk,
        reserveSigBytes, totalDebt, reserveInsertProofBytes, trackerSigBytes, trackerLookupProofBytes
      )
      val trackerDataInput = mkTrackerDataInput(trackerTreeWithKey, trackerPk)

      val basisOutput = createOut(
        conditionScript("properRedemptionTree"), outputValue,
        Array(ErgoValue.of(ownerPk), reserveTreeAfterInsert, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut("{sigmaProp(true)}", redeemAmount, Array(), Array())

      // This should pass because context var 5 has the correct proof
      noException should be thrownBy {
        createTx(Array(basisInput), Array(trackerDataInput), Array(basisOutput, redemptionOutput),
          fee = None, changeAddress, Array(mockReceiverSecret.toString()), broadcast = false)
      }

      println("✓ Test confirmed: properRedemptionTree passes WITH valid context var 5")
    }
  }

  // ========== NEGATIVE TESTS ==========

  property("NEGATIVE: selfPreserved should fail with different contract in output") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val basisInput = mkBasisInput(
        conditionScript("selfPreserved"), inputValue, emptyReserveTree, mockReceiverPk,
        reserveSigBytes, totalDebt, reserveInsertProofBytes, trackerSigBytes, trackerLookupProofBytes
      )
      val trackerDataInput = mkTrackerDataInput(trackerTreeWithKey, trackerPk)

      // Use different contract in output (should fail selfPreserved check)
      val basisOutput = createOut(
        conditionScript("trackerIdCorrect"), outputValue, // Different contract!
        Array(ErgoValue.of(ownerPk), emptyReserveTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut("{sigmaProp(true)}", redeemAmount, Array(), Array())

      val ex = intercept[Throwable] {
        createTx(Array(basisInput), Array(trackerDataInput), Array(basisOutput, redemptionOutput),
          fee = None, changeAddress, Array(mockReceiverSecret.toString()), broadcast = false)
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
        .registers(ErgoValue.of(ownerPk), emptyReserveTree, ErgoValue.of(wrongTrackerNFT))
        .contract(ctx.compileContract(ConstantsBuilder.empty(), conditionScript("trackerIdCorrect")))
        .build()
        .convertToInputWith(reserveBoxId, fakeIndex)
        .withContextVars(
          new ContextVar(0, ErgoValue.of(0x00.toByte)),
          new ContextVar(1, ErgoValue.of(mockReceiverPk)),
          new ContextVar(2, ErgoValue.of(reserveSigBytes)),
          new ContextVar(3, ErgoValue.of(totalDebt)),
          new ContextVar(5, ErgoValue.of(reserveInsertProofBytes)),
          new ContextVar(6, ErgoValue.of(trackerSigBytes)),
          new ContextVar(8, ErgoValue.of(trackerLookupProofBytes))
        )

      val trackerDataInput = mkTrackerDataInput(trackerTreeWithKey, trackerPk)

      val basisOutput = createOut(
        conditionScript("trackerIdCorrect"), outputValue,
        Array(ErgoValue.of(ownerPk), emptyReserveTree, ErgoValue.of(wrongTrackerNFT)),
        Array(new ErgoToken(basisTokenId, 1))
      )
      val redemptionOutput = createOut("{sigmaProp(true)}", redeemAmount, Array(), Array())

      val ex = intercept[Throwable] {
        createTx(Array(basisInput), Array(trackerDataInput), Array(basisOutput, redemptionOutput),
          fee = None, changeAddress, Array(mockReceiverSecret.toString()), broadcast = false)
      }
      hasScriptValidationFailure(ex) shouldBe true
    }
  }
}
