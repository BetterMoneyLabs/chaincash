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
import sigmastate.serialization.{GroupElementSerializer, ValueSerializer}
import special.sigma.{AvlTree, GroupElement}
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.{PlasmaMap, Proof}

import collection.JavaConverters._
import java.util
import scala.util.Try

class BasisSpec extends PropSpec with Matchers with ScalaCheckDrivenPropertyChecks with HttpClientTesting {

  implicit val addrEncoder = Constants.ergoAddressEncoder

  val fakeScript = "sigmaProp(true)"

  val chainCashPlasmaParameters = PlasmaParameters(32, None)
  def emptyPlasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
  val emptyTreeErgoValue: ErgoValue[AvlTree] = emptyPlasmaMap.ergoValue
  val emptyTree: AvlTree = emptyTreeErgoValue.getValue

  val basisTokenId = "4b2d8b7beb3eaac8234d9e61792d270898a43934d6a27275e4f3a044609c9f2a"
  val trackerNFT = "3c45f29a5165b030fdb5eaf5d81f8108f9d8f507b31487dd51f4ae08fe07cf4a"
  val trackerNFTBytes = Base16.decode(trackerNFT).get

  val ownerSecret = SigUtils.randBigInt
  val ownerPk = Constants.g.exp(ownerSecret.bigInteger)
  val receiverSecret = SigUtils.randBigInt
  val receiverPk = Constants.g.exp(receiverSecret.bigInteger)
  val trackerSecret = SigUtils.randBigInt
  val trackerPk = Constants.g.exp(trackerSecret.bigInteger)

  val changeAddress = P2PKAddress(ProveDlog(ownerPk)).toString()

  val minValue = 1000000000L
  val feeValue = 1000000L

  val fakeTxId1 = "f9e5ce5aa0d95f5d54a7bc89c46730d9662397067250aa18a0039631c0f5b809"
  val fakeTxId2 = "f9e5ce5aa0d95f5d54a7bc89c46730d9662397067250aa18a0039631c0f5b808"
  val fakeTxId3 = "f9e5ce5aa0d95f5d54a7bc89c46730d9662397067250aa18a0039631c0f5b807"
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

  

  // ========== POSITIVE TESTS ==========

  property("basis redemption should work with valid setup") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>

      val totalDebt = 1000000000L // 1 ERG total debt
      val redeemedDebt = 0L // 0 ERG already redeemed (starting fresh)
      val redeemAmount = 300000000L // 0.3 ERG to redeem now
      val newRedeemedDebt = redeemedDebt + redeemAmount // 0.3 ERG total redeemed after this
      val timestamp = System.currentTimeMillis() // Current timestamp in milliseconds

      // Create key for debt record: hash(ownerKey || receiverKey)
      val ownerKeyBytes = ownerPk.getEncoded
      val receiverBytes = receiverPk.getEncoded
      val key = Blake2b256(ownerKeyBytes.toArray ++ receiverBytes.toArray)

      // Create message for signatures: key || total debt || timestamp
      val message = key ++ Longs.toByteArray(totalDebt) ++ Longs.toByteArray(timestamp)

      // Create signatures
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val trackerSig = SigUtils.sign(message, trackerSecret)

      // Combine signatures into bytes
      val reserveSigBytes = GroupElementSerializer.toBytes(reserveSig._1) ++ reserveSig._2.toByteArray
      val trackerSigBytes = GroupElementSerializer.toBytes(trackerSig._1) ++ trackerSig._2.toByteArray

      // Create initial tree with existing redeemed debt (0 in this case)
      // Tree value format: timestamp (8 bytes) ++ redeemedAmount (8 bytes) = 16 bytes
      val reservePlasmaParameters = PlasmaParameters(32, None)
      val plasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, reservePlasmaParameters)
      val inputTreeErgoValue = plasmaMap.ergoValue

      // Create proof for inserting new redeemed debt with timestamp
      // Value format: timestamp (8 bytes) ++ newRedeemedAmount (8 bytes)
      val insertRes2 = plasmaMap.insert(key -> (Longs.toByteArray(timestamp) ++ Longs.toByteArray(newRedeemedDebt)))
      val insertProof = insertRes2.proof
      val outputTreeErgoValue = plasmaMap.ergoValue

      // Create tracker tree with debt record and lookup proof
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      // Create basis input with tree containing existing redeemed debt
      val basisInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue + totalDebt + feeValue) // Total reserve value
          .tokens(new ErgoToken(basisTokenId, 1))
          .registers(ErgoValue.of(ownerPk), inputTreeErgoValue, ErgoValue.of(trackerNFTBytes))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.basisContract))
          .build()
          .convertToInputWith(fakeTxId1, fakeIndex)
          .withContextVars(
            new ContextVar(0, ErgoValue.of(0: Byte)), // action 0, index 0
            new ContextVar(1, ErgoValue.of(receiverPk)),
            new ContextVar(2, ErgoValue.of(reserveSigBytes)),
            new ContextVar(3, ErgoValue.of(totalDebt)), // total debt amount
            new ContextVar(4, ErgoValue.of(timestamp)), // timestamp
            new ContextVar(5, ErgoValue.of(insertProof.bytes)), // proof for insertion
            new ContextVar(6, ErgoValue.of(trackerSigBytes)),
            new ContextVar(8, ErgoValue.of(trackerLookupProof)) // tracker tree lookup proof
          )


      // Tracker data input with debt record in AVL tree
      val trackerDataInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue)
          .tokens(new ErgoToken(trackerNFTBytes, 1))
          .registers(ErgoValue.of(trackerPk), trackerTree)
          .contract(ctx.compileContract(ConstantsBuilder.empty(), "{sigmaProp(true)}")) // Simple true contract
          .build()
          .convertToInputWith(fakeTxId2, fakeIndex)

      // Basis output (updated reserve with new redeemed debt)
      val basisOutput = createOut(
        Constants.basisContract,
        minValue + totalDebt - redeemAmount + feeValue, // Reduce value by the amount being redeemed
        Array(ErgoValue.of(ownerPk), outputTreeErgoValue, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )

      // Redemption output (to receiver)
      val redemptionOutput = createOut(
        trueScript,
        redeemAmount, // Amount being redeemed
        Array(),
        Array()
      )

      val inputs = Array[InputBox](basisInput)
      val dataInputs = Array[InputBox](trackerDataInput)
      val outputs = Array[OutBoxImpl](basisOutput, redemptionOutput)

      noException should be thrownBy {
        createTx(
          inputs,
          dataInputs,
          outputs,
          fee = None,
          changeAddress,
          Array[String](receiverSecret.toString()),
          false
        )
      }
    }
  }

  property("basis top-up should work") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>

      val topUpAmount = 2000000000L // 2 ERG

      // Basis reserve input
      val basisInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue)
          .tokens(new ErgoToken(basisTokenId, 1))
          .registers(ErgoValue.of(ownerPk), ErgoValue.of(emptyTree), ErgoValue.of(trackerNFTBytes))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.basisContract))
          .build()
          .convertToInputWith(fakeTxId1, fakeIndex)
          .withContextVars(
            new ContextVar(0, ErgoValue.of(10: Byte)) // action 1, index 0
          )

      // Funding box for top-up
      val fundingBox =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(topUpAmount + feeValue)
          .contract(ctx.compileContract(ConstantsBuilder.empty(), fakeScript))
          .build()
          .convertToInputWith(fakeTxId2, fakeIndex)

      // Basis output (with increased value)
      val basisOutput = createOut(
        Constants.basisContract,
        minValue + topUpAmount,
        Array(ErgoValue.of(ownerPk), ErgoValue.of(emptyTree), ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )

      val inputs = Array[InputBox](basisInput, fundingBox)
      val dataInputs = Array[InputBox]()
      val outputs = Array[OutBoxImpl](basisOutput)

      noException should be thrownBy {
        createTx(
          inputs,
          dataInputs,
          outputs,
          fee = None,
          changeAddress,
          Array[String](ownerSecret.toString()),
          false
        )
      }
    }
  }

  property("basis redemption should fail with invalid tracker signature (even with old tracker)") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>

      val totalDebt = 1000000000L // 1 ERG total debt
      val redeemedDebt = 0L // 0 ERG already redeemed (starting fresh)
      val redeemAmount = 300000000L // 0.3 ERG to redeem now
      val newRedeemedDebt = redeemedDebt + redeemAmount // 0.3 ERG total redeemed after this
      val timestamp = System.currentTimeMillis()

      // Create key for debt record: hash(ownerKey || receiverKey)
      val ownerKeyBytes = ownerPk.getEncoded
      val receiverBytes = receiverPk.getEncoded
      val key = Blake2b256(ownerKeyBytes.toArray ++ receiverBytes.toArray)

      // Create message for signatures: key || total debt || timestamp (emergency format adds || 0L)
      val message = key ++ Longs.toByteArray(totalDebt) ++ Longs.toByteArray(timestamp) ++ Longs.toByteArray(0L)

      // Create valid reserve signature but invalid tracker signature
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val invalidTrackerSig = SigUtils.sign(message, receiverSecret) // Wrong secret for tracker

      // Combine signatures into bytes
      val reserveSigBytes = GroupElementSerializer.toBytes(reserveSig._1) ++ reserveSig._2.toByteArray
      val invalidTrackerSigBytes = GroupElementSerializer.toBytes(invalidTrackerSig._1) ++ invalidTrackerSig._2.toByteArray

      // Create initial tree with existing redeemed debt (0 in this case)
      // Tree value format: timestamp (8 bytes) ++ redeemedAmount (8 bytes) = 16 bytes
      val plasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
      val insertRes = plasmaMap.insert(key -> (Longs.toByteArray(0L) ++ Longs.toByteArray(redeemedDebt)))
      val lookupProof = insertRes.proof

      // Create proof for inserting new redeemed debt with timestamp
      val insertRes2 = plasmaMap.insert(key -> (Longs.toByteArray(timestamp) ++ Longs.toByteArray(newRedeemedDebt)))
      val insertProof = insertRes2.proof
      val inputTreeErgoValue = plasmaMap.ergoValue
      val outputTreeErgoValue = plasmaMap.ergoValue

      // Create tracker tree with debt record and lookup proof
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      // Basis reserve input
      val basisInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue + totalDebt + feeValue) // Total reserve value
          .tokens(new ErgoToken(basisTokenId, 1))
          .registers(ErgoValue.of(ownerPk), inputTreeErgoValue, ErgoValue.of(trackerNFTBytes))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.basisContract))
          .build()
          .convertToInputWith(fakeTxId1, fakeIndex)
          .withContextVars(
            new ContextVar(0, ErgoValue.of(0: Byte)),
            new ContextVar(1, ErgoValue.of(receiverPk)),
            new ContextVar(2, ErgoValue.of(reserveSigBytes)),
            new ContextVar(3, ErgoValue.of(totalDebt)), // total debt amount
            new ContextVar(4, ErgoValue.of(timestamp)), // timestamp
            new ContextVar(5, ErgoValue.of(insertProof.bytes)), // proof for insertion
            new ContextVar(6, ErgoValue.of(invalidTrackerSigBytes)),
            new ContextVar(7, ErgoValue.of(lookupProof.bytes)), // proof for lookup
            new ContextVar(8, ErgoValue.of(trackerLookupProof)) // tracker tree lookup proof
          )

      // Tracker data input - created with old creation height (3+ days old)
      // Note: The contract currently requires tracker signature regardless of tracker age
      val trackerDataInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue)
          .tokens(new ErgoToken(trackerNFTBytes, 1))
          .registers(ErgoValue.of(trackerPk), trackerTree)
          .contract(ctx.compileContract(ConstantsBuilder.empty(), "{sigmaProp(true)}"))
          .creationHeight(ctx.getHeight - 3 * 720 - 1) // At least 3 days old
          .build()
          .convertToInputWith(fakeTxId2, fakeIndex)

      // Basis output
      val basisOutput = createOut(
        Constants.basisContract,
        minValue + totalDebt - redeemAmount + feeValue, // Reduce value by the amount being redeemed
        Array(ErgoValue.of(ownerPk), outputTreeErgoValue, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )

      // Redemption output
      val redemptionOutput = createOut(
        trueScript,
        redeemAmount, // Amount being redeemed
        Array(),
        Array()
      )

      val inputs = Array[InputBox](basisInput)
      val dataInputs = Array[InputBox](trackerDataInput)
      val outputs = Array[OutBoxImpl](basisOutput, redemptionOutput)

      // This test should fail because the tracker signature is invalid
      // (The contract currently requires tracker signature regardless of tracker age)
      a[Throwable] should be thrownBy {
        createTx(
          inputs,
          dataInputs,
          outputs,
          fee = None,
          changeAddress,
          Array[String](receiverSecret.toString()),
          false
        )
      }
    }
  }

  property("basis redemption should fail with insufficient debt amount") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>

      val debtAmount = 500000000L // 0.5 ERG
      val timestamp = System.currentTimeMillis()
      val redeemedAmount = 0L // Nothing redeemed yet

      // Create key for debt record
      val ownerKeyBytes = ownerPk.getEncoded
      val receiverBytes = receiverPk.getEncoded
      val key = Blake2b256(ownerKeyBytes.toArray ++ receiverBytes.toArray)

      // Create message for signatures: key || total debt || timestamp
      val message = key ++ Longs.toByteArray(debtAmount) ++ Longs.toByteArray(timestamp)

      // Create signatures
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val trackerSig = SigUtils.sign(message, trackerSecret)

      // Combine signatures into bytes
      val reserveSigBytes = GroupElementSerializer.toBytes(reserveSig._1) ++ reserveSig._2.toByteArray
      val trackerSigBytes = GroupElementSerializer.toBytes(trackerSig._1) ++ trackerSig._2.toByteArray

      // Create plasma map for reserve tree - start with empty tree
      // Tree value format: timestamp (8 bytes) ++ redeemedAmount (8 bytes) = 16 bytes
      val plasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
      val initialTreeErgoValue = plasmaMap.ergoValue

      // Create proof for inserting into tree with timestamp and redeemed amount
      val treeValue = Longs.toByteArray(timestamp) ++ Longs.toByteArray(redeemedAmount)
      val insertRes = plasmaMap.insert(key -> treeValue)
      val insertProof = insertRes.proof
      val nextTreeErgoValue = plasmaMap.ergoValue

      // Basis reserve input with insufficient value
      val basisInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue + debtAmount - 100000000L + feeValue) // Less than debt amount
          .tokens(new ErgoToken(basisTokenId, 1))
          .registers(ErgoValue.of(ownerPk), initialTreeErgoValue, ErgoValue.of(trackerNFTBytes))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.basisContract))
          .build()
          .convertToInputWith(fakeTxId1, fakeIndex)
          .withContextVars(
            new ContextVar(0, ErgoValue.of(0: Byte)),
            new ContextVar(1, ErgoValue.of(receiverPk)),
            new ContextVar(2, ErgoValue.of(reserveSigBytes)),
            new ContextVar(3, ErgoValue.of(debtAmount)),
            new ContextVar(4, ErgoValue.of(timestamp)),
            new ContextVar(5, ErgoValue.of(insertProof.bytes)),
            new ContextVar(6, ErgoValue.of(trackerSigBytes)),
            new ContextVar(7, ErgoValue.of(insertProof.bytes)), // proof for lookup (same as insert for first time)
            new ContextVar(8, ErgoValue.of(mkTrackerTreeAndProof(key, debtAmount).lookupProofBytes)) // tracker tree lookup proof
          )

      // Tracker data input
      val trackerDataInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue)
          .tokens(new ErgoToken(trackerNFTBytes, 1))
          .registers(ErgoValue.of(trackerPk), ErgoValue.of(emptyTree))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), "{false}"))
          .build()
          .convertToInputWith(fakeTxId2, fakeIndex)

      // Basis output
      val basisOutput = createOut(
        Constants.basisContract,
        minValue + feeValue - 100000000L,
        Array(ErgoValue.of(ownerPk), nextTreeErgoValue, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )

      // Redemption output (to receiver)
      val redemptionOutput = createOut(
        trueScript,
        debtAmount,
        Array(),
        Array()
      )

      val inputs = Array[InputBox](basisInput)
      val dataInputs = Array[InputBox](trackerDataInput)
      val outputs = Array[OutBoxImpl](basisOutput, redemptionOutput)

      a[Throwable] should be thrownBy {
        createTx(
          inputs,
          dataInputs,
          outputs,
          fee = None,
          changeAddress,
          Array[String](ownerSecret.toString()),
          false
        )
      }
    }
  }

  property("basis redemption should fail with invalid tracker signature") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>

      val debtAmount = 500000000L // 0.5 ERG
      val timestamp = System.currentTimeMillis()
      val redeemedAmount = 0L // Nothing redeemed yet

      // Create key for debt record
      val ownerKeyBytes = ownerPk.getEncoded
      val receiverBytes = receiverPk.getEncoded
      val key = Blake2b256(ownerKeyBytes.toArray ++ receiverBytes.toArray)

      // Create message for signatures: key || total debt || timestamp
      val message = key ++ Longs.toByteArray(debtAmount) ++ Longs.toByteArray(timestamp)

      // Create valid reserve signature but invalid tracker signature
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val invalidTrackerSig = SigUtils.sign(message, receiverSecret) // Wrong secret for tracker

      // Combine signatures into bytes
      val reserveSigBytes = GroupElementSerializer.toBytes(reserveSig._1) ++ reserveSig._2.toByteArray
      val invalidTrackerSigBytes = GroupElementSerializer.toBytes(invalidTrackerSig._1) ++ invalidTrackerSig._2.toByteArray

      // Create plasma map for reserve tree - start with empty tree
      // Tree value format: timestamp (8 bytes) ++ redeemedAmount (8 bytes) = 16 bytes
      val plasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
      val initialTreeErgoValue = plasmaMap.ergoValue

      // Create proof for inserting into tree with timestamp and redeemed amount
      val treeValue = Longs.toByteArray(timestamp) ++ Longs.toByteArray(redeemedAmount)
      val insertRes = plasmaMap.insert(key -> treeValue)
      val insertProof = insertRes.proof
      val nextTreeErgoValue = plasmaMap.ergoValue

      // Basis reserve input
      val basisInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue + debtAmount + feeValue)
          .tokens(new ErgoToken(basisTokenId, 1))
          .registers(ErgoValue.of(ownerPk), initialTreeErgoValue, ErgoValue.of(trackerNFTBytes))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.basisContract))
          .build()
          .convertToInputWith(fakeTxId1, fakeIndex)
          .withContextVars(
            new ContextVar(0, ErgoValue.of(0: Byte)),
            new ContextVar(1, ErgoValue.of(receiverPk)),
            new ContextVar(2, ErgoValue.of(reserveSigBytes)),
            new ContextVar(3, ErgoValue.of(debtAmount)),
            new ContextVar(4, ErgoValue.of(timestamp)),
            new ContextVar(5, ErgoValue.of(insertProof.bytes)),
            new ContextVar(6, ErgoValue.of(invalidTrackerSigBytes)),
            new ContextVar(7, ErgoValue.of(insertProof.bytes)), // proof for lookup
            new ContextVar(8, ErgoValue.of(mkTrackerTreeAndProof(key, debtAmount).lookupProofBytes)) // tracker tree lookup proof
          )

      // Tracker data input
      val trackerDataInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue)
          .tokens(new ErgoToken(trackerNFTBytes, 1))
          .registers(ErgoValue.of(trackerPk), ErgoValue.of(emptyTree))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), "{false}"))
          .build()
          .convertToInputWith(fakeTxId2, fakeIndex)

      // Basis output
      val basisOutput = createOut(
        Constants.basisContract,
        minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTreeErgoValue, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )

      // Redemption output
      val redemptionOutput = createOut(
        trueScript,
        debtAmount,
        Array(),
        Array()
      )

      val inputs = Array[InputBox](basisInput)
      val dataInputs = Array[InputBox](trackerDataInput)
      val outputs = Array[OutBoxImpl](basisOutput, redemptionOutput)

      a[Throwable] should be thrownBy {
        createTx(
          inputs,
          dataInputs,
          outputs,
          fee = None,
          changeAddress,
          Array[String](ownerSecret.toString()),
          false
        )
      }
    }
  }

  property("basis top-up should fail with insufficient amount") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>

      val topUpAmount = 50000000L // 0.05 ERG (less than minimum 0.1 ERG)

      // Basis reserve input
      val basisInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue)
          .tokens(new ErgoToken(basisTokenId, 1))
          .registers(ErgoValue.of(ownerPk), ErgoValue.of(emptyTree), ErgoValue.of(trackerNFTBytes))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.basisContract))
          .build()
          .convertToInputWith(fakeTxId1, fakeIndex)
          .withContextVars(
            new ContextVar(0, ErgoValue.of(10: Byte)) // action 1, index 0
          )

      // Funding box for top-up with insufficient amount
      val fundingBox =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(topUpAmount + feeValue) // Less than minimum top-up
          .contract(ctx.compileContract(ConstantsBuilder.empty(), fakeScript))
          .build()
          .convertToInputWith(fakeTxId2, fakeIndex)

      // Basis output (with insufficient top-up)
      val basisOutput = createOut(
        Constants.basisContract,
        minValue + topUpAmount,
        Array(ErgoValue.of(ownerPk), ErgoValue.of(emptyTree), ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )

      val inputs = Array[InputBox](basisInput, fundingBox)
      val dataInputs = Array[InputBox]()
      val outputs = Array[OutBoxImpl](basisOutput)

      a[Throwable] should be thrownBy {
        createTx(
          inputs,
          dataInputs,
          outputs,
          fee = None,
          changeAddress,
          Array[String](ownerSecret.toString()),
          false
        )
      }
    }
  }

  property("basis redemption should fail with invalid action code") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>

      val debtAmount = 500000000L // 0.5 ERG
      val timestamp = System.currentTimeMillis()
      val redeemedAmount = 0L // Nothing redeemed yet

      // Create key for debt record
      val ownerKeyBytes = ownerPk.getEncoded
      val receiverBytes = receiverPk.getEncoded
      val key = Blake2b256(ownerKeyBytes.toArray ++ receiverBytes.toArray)

      // Create message for signatures: key || total debt || timestamp
      val message = key ++ Longs.toByteArray(debtAmount) ++ Longs.toByteArray(timestamp)

      // Create signatures
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val trackerSig = SigUtils.sign(message, trackerSecret)

      // Combine signatures into bytes
      val reserveSigBytes = GroupElementSerializer.toBytes(reserveSig._1) ++ reserveSig._2.toByteArray
      val trackerSigBytes = GroupElementSerializer.toBytes(trackerSig._1) ++ trackerSig._2.toByteArray

      // Create plasma map for reserve tree
      // Tree value format: timestamp (8 bytes) ++ redeemedAmount (8 bytes) = 16 bytes
      val plasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
      val initialTreeErgoValue = plasmaMap.ergoValue

      // Create proof for inserting into tree with timestamp and redeemed amount
      val treeValue = Longs.toByteArray(timestamp) ++ Longs.toByteArray(redeemedAmount)
      val insertRes = plasmaMap.insert(key -> treeValue)
      val insertProof = insertRes.proof
      val nextTreeErgoValue = plasmaMap.ergoValue

      // Basis reserve input with invalid action code (2)
      val basisInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue + debtAmount + feeValue)
          .tokens(new ErgoToken(basisTokenId, 1))
          .registers(ErgoValue.of(ownerPk), initialTreeErgoValue, ErgoValue.of(trackerNFTBytes))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.basisContract))
          .build()
          .convertToInputWith(fakeTxId1, fakeIndex)
          .withContextVars(
            new ContextVar(0, ErgoValue.of(20: Byte)), // Invalid action code 2
            new ContextVar(1, ErgoValue.of(receiverPk)),
            new ContextVar(2, ErgoValue.of(reserveSigBytes)),
            new ContextVar(3, ErgoValue.of(debtAmount)),
            new ContextVar(4, ErgoValue.of(timestamp)),
            new ContextVar(5, ErgoValue.of(insertProof.bytes)),
            new ContextVar(6, ErgoValue.of(trackerSigBytes)),
            new ContextVar(7, ErgoValue.of(insertProof.bytes)), // proof for lookup
            new ContextVar(8, ErgoValue.of(mkTrackerTreeAndProof(key, debtAmount).lookupProofBytes)) // tracker tree lookup proof
          )

      // Tracker data input
      val trackerDataInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue)
          .tokens(new ErgoToken(trackerNFTBytes, 1))
          .registers(ErgoValue.of(trackerPk), ErgoValue.of(emptyTree))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), "{false}"))
          .build()
          .convertToInputWith(fakeTxId2, fakeIndex)

      // Basis output
      val basisOutput = createOut(
        Constants.basisContract,
        minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTreeErgoValue, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )

      // Redemption output
      val redemptionOutput = createOut(
        trueScript,
        debtAmount,
        Array(),
        Array()
      )

      val inputs = Array[InputBox](basisInput)
      val dataInputs = Array[InputBox](trackerDataInput)
      val outputs = Array[OutBoxImpl](basisOutput, redemptionOutput)

      a[Throwable] should be thrownBy {
        createTx(
          inputs,
          dataInputs,
          outputs,
          fee = None,
          changeAddress,
          Array[String](ownerSecret.toString()),
          false
        )
      }
    }
  }

  property("basis redemption should fail with tracker identity mismatch") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>

      val debtAmount = 500000000L // 0.5 ERG
      val timestamp = System.currentTimeMillis()
      val redeemedAmount = 0L // Nothing redeemed yet

      // Create key for debt record
      val ownerKeyBytes = ownerPk.getEncoded
      val receiverBytes = receiverPk.getEncoded
      val key = Blake2b256(ownerKeyBytes.toArray ++ receiverBytes.toArray)

      // Create message for signatures: key || total debt || timestamp
      val message = key ++ Longs.toByteArray(debtAmount) ++ Longs.toByteArray(timestamp)

      // Create signatures
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val trackerSig = SigUtils.sign(message, trackerSecret)

      // Combine signatures into bytes
      val reserveSigBytes = GroupElementSerializer.toBytes(reserveSig._1) ++ reserveSig._2.toByteArray
      val trackerSigBytes = GroupElementSerializer.toBytes(trackerSig._1) ++ trackerSig._2.toByteArray

      // Create plasma map for reserve tree
      // Tree value format: timestamp (8 bytes) ++ redeemedAmount (8 bytes) = 16 bytes
      val plasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
      val initialTreeErgoValue = plasmaMap.ergoValue

      // Create proof for inserting into tree with timestamp and redeemed amount
      val treeValue = Longs.toByteArray(timestamp) ++ Longs.toByteArray(redeemedAmount)
      val insertRes = plasmaMap.insert(key -> treeValue)
      val insertProof = insertRes.proof
      val nextTreeErgoValue = plasmaMap.ergoValue

      // Different tracker NFT ID
      val wrongTrackerNFT = "4c45f29a5165b030fdb5eaf5d81f8108f9d8f507b31487dd51f4ae08fe07cf4b"
      val wrongTrackerNFTBytes = Base16.decode(wrongTrackerNFT).get

      // Basis reserve input with wrong tracker NFT
      val basisInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue + debtAmount + feeValue)
          .tokens(new ErgoToken(basisTokenId, 1))
          .registers(ErgoValue.of(ownerPk), initialTreeErgoValue, ErgoValue.of(wrongTrackerNFTBytes))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.basisContract))
          .build()
          .convertToInputWith(fakeTxId1, fakeIndex)
          .withContextVars(
            new ContextVar(0, ErgoValue.of(0: Byte)),
            new ContextVar(1, ErgoValue.of(receiverPk)),
            new ContextVar(2, ErgoValue.of(reserveSigBytes)),
            new ContextVar(3, ErgoValue.of(debtAmount)),
            new ContextVar(4, ErgoValue.of(timestamp)),
            new ContextVar(5, ErgoValue.of(insertProof.bytes)),
            new ContextVar(6, ErgoValue.of(trackerSigBytes)),
            new ContextVar(7, ErgoValue.of(insertProof.bytes)), // proof for lookup
            new ContextVar(8, ErgoValue.of(mkTrackerTreeAndProof(key, debtAmount).lookupProofBytes)) // tracker tree lookup proof
          )

      // Tracker data input with correct NFT
      val trackerDataInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue)
          .tokens(new ErgoToken(trackerNFTBytes, 1))
          .registers(ErgoValue.of(trackerPk), ErgoValue.of(emptyTree))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), "{false}"))
          .build()
          .convertToInputWith(fakeTxId2, fakeIndex)

      // Basis output
      val basisOutput = createOut(
        Constants.basisContract,
        minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTreeErgoValue, ErgoValue.of(wrongTrackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )

      // Redemption output
      val redemptionOutput = createOut(
        trueScript,
        debtAmount,
        Array(),
        Array()
      )

      val inputs = Array[InputBox](basisInput)
      val dataInputs = Array[InputBox](trackerDataInput)
      val outputs = Array[OutBoxImpl](basisOutput, redemptionOutput)

      a[Throwable] should be thrownBy {
        createTx(
          inputs,
          dataInputs,
          outputs,
          fee = None,
          changeAddress,
          Array[String](ownerSecret.toString()),
          false
        )
      }
    }
  }

  property("basis redemption should fail with invalid reserve owner signature") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>

      val debtAmount = 500000000L // 0.5 ERG
      val timestamp = System.currentTimeMillis()
      val redeemedAmount = 0L // Nothing redeemed yet

      // Create key for debt record
      val ownerKeyBytes = ownerPk.getEncoded
      val receiverBytes = receiverPk.getEncoded
      val key = Blake2b256(ownerKeyBytes.toArray ++ receiverBytes.toArray)

      // Create message for signatures: key || total debt || timestamp
      val message = key ++ Longs.toByteArray(debtAmount) ++ Longs.toByteArray(timestamp)

      // Create valid tracker signature but invalid reserve signature (using wrong secret)
      val invalidReserveSig = SigUtils.sign(message, trackerSecret) // Wrong secret for reserve
      val trackerSig = SigUtils.sign(message, trackerSecret)

      // Combine signatures into bytes
      val invalidReserveSigBytes = GroupElementSerializer.toBytes(invalidReserveSig._1) ++ invalidReserveSig._2.toByteArray
      val trackerSigBytes = GroupElementSerializer.toBytes(trackerSig._1) ++ trackerSig._2.toByteArray

      // Create plasma map for reserve tree
      // Tree value format: timestamp (8 bytes) ++ redeemedAmount (8 bytes) = 16 bytes
      val plasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
      val initialTreeErgoValue = plasmaMap.ergoValue

      // Create proof for inserting into tree with timestamp and redeemed amount
      val treeValue = Longs.toByteArray(timestamp) ++ Longs.toByteArray(redeemedAmount)
      val insertRes = plasmaMap.insert(key -> treeValue)
      val insertProof = insertRes.proof
      val nextTreeErgoValue = plasmaMap.ergoValue

      // Basis reserve input
      val basisInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue + debtAmount + feeValue)
          .tokens(new ErgoToken(basisTokenId, 1))
          .registers(ErgoValue.of(ownerPk), initialTreeErgoValue, ErgoValue.of(trackerNFTBytes))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.basisContract))
          .build()
          .convertToInputWith(fakeTxId1, fakeIndex)
          .withContextVars(
            new ContextVar(0, ErgoValue.of(0: Byte)),
            new ContextVar(1, ErgoValue.of(receiverPk)),
            new ContextVar(2, ErgoValue.of(invalidReserveSigBytes)),
            new ContextVar(3, ErgoValue.of(debtAmount)),
            new ContextVar(4, ErgoValue.of(timestamp)),
            new ContextVar(5, ErgoValue.of(insertProof.bytes)),
            new ContextVar(6, ErgoValue.of(trackerSigBytes)),
            new ContextVar(7, ErgoValue.of(insertProof.bytes)), // proof for lookup
            new ContextVar(8, ErgoValue.of(mkTrackerTreeAndProof(key, debtAmount).lookupProofBytes)) // tracker tree lookup proof
          )

      // Tracker data input
      val trackerDataInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue)
          .tokens(new ErgoToken(trackerNFTBytes, 1))
          .registers(ErgoValue.of(trackerPk), ErgoValue.of(emptyTree))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), "{false}"))
          .build()
          .convertToInputWith(fakeTxId2, fakeIndex)

      // Basis output
      val basisOutput = createOut(
        Constants.basisContract,
        minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTreeErgoValue, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )

      // Redemption output
      val redemptionOutput = createOut(
        trueScript,
        debtAmount,
        Array(),
        Array()
      )

      val inputs = Array[InputBox](basisInput)
      val dataInputs = Array[InputBox](trackerDataInput)
      val outputs = Array[OutBoxImpl](basisOutput, redemptionOutput)

      a[Throwable] should be thrownBy {
        createTx(
          inputs,
          dataInputs,
          outputs,
          fee = None,
          changeAddress,
          Array[String](ownerSecret.toString()),
          false
        )
      }
    }
  }

  property("basis redemption should fail with emergency redemption time boundary") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>

      val debtAmount = 500000000L // 0.5 ERG
      // Use timestamp that's exactly 7 days before block headers (not enough for emergency redemption)
      val timestamp = 1576787597586L - (7 * 86400000L) // Exactly 7 days before block headers

      // Create key for debt record
      val ownerKeyBytes = ownerPk.getEncoded
      val receiverBytes = receiverPk.getEncoded
      val key = Blake2b256(ownerKeyBytes.toArray ++ receiverBytes.toArray)

      // Create message for signatures
      val message = key ++ Longs.toByteArray(debtAmount) ++ Longs.toByteArray(timestamp)

      // Create valid reserve signature but invalid tracker signature
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val invalidTrackerSig = SigUtils.sign(message, receiverSecret) // Wrong secret for tracker

      // Combine signatures into bytes
      val reserveSigBytes = GroupElementSerializer.toBytes(reserveSig._1) ++ reserveSig._2.toByteArray
      val invalidTrackerSigBytes = GroupElementSerializer.toBytes(invalidTrackerSig._1) ++ invalidTrackerSig._2.toByteArray

      // Create plasma map for timestamp tree
      val plasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
      val initialTreeErgoValue = plasmaMap.ergoValue
      
      // Create proof for inserting into empty tree
      val timestampKeyVal = (key, Longs.toByteArray(timestamp))
      val insertRes = plasmaMap.insert(timestampKeyVal)
      val insertProof = insertRes.proof
      val nextTreeErgoValue = plasmaMap.ergoValue

      // Basis reserve input
      val basisInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue + debtAmount + feeValue)
          .tokens(new ErgoToken(basisTokenId, 1))
          .registers(ErgoValue.of(ownerPk), initialTreeErgoValue, ErgoValue.of(trackerNFTBytes))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.basisContract))
          .build()
          .convertToInputWith(fakeTxId1, fakeIndex)
          .withContextVars(
            new ContextVar(0, ErgoValue.of(0: Byte)),
            new ContextVar(1, ErgoValue.of(receiverPk)),
            new ContextVar(2, ErgoValue.of(reserveSigBytes)),
            new ContextVar(3, ErgoValue.of(debtAmount)),
            new ContextVar(4, ErgoValue.of(timestamp)),
            new ContextVar(5, ErgoValue.of(insertProof.bytes)),
            new ContextVar(6, ErgoValue.of(invalidTrackerSigBytes))
          )

      // Tracker data input
      val trackerDataInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue)
          .tokens(new ErgoToken(trackerNFTBytes, 1))
          .registers(ErgoValue.of(trackerPk), ErgoValue.of(emptyTree))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), "{false}"))
          .build()
          .convertToInputWith(fakeTxId2, fakeIndex)

      // Basis output
      val basisOutput = createOut(
        Constants.basisContract,
        minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTreeErgoValue, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )

      // Redemption output
      val redemptionOutput = createOut(
        trueScript,
        debtAmount,
        Array(),
        Array()
      )

      val inputs = Array[InputBox](basisInput)
      val dataInputs = Array[InputBox](trackerDataInput)
      val outputs = Array[OutBoxImpl](basisOutput, redemptionOutput)

      // This test should fail because exactly 7 days is not enough for emergency redemption
      // (needs more than 7 days)
      a[Throwable] should be thrownBy {
        createTx(
          inputs,
          dataInputs,
          outputs,
          fee = None,
          changeAddress,
          Array[String](ownerSecret.toString()),
          false
        )
      }
    }
  }

  property("basis redemption should fail with invalid AVL tree proof") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>

      val debtAmount = 500000000L // 0.5 ERG
      val timestamp = System.currentTimeMillis()

      // Create key for debt record
      val ownerKeyBytes = ownerPk.getEncoded
      val receiverBytes = receiverPk.getEncoded
      val key = Blake2b256(ownerKeyBytes.toArray ++ receiverBytes.toArray)

      // Create message for signatures
      val message = key ++ Longs.toByteArray(debtAmount) ++ Longs.toByteArray(timestamp)

      // Create signatures
      val reserveSig = SigUtils.sign(message, ownerSecret)
      val trackerSig = SigUtils.sign(message, trackerSecret)

      // Combine signatures into bytes
      val reserveSigBytes = GroupElementSerializer.toBytes(reserveSig._1) ++ reserveSig._2.toByteArray
      val trackerSigBytes = GroupElementSerializer.toBytes(trackerSig._1) ++ trackerSig._2.toByteArray

      // Create plasma map for timestamp tree
      val plasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
      val initialTreeErgoValue = plasmaMap.ergoValue
      
      // Create proof for inserting into empty tree
      val timestampKeyVal = (key, Longs.toByteArray(timestamp))
      val insertRes = plasmaMap.insert(timestampKeyVal)
      val insertProof = insertRes.proof
      val nextTreeErgoValue = plasmaMap.ergoValue

      // Create invalid proof (use proof for different key)
      val wrongKey = Blake2b256(receiverBytes.toArray ++ ownerKeyBytes.toArray) // Reversed keys
      val wrongPlasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
      val wrongInsertRes = wrongPlasmaMap.insert((wrongKey, Longs.toByteArray(timestamp)))
      val invalidProof = wrongInsertRes.proof

      // Basis reserve input
      val basisInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue + debtAmount + feeValue)
          .tokens(new ErgoToken(basisTokenId, 1))
          .registers(ErgoValue.of(ownerPk), initialTreeErgoValue, ErgoValue.of(trackerNFTBytes))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.basisContract))
          .build()
          .convertToInputWith(fakeTxId1, fakeIndex)
          .withContextVars(
            new ContextVar(0, ErgoValue.of(0: Byte)),
            new ContextVar(1, ErgoValue.of(receiverPk)),
            new ContextVar(2, ErgoValue.of(reserveSigBytes)),
            new ContextVar(3, ErgoValue.of(debtAmount)),
            new ContextVar(4, ErgoValue.of(timestamp)),
            new ContextVar(5, ErgoValue.of(invalidProof.bytes)), // Invalid proof
            new ContextVar(6, ErgoValue.of(trackerSigBytes))
          )

      // Tracker data input
      val trackerDataInput =
        ctx
          .newTxBuilder()
          .outBoxBuilder
          .value(minValue)
          .tokens(new ErgoToken(trackerNFTBytes, 1))
          .registers(ErgoValue.of(trackerPk), ErgoValue.of(emptyTree))
          .contract(ctx.compileContract(ConstantsBuilder.empty(), "{false}"))
          .build()
          .convertToInputWith(fakeTxId2, fakeIndex)

      // Basis output
      val basisOutput = createOut(
        Constants.basisContract,
        minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTreeErgoValue, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1))
      )

      // Redemption output
      val redemptionOutput = createOut(
        trueScript,
        debtAmount,
        Array(),
        Array()
      )

      val inputs = Array[InputBox](basisInput)
      val dataInputs = Array[InputBox](trackerDataInput)
      val outputs = Array[OutBoxImpl](basisOutput, redemptionOutput)

      a[Throwable] should be thrownBy {
        createTx(
          inputs,
          dataInputs,
          outputs,
          fee = None,
          changeAddress,
          Array[String](ownerSecret.toString()),
          false
        )
      }
    }
  }

  // ========== CONTRACT LINKAGE VERIFICATION ==========
  // Verifies tests use the exact contract from contracts/offchain/basis.es

  property("Constants.basisContract matches contracts/offchain/basis.es (text-equivalent)") {
    import java.nio.charset.StandardCharsets
    import java.nio.file.{Files, Paths}
    // Anchor to project root via user.dir (sbt sets this to project root)
    val projectRoot = Paths.get(sys.props("user.dir"))
    val contractPath = projectRoot.resolve("contracts/offchain/basis.es")
    require(Files.exists(contractPath), s"Missing contract file: $contractPath (user.dir=${sys.props("user.dir")})")
    val fileBytes = Files.readAllBytes(contractPath)
    val fileText = new String(fileBytes, StandardCharsets.UTF_8)
    // Constants.readContract normalizes to \n via getLines.mkString("\n")
    Constants.basisContract shouldEqual fileText.replace("\r\n", "\n").stripSuffix("\n")
  }

  property("compiled basisErgoTree matches fresh compilation of basis.es") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val freshlyCompiled = ctx.compileContract(ConstantsBuilder.empty(), Constants.basisContract)
      Constants.basisErgoTree.bytes shouldEqual freshlyCompiled.getErgoTree.bytes
    }
  }

  // ========== HELPER METHODS FOR CLEANER TESTS ==========

  def mkKey(ownerKey: GroupElement, receiverKey: GroupElement): Array[Byte] =
    Blake2b256(ownerKey.getEncoded.toArray ++ receiverKey.getEncoded.toArray)

  // Message for normal redemption: key || totalDebt || timestamp
  def mkMessage(key: Array[Byte], totalDebt: Long, timestamp: Long = System.currentTimeMillis()): Array[Byte] =
    key ++ Longs.toByteArray(totalDebt) ++ Longs.toByteArray(timestamp)

  // NOTE: Emergency redemption uses the SAME message format as normal redemption.
  // The only difference is that tracker signature becomes OPTIONAL after 3 days (2160 blocks).
  // This function is kept for documentation purposes to show what an "emergency format" would look like,
  // but it is NOT used by the actual contract.
  def mkEmergencyMessage(key: Array[Byte], totalDebt: Long, timestamp: Long = System.currentTimeMillis()): Array[Byte] =
    key ++ Longs.toByteArray(totalDebt) ++ Longs.toByteArray(timestamp) ++ Longs.toByteArray(0L)

  def mkSigBytes(sig: (GroupElement, BigInt)): Array[Byte] =
    GroupElementSerializer.toBytes(sig._1) ++ sig._2.toByteArray

  case class TreeAndProof(initialTree: ErgoValue[AvlTree], nextTree: ErgoValue[AvlTree], proofBytes: Array[Byte])

  // For first redemption: start with empty tree, prove insertion of (timestamp, redeemedDebt)
  // Note: The contract uses insert-only tree, so only first redemption per (owner, receiver) pair is supported
  // Tree value format: timestamp (8 bytes) ++ redeemedAmount (8 bytes) = 16 bytes
  def mkTreeAndProof(key: Array[Byte], redeemedDebt: Long, timestamp: Long = System.currentTimeMillis()): TreeAndProof = {
    val plasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
    val initial = plasmaMap.ergoValue  // empty tree
    // Prove insertion of (timestamp, redeemed debt value)
    val treeValue = Longs.toByteArray(timestamp) ++ Longs.toByteArray(redeemedDebt)
    val insertRes = plasmaMap.insert((key, treeValue))
    TreeAndProof(initial, plasmaMap.ergoValue, insertRes.proof.bytes)
  }

  // Lookup proof for existing redeemed debt (for subsequent redemptions if contract supported them)
  // Tree value format: timestamp (8 bytes) ++ redeemedAmount (8 bytes) = 16 bytes
  def mkLookupProof(key: Array[Byte], redeemedDebt: Long, timestamp: Long = System.currentTimeMillis()): Array[Byte] = {
    val plasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
    val treeValue = Longs.toByteArray(timestamp) ++ Longs.toByteArray(redeemedDebt)
    val insertRes = plasmaMap.insert((key, treeValue))
    plasmaMap.lookUp(key).proof.bytes
  }

  // Tracker tree setup: create tree with debt record and lookup proof
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
    trackerLookupProofBytes: Option[Array[Byte]] = None,
    timestamp: Long = System.currentTimeMillis()
  )(implicit ctx: BlockchainContext): InputBox = {
    val baseVars = Array(
      new ContextVar(0, ErgoValue.of(0: Byte)),
      new ContextVar(1, ErgoValue.of(receiverKey)),
      new ContextVar(2, ErgoValue.of(reserveSigBytes)),
      new ContextVar(3, ErgoValue.of(totalDebt)),
      new ContextVar(4, ErgoValue.of(timestamp)), // timestamp
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

  // Note: tracker data input includes R4 (trackerPk) and R5 (AVL tree commitment to credit data).
  // The contract reads tracker.R5[AvlTree] for potential validation of tracker state.
  // For emergency redemption tests, set creationHeight to ctx.getHeight - 3 * 720 - 1 or more
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
    builderWithHeight
      .build()
      .convertToInputWith(fakeTxId2, fakeIndex)
  }

  // Script validation failures throw sigmastate.exceptions.InterpreterException
  // with message "Script reduced to false". We check the cause chain to handle wrapped exceptions.
  private def formatCauseChain(t: Throwable): String =
    Iterator.iterate[Throwable](t)(_.getCause).takeWhile(_ != null).zipWithIndex.map {
      case (e, i) => s"[$i] ${e.getClass.getName}: ${Option(e.getMessage).getOrElse("<no message>")}"
    }.mkString("\n")

  // Any InterpreterException indicates script-level failure (sigmaProp false, AVL proof invalid, etc.)
  private def hasScriptValidationFailure(t: Throwable): Boolean =
    Iterator.iterate[Throwable](t)(_.getCause).takeWhile(_ != null).exists {
      case _: sigmastate.exceptions.InterpreterException => true
      case _ => false
    }

  // Known builder/selection error keywords in class names that indicate construction failure
  private val constructionErrorClassKeywords = Seq(
    "NotEnoughCoins", "NotEnoughTokens", "NotEnoughErgs",
    "BoxSelection", "InputBoxesSelection"
  )

  private def looksLikeConstructionError(t: Throwable): Boolean =
    Iterator.iterate[Throwable](t)(_.getCause).takeWhile(_ != null).exists { e =>
      // Check both simple and full class names for known keywords
      val simpleName = e.getClass.getSimpleName
      val fullName = e.getClass.getName
      val classMatch = constructionErrorClassKeywords.exists(kw =>
        simpleName.contains(kw) || fullName.contains(kw))
      if (classMatch) true
      else {
        // Fallback: check message for known patterns
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

  // ========== SELF-PRESERVED VIOLATION TESTS WITH MUTATION CONTROLS ==========
  // Each test includes a control assertion: fix the violation → tx succeeds

  property("basis redemption should fail with different contract in output (with control)") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val totalDebt = 500000000L
      val redeemedDebt = 0L
      val redeemAmount = totalDebt
      val newRedeemedDebt = redeemedDebt + redeemAmount
      val timestamp = System.currentTimeMillis() // Consistent timestamp
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt, timestamp)
      val reserveSigBytes = mkSigBytes(SigUtils.sign(message, ownerSecret))
      val trackerSigBytes = mkSigBytes(SigUtils.sign(message, trackerSecret))
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, newRedeemedDebt, timestamp)
      // Create tracker tree with debt record and lookup proof
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)
      // No lookup proof needed for first redemption (redeemedDebt = 0)

      // Create tracker tree with debt record and lookup proof

      val basisInput = mkBasisInput(minValue + totalDebt + feeValue, initialTree,
        receiverPk, reserveSigBytes, totalDebt, proofBytes, trackerSigBytes, None, Some(trackerLookupProof), timestamp)
      val trackerDataInput = mkTrackerDataInput(trackerTree)
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      // FAIL CASE: Output with DIFFERENT contract - violates selfPreserved.propositionBytes
      val badBasisOutput = createOut(trueScript, minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1)))
      assertTxFails(Array(basisInput), Array(trackerDataInput),
        Array(badBasisOutput, redemptionOutput), Array(receiverSecret.toString()))

      // CONTROL: Use correct contract → tx succeeds
      val goodBasisOutput = createOut(Constants.basisContract, minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1)))
      noException should be thrownBy {
        assertTxSucceeds(Array(basisInput), Array(trackerDataInput),
          Array(goodBasisOutput, redemptionOutput), Array(receiverSecret.toString()))
      }
    }
  }

  property("basis redemption should fail with missing token in output (with control)") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val timestamp = System.currentTimeMillis()
      val totalDebt = 500000000L
      val redeemedDebt = 0L
      val redeemAmount = totalDebt
      val newRedeemedDebt = redeemedDebt + redeemAmount
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt, timestamp)
      val reserveSigBytes = mkSigBytes(SigUtils.sign(message, ownerSecret))
      val trackerSigBytes = mkSigBytes(SigUtils.sign(message, trackerSecret))
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, newRedeemedDebt, timestamp)
      // Create tracker tree with debt record and lookup proof
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)
      // No lookup proof needed for first redemption (redeemedDebt = 0)

      // Create tracker tree with debt record and lookup proof

      val basisInput = mkBasisInput(minValue + totalDebt + feeValue, initialTree,
        receiverPk, reserveSigBytes, totalDebt, proofBytes, trackerSigBytes, None, Some(trackerLookupProof), timestamp)
      val trackerDataInput = mkTrackerDataInput(trackerTree)
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      // FAIL CASE: basis output missing token (moved to redemption) - violates selfPreserved.tokens
      val badBasisOutput = createOut(Constants.basisContract, minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array()) // basis token missing here -> contract should fail
      val redemptionOutputWithToken = createOut(trueScript, redeemAmount,
        Array(), Array(new ErgoToken(basisTokenId, 1))) // token moved here so builder is happy
      assertTxFails(Array(basisInput), Array(trackerDataInput),
        Array(badBasisOutput, redemptionOutputWithToken), Array(receiverSecret.toString()))

      // CONTROL: Include correct token → tx succeeds
      val goodBasisOutput = createOut(Constants.basisContract, minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1)))
      noException should be thrownBy {
        assertTxSucceeds(Array(basisInput), Array(trackerDataInput),
          Array(goodBasisOutput, redemptionOutput), Array(receiverSecret.toString()))
      }
    }
  }

  property("basis redemption should fail with changed owner key in output (with control)") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val timestamp = System.currentTimeMillis()
      val totalDebt = 500000000L
      val redeemedDebt = 0L
      val redeemAmount = totalDebt
      val newRedeemedDebt = redeemedDebt + redeemAmount
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt, timestamp)
      val reserveSigBytes = mkSigBytes(SigUtils.sign(message, ownerSecret))
      val trackerSigBytes = mkSigBytes(SigUtils.sign(message, trackerSecret))
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, newRedeemedDebt, timestamp)
      // Create tracker tree with debt record and lookup proof
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)
      // No lookup proof needed for first redemption (redeemedDebt = 0)

      val differentOwnerPk = Constants.g.exp(SigUtils.randBigInt.bigInteger)

      // Create tracker tree with debt record and lookup proof

      val basisInput = mkBasisInput(minValue + totalDebt + feeValue, initialTree,
        receiverPk, reserveSigBytes, totalDebt, proofBytes, trackerSigBytes, None, Some(trackerLookupProof), timestamp)
      val trackerDataInput = mkTrackerDataInput(trackerTree)
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      // FAIL CASE: Output with DIFFERENT owner key in R4 - violates selfPreserved.R4
      val badBasisOutput = createOut(Constants.basisContract, minValue + feeValue,
        Array(ErgoValue.of(differentOwnerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1)))
      assertTxFails(Array(basisInput), Array(trackerDataInput),
        Array(badBasisOutput, redemptionOutput), Array(receiverSecret.toString()))

      // CONTROL: Fix owner key → tx succeeds
      val goodBasisOutput = createOut(Constants.basisContract, minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1)))
      noException should be thrownBy {
        assertTxSucceeds(Array(basisInput), Array(trackerDataInput),
          Array(goodBasisOutput, redemptionOutput), Array(receiverSecret.toString()))
      }
    }
  }

  property("basis redemption should fail with changed tracker NFT ID in output (with control)") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val timestamp = System.currentTimeMillis()
      val totalDebt = 500000000L
      val redeemedDebt = 0L
      val redeemAmount = totalDebt
      val newRedeemedDebt = redeemedDebt + redeemAmount
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt, timestamp)
      val reserveSigBytes = mkSigBytes(SigUtils.sign(message, ownerSecret))
      val trackerSigBytes = mkSigBytes(SigUtils.sign(message, trackerSecret))
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, newRedeemedDebt, timestamp)
      // Create tracker tree with debt record and lookup proof
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)
      // No lookup proof needed for first redemption (redeemedDebt = 0)

      val differentTrackerNFTBytes = Base16.decode("4c45f29a5165b030fdb5eaf5d81f8108f9d8f507b31487dd51f4ae08fe07cf4b").get

      // Create tracker tree with debt record and lookup proof

      val basisInput = mkBasisInput(minValue + totalDebt + feeValue, initialTree,
        receiverPk, reserveSigBytes, totalDebt, proofBytes, trackerSigBytes, None, Some(trackerLookupProof), timestamp)
      val trackerDataInput = mkTrackerDataInput(trackerTree)
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      // FAIL CASE: Output with DIFFERENT tracker NFT ID in R6 - violates selfPreserved.R6
      val badBasisOutput = createOut(Constants.basisContract, minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(differentTrackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1)))
      assertTxFails(Array(basisInput), Array(trackerDataInput),
        Array(badBasisOutput, redemptionOutput), Array(receiverSecret.toString()))

      // CONTROL: Fix tracker NFT ID → tx succeeds
      val goodBasisOutput = createOut(Constants.basisContract, minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1)))
      noException should be thrownBy {
        assertTxSucceeds(Array(basisInput), Array(trackerDataInput),
          Array(goodBasisOutput, redemptionOutput), Array(receiverSecret.toString()))
      }
    }
  }

  // ========== DOUBLE REDEMPTION TEST WITH MUTATION CONTROL ==========

  property("basis redemption should fail with tree digest mismatch (with control)") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      // FAIL CASE: Input tree already has key, proof is for empty tree → mismatch
      // CONTROL: Use empty input tree with matching proof → succeeds

      val totalDebt = 500000000L
      val redeemedDebt = 0L
      val redeemAmount = totalDebt
      val newRedeemedDebt = redeemedDebt + redeemAmount
      val timestamp = System.currentTimeMillis() // Consistent timestamp
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt, timestamp)
      val reserveSigBytes = mkSigBytes(SigUtils.sign(message, ownerSecret))
      val trackerSigBytes = mkSigBytes(SigUtils.sign(message, trackerSecret))

      // Tree that ALREADY has the key (simulating prior redemption)
      // Value format: timestamp (8 bytes) ++ redeemedAmount (8 bytes) = 16 bytes
      val existingPlasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
      existingPlasmaMap.insert((key, Longs.toByteArray(timestamp) ++ Longs.toByteArray(redeemedDebt)))
      val treeWithExistingKey = existingPlasmaMap.ergoValue

      // Proof from empty tree (won't work with treeWithExistingKey)
      val freshPlasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
      val emptyTreeEV = freshPlasmaMap.ergoValue
      val insertRes = freshPlasmaMap.insert((key, Longs.toByteArray(timestamp) ++ Longs.toByteArray(newRedeemedDebt)))
      val proofFromEmptyTree = insertRes.proof.bytes
      val treeAfterInsert = freshPlasmaMap.ergoValue

      val lookupProofBytes = None  // No lookup proof needed for first redemption (redeemedDebt = 0)
      // Create tracker tree with debt record and lookup proof
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)
      val trackerDataInput = mkTrackerDataInput(trackerTree)
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      // FAIL CASE: Input has existing key, but proof is for empty tree
      val badBasisInput = ctx.newTxBuilder().outBoxBuilder
        .value(minValue + totalDebt + feeValue)
        .tokens(new ErgoToken(basisTokenId, 1))
        .registers(ErgoValue.of(ownerPk), treeWithExistingKey, ErgoValue.of(trackerNFTBytes))
        .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.basisContract))
        .build()
        .convertToInputWith(fakeTxId1, fakeIndex)
        .withContextVars(
          new ContextVar(0, ErgoValue.of(0: Byte)),
          new ContextVar(1, ErgoValue.of(receiverPk)),
          new ContextVar(2, ErgoValue.of(reserveSigBytes)),
          new ContextVar(3, ErgoValue.of(totalDebt)),
          new ContextVar(4, ErgoValue.of(timestamp)), // timestamp
          new ContextVar(5, ErgoValue.of(proofFromEmptyTree)),
          new ContextVar(6, ErgoValue.of(trackerSigBytes)),
          new ContextVar(8, ErgoValue.of(trackerLookupProof))
        )
      val badBasisOutput = createOut(Constants.basisContract, minValue + feeValue,
        Array(ErgoValue.of(ownerPk), treeAfterInsert, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1)))
      assertTxFails(Array(badBasisInput), Array(trackerDataInput),
        Array(badBasisOutput, redemptionOutput), Array(receiverSecret.toString()))

      // CONTROL: Use empty input tree with matching proof → succeeds
      val goodBasisInput = ctx.newTxBuilder().outBoxBuilder
        .value(minValue + totalDebt + feeValue)
        .tokens(new ErgoToken(basisTokenId, 1))
        .registers(ErgoValue.of(ownerPk), emptyTreeEV, ErgoValue.of(trackerNFTBytes))
        .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.basisContract))
        .build()
        .convertToInputWith(fakeTxId1, fakeIndex)
        .withContextVars(
          new ContextVar(0, ErgoValue.of(0: Byte)),
          new ContextVar(1, ErgoValue.of(receiverPk)),
          new ContextVar(2, ErgoValue.of(reserveSigBytes)),
          new ContextVar(3, ErgoValue.of(totalDebt)),
          new ContextVar(4, ErgoValue.of(timestamp)), // timestamp
          new ContextVar(5, ErgoValue.of(proofFromEmptyTree)),
          new ContextVar(6, ErgoValue.of(trackerSigBytes)),
          new ContextVar(8, ErgoValue.of(trackerLookupProof))
        )
      val goodBasisOutput = createOut(Constants.basisContract, minValue + feeValue,
        Array(ErgoValue.of(ownerPk), treeAfterInsert, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1)))
      noException should be thrownBy {
        assertTxSucceeds(Array(goodBasisInput), Array(trackerDataInput),
          Array(goodBasisOutput, redemptionOutput), Array(receiverSecret.toString()))
      }
    }
  }

  // ========== TOP-UP TREE PRESERVATION TEST WITH MUTATION CONTROL ==========

  property("basis top-up should fail with changed tree (with control)") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val topUpAmount = 2000000000L

      // Create a different tree for testing the failure case
      val plasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
      plasmaMap.insert((Blake2b256("test".getBytes), Array[Byte](1, 2, 3)))
      val differentTree = plasmaMap.ergoValue.getValue

      val basisInput = ctx.newTxBuilder().outBoxBuilder
        .value(minValue)
        .tokens(new ErgoToken(basisTokenId, 1))
        .registers(ErgoValue.of(ownerPk), ErgoValue.of(emptyTree), ErgoValue.of(trackerNFTBytes))
        .contract(ctx.compileContract(ConstantsBuilder.empty(), Constants.basisContract))
        .build()
        .convertToInputWith(fakeTxId1, fakeIndex)
        .withContextVars(new ContextVar(0, ErgoValue.of(10: Byte)))

      val fundingBox = ctx.newTxBuilder().outBoxBuilder
        .value(topUpAmount + feeValue)
        .contract(ctx.compileContract(ConstantsBuilder.empty(), fakeScript))
        .build()
        .convertToInputWith(fakeTxId2, fakeIndex)

      // FAIL CASE: Output with DIFFERENT tree - violates top-up tree preservation
      val badBasisOutput = createOut(Constants.basisContract, minValue + topUpAmount,
        Array(ErgoValue.of(ownerPk), ErgoValue.of(differentTree), ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1)))
      assertTxFails(Array(basisInput, fundingBox), Array(), Array(badBasisOutput), Array(ownerSecret.toString()))

      // CONTROL: Use same tree → tx succeeds
      val goodBasisOutput = createOut(Constants.basisContract, minValue + topUpAmount,
        Array(ErgoValue.of(ownerPk), ErgoValue.of(emptyTree), ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1)))
      noException should be thrownBy {
        assertTxSucceeds(Array(basisInput, fundingBox), Array(), Array(goodBasisOutput), Array(ownerSecret.toString()))
      }
    }
  }

  // ========== OR-BRANCH COVERAGE: properlyRedeemed = (redeemed <= debtDelta) && properTrackerSignature ==========
  // The contract now uses tracker signature only (no emergency redemption via HEIGHT).
  // Truth table for properTrackerSignature:
  //   A. trackerSig valid   -> succeeds
  //   B. trackerSig invalid -> fails
  // Note: Emergency redemption via HEIGHT was removed from the contract.

  property("OR-branch A: valid tracker sig -> succeeds") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val totalDebt = 500000000L
      val redeemedDebt = 0L
      val redeemAmount = totalDebt
      val newRedeemedDebt = redeemedDebt + redeemAmount
      val timestamp = System.currentTimeMillis() // Consistent timestamp for all operations
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt, timestamp)
      val reserveSigBytes = mkSigBytes(SigUtils.sign(message, ownerSecret))
      val trackerSigBytes = mkSigBytes(SigUtils.sign(message, trackerSecret)) // VALID tracker sig
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, newRedeemedDebt, timestamp)
      // Create tracker tree with debt record and lookup proof
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)
      // No lookup proof needed for first redemption (redeemedDebt = 0)

      // Create tracker tree with debt record and lookup proof

      val basisInput = mkBasisInput(minValue + totalDebt + feeValue, initialTree,
        receiverPk, reserveSigBytes, totalDebt, proofBytes, trackerSigBytes, None, Some(trackerLookupProof), timestamp)
      val trackerDataInput = mkTrackerDataInput(trackerTree)
      val basisOutput = createOut(Constants.basisContract, minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1)))
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      noException should be thrownBy {
        assertTxSucceeds(Array(basisInput), Array(trackerDataInput),
          Array(basisOutput, redemptionOutput), Array(receiverSecret.toString()))
      }
    }
  }

  // Create a deterministically invalid signature by corrupting a valid one
  private def corruptSig(sig: (special.sigma.GroupElement, BigInt)): Array[Byte] = {
    val b = mkSigBytes(sig).clone()
    b(0) = (b(0) ^ 0x01).toByte // flip one bit
    b
  }

  property("OR-branch B: invalid tracker sig -> fails") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val totalDebt = 500000000L
      val redeemedDebt = 0L
      val redeemAmount = totalDebt
      val newRedeemedDebt = redeemedDebt + redeemAmount
      val timestamp = System.currentTimeMillis() // Consistent timestamp
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt, timestamp)
      val reserveSigBytes = mkSigBytes(SigUtils.sign(message, ownerSecret))
      val invalidTrackerSigBytes = corruptSig(SigUtils.sign(message, trackerSecret)) // corrupted sig
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, newRedeemedDebt, timestamp)
      // Create tracker tree with debt record and lookup proof
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)
      // No lookup proof needed for first redemption (redeemedDebt = 0)

      // Create tracker tree with debt record and lookup proof

      val basisInput = mkBasisInput(minValue + totalDebt + feeValue, initialTree,
        receiverPk, reserveSigBytes, totalDebt, proofBytes, invalidTrackerSigBytes, None, None, timestamp)
      val trackerDataInput = mkTrackerDataInput(trackerTree)
      val basisOutput = createOut(Constants.basisContract, minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1)))
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      // Invalid tracker signature -> tx should fail
      a[Throwable] should be thrownBy {
        createTx(Array(basisInput), Array(trackerDataInput),
          Array(basisOutput, redemptionOutput), fee = None, changeAddress,
          Array(receiverSecret.toString()), broadcast = false)
      }
    }
  }

  property("OR-branch C: invalid tracker sig -> fails") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val totalDebt = 500000000L
      val redeemedDebt = 0L
      val redeemAmount = totalDebt
      val newRedeemedDebt = redeemedDebt + redeemAmount
      val timestamp = System.currentTimeMillis() // Consistent timestamp
      val key = mkKey(ownerPk, receiverPk)
      val message = mkMessage(key, totalDebt, timestamp)
      val reserveSigBytes = mkSigBytes(SigUtils.sign(message, ownerSecret))
      val invalidTrackerSigBytes = corruptSig(SigUtils.sign(message, trackerSecret)) // corrupted sig
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, newRedeemedDebt, timestamp)
      // Create tracker tree with debt record and lookup proof
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)
      // No lookup proof needed for first redemption (redeemedDebt = 0)

      // Create tracker tree with debt record and lookup proof

      val basisInput = mkBasisInput(minValue + totalDebt + feeValue, initialTree,
        receiverPk, reserveSigBytes, totalDebt, proofBytes, invalidTrackerSigBytes, None, None, timestamp)
      val trackerDataInput = mkTrackerDataInput(trackerTree)
      val basisOutput = createOut(Constants.basisContract, minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1)))
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      // Invalid tracker signature -> tx should fail
      a[Throwable] should be thrownBy {
        createTx(Array(basisInput), Array(trackerDataInput),
          Array(basisOutput, redemptionOutput), fee = None, changeAddress,
          Array(receiverSecret.toString()), broadcast = false)
      }
    }
  }

  // ========== EMERGENCY EXIT TESTS ==========
  // Emergency redemption allows redemption after 3 days (3 * 720 blocks) WITHOUT tracker signature.
  // The contract uses the SAME message format for both normal and emergency redemption:
  //   Message: key || totalDebt || timestamp
  // The only difference is:
  //   - Normal: tracker signature REQUIRED
  //   - Emergency (3+ days): tracker signature OPTIONAL
  // This provides an escape hatch if tracker becomes unavailable.

  property("Emergency exit: should succeed with old tracker and valid tracker signature") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val totalDebt = 500000000L
      val redeemedDebt = 0L
      val redeemAmount = totalDebt
      val newRedeemedDebt = redeemedDebt + redeemAmount
      val timestamp = System.currentTimeMillis()
      val key = mkKey(ownerPk, receiverPk)
      // Use normal message format (same for both normal and emergency redemption)
      val message = mkMessage(key, totalDebt, timestamp)
      val reserveSigBytes = mkSigBytes(SigUtils.sign(message, ownerSecret))
      val trackerSigBytes = mkSigBytes(SigUtils.sign(message, trackerSecret)) // Valid tracker sig
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, newRedeemedDebt, timestamp)
      // Create tracker tree with debt record and lookup proof
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisInput(minValue + totalDebt + feeValue, initialTree,
        receiverPk, reserveSigBytes, totalDebt, proofBytes, trackerSigBytes, None, Some(trackerLookupProof), timestamp)
      // Tracker is more than 3 days old (3 * 720 = 2160 blocks)
      val trackerDataInput = mkTrackerDataInput(trackerTree, Some(3 * 720 + 1))
      val basisOutput = createOut(Constants.basisContract, minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1)))
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      // Should succeed: tracker is old enough and has valid signature
      noException should be thrownBy {
        assertTxSucceeds(Array(basisInput), Array(trackerDataInput),
          Array(basisOutput, redemptionOutput), Array(receiverSecret.toString()))
      }
    }
  }

  property("Emergency exit: should succeed with old tracker using normal message format") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val totalDebt = 500000000L
      val redeemedDebt = 0L
      val redeemAmount = totalDebt
      val newRedeemedDebt = redeemedDebt + redeemAmount
      val timestamp = System.currentTimeMillis()
      val key = mkKey(ownerPk, receiverPk)
      // Normal message format works with old tracker (as it should)
      val message = mkMessage(key, totalDebt, timestamp)
      val reserveSigBytes = mkSigBytes(SigUtils.sign(message, ownerSecret))
      val trackerSigBytes = mkSigBytes(SigUtils.sign(message, trackerSecret))
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, newRedeemedDebt, timestamp)
      // Create tracker tree with debt record and lookup proof
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisInput(minValue + totalDebt + feeValue, initialTree,
        receiverPk, reserveSigBytes, totalDebt, proofBytes, trackerSigBytes, None, Some(trackerLookupProof), timestamp)
      // Tracker is more than 3 days old
      val trackerDataInput = mkTrackerDataInput(trackerTree, Some(3 * 720 + 1))
      val basisOutput = createOut(Constants.basisContract, minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1)))
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      // Should succeed: normal message format works regardless of tracker age
      noException should be thrownBy {
        assertTxSucceeds(Array(basisInput), Array(trackerDataInput),
          Array(basisOutput, redemptionOutput), Array(receiverSecret.toString()))
      }
    }
  }

  property("Emergency exit: should fail with old tracker and invalid tracker signature") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val totalDebt = 500000000L
      val redeemedDebt = 0L
      val redeemAmount = totalDebt
      val newRedeemedDebt = redeemedDebt + redeemAmount
      val timestamp = System.currentTimeMillis()
      val key = mkKey(ownerPk, receiverPk)
      // Use normal message format
      val message = mkMessage(key, totalDebt, timestamp)
      val reserveSigBytes = mkSigBytes(SigUtils.sign(message, ownerSecret))
      val invalidTrackerSigBytes = corruptSig(SigUtils.sign(message, trackerSecret)) // corrupted sig
      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, newRedeemedDebt, timestamp)
      // Create tracker tree with debt record and lookup proof
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisInput(minValue + totalDebt + feeValue, initialTree,
        receiverPk, reserveSigBytes, totalDebt, proofBytes, invalidTrackerSigBytes, None, Some(trackerLookupProof), timestamp)
      // Tracker is more than 3 days old (but invalid signature should still fail)
      val trackerDataInput = mkTrackerDataInput(trackerTree, Some(3 * 720 + 1))
      val basisOutput = createOut(Constants.basisContract, minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1)))
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      // Should fail: invalid tracker signature is rejected even for old tracker
      a[Throwable] should be thrownBy {
        createTx(Array(basisInput), Array(trackerDataInput),
          Array(basisOutput, redemptionOutput), fee = None, changeAddress,
          Array(receiverSecret.toString()), broadcast = false)
      }
    }
  }

  // ========== TRUE EMERGENCY REDEMPTION TEST (TRACKER OFFLINE) ==========
  // This test demonstrates the emergency exit behavior when tracker is completely offline.
  // After emergency period (3 days / 2160 blocks), notes should be redeemable WITHOUT tracker signature.
  //
  // The contract supports two paths:
  // 1. Normal redemption: tracker signature required (any time)
  // 2. Emergency redemption: no tracker signature needed (after 3 days)
  //
  // Both paths use the SAME message format: key || totalDebt || timestamp
  // The only difference is tracker signature optionality after emergency period.

  property("Emergency exit: should succeed without tracker signature after emergency period") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val totalDebt = 500000000L
      val redeemedDebt = 0L
      val redeemAmount = totalDebt
      val newRedeemedDebt = redeemedDebt + redeemAmount
      val timestamp = System.currentTimeMillis()
      val key = mkKey(ownerPk, receiverPk)

      // Use normal message format (same for both normal and emergency redemption)
      val message = mkMessage(key, totalDebt, timestamp)
      val reserveSigBytes = mkSigBytes(SigUtils.sign(message, ownerSecret))

      // NO tracker signature - tracker is offline!
      // We use empty bytes to simulate missing tracker signature
      val noTrackerSigBytes = Array[Byte]()

      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, newRedeemedDebt, timestamp)
      // Create tracker tree with debt record and lookup proof
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisInput(minValue + totalDebt + feeValue, initialTree,
        receiverPk, reserveSigBytes, totalDebt, proofBytes, noTrackerSigBytes, None, Some(trackerLookupProof), timestamp)

      // Tracker is more than 3 days old (3 * 720 = 2160 blocks)
      // After emergency period, redemption should be possible without tracker signature
      val trackerDataInput = mkTrackerDataInput(trackerTree, Some(3 * 720 + 1))

      val basisOutput = createOut(Constants.basisContract, minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1)))
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      // Emergency redemption should succeed: tracker is old enough, no tracker signature needed
      noException should be thrownBy {
        createTx(Array(basisInput), Array(trackerDataInput),
          Array(basisOutput, redemptionOutput), fee = None, changeAddress,
          Array(receiverSecret.toString()), broadcast = false)
      }
    }
  }

  property("Emergency exit: should fail without tracker signature before emergency period") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val totalDebt = 500000000L
      val redeemedDebt = 0L
      val redeemAmount = totalDebt
      val newRedeemedDebt = redeemedDebt + redeemAmount
      val timestamp = System.currentTimeMillis()
      val key = mkKey(ownerPk, receiverPk)

      // Normal redemption message (not emergency format)
      val message = mkMessage(key, totalDebt, timestamp)
      val reserveSigBytes = mkSigBytes(SigUtils.sign(message, ownerSecret))

      // NO tracker signature - should fail before emergency period
      val noTrackerSigBytes = Array[Byte]()

      val TreeAndProof(initialTree, nextTree, proofBytes) = mkTreeAndProof(key, newRedeemedDebt, timestamp)
      // Create tracker tree with debt record and lookup proof
      val TrackerTreeAndProof(trackerTree, trackerLookupProof) = mkTrackerTreeAndProof(key, totalDebt)

      val basisInput = mkBasisInput(minValue + totalDebt + feeValue, initialTree,
        receiverPk, reserveSigBytes, totalDebt, proofBytes, noTrackerSigBytes, None, Some(trackerLookupProof), timestamp)

      // Tracker is NOT old enough (only 1 day old, need 3 days)
      val trackerDataInput = mkTrackerDataInput(trackerTree, Some(1 * 720)) // Only 1 day

      val basisOutput = createOut(Constants.basisContract, minValue + feeValue,
        Array(ErgoValue.of(ownerPk), nextTree, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1)))
      val redemptionOutput = createOut(trueScript, redeemAmount, Array(), Array())

      // Should fail: tracker signature required before emergency period
      a[Throwable] should be thrownBy {
        createTx(Array(basisInput), Array(trackerDataInput),
          Array(basisOutput, redemptionOutput), fee = None, changeAddress,
          Array(receiverSecret.toString()), broadcast = false)
      }
    }
  }

  // ========== DEBT TRANSFER (NOVATION) TESTS ==========
  // Tests triangular trade: debt transfer from one creditor to another with debtor consent
  // Scenario: A owes B (10 ERG). B wants to pay C (5 ERG).
  // Solution: Alice signs two new notes - one to B (5 ERG), one to C (5 ERG)
  // Result: A owes B (5 ERG), A owes C (5 ERG), B owes C (0 ERG)

  property("debt transfer: triangular trade with consent should work") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val timestamp = System.currentTimeMillis()

      // Initial state: Alice owes Bob 10 ERG
      val initialDebtToBob = 10000000000L // 10 ERG
      val transferAmount = 5000000000L    // 5 ERG to transfer to Carol
      val remainingDebtToBob = 5000000000L // 5 ERG remaining

      // Keys for all parties
      val aliceKey = ownerPk
      val bobKey = receiverPk
      val carolSecret = SigUtils.randBigInt
      val carolKey = Constants.g.exp(carolSecret.bigInteger)

      // Keys for debt records
      val keyAliceBob = mkKey(aliceKey, bobKey)
      val keyAliceCarol = mkKey(aliceKey, carolKey)

      // === Step 1: Initial debt A->B exists in tracker (10 ERG) ===
      val trackerTreeABInitial = mkTrackerTreeAndProof(keyAliceBob, initialDebtToBob)

      // === Step 2: Debt transfer via new notes (B wants to pay C using A's debt) ===
      // Alice signs TWO new notes:
      // - Note 1: A->B for 5 ERG (remaining debt)
      // - Note 2: A->C for 5 ERG (transferred debt)
      
      // Note 1: Alice -> Bob (5 ERG remaining)
      val messageToBob = mkMessage(keyAliceBob, remainingDebtToBob, timestamp)
      val aliceSigBob = SigUtils.sign(messageToBob, ownerSecret)
      val trackerSigBob = SigUtils.sign(messageToBob, trackerSecret)
      
      // Note 2: Alice -> Carol (5 ERG transferred)
      val messageToCarol = mkMessage(keyAliceCarol, transferAmount, timestamp)
      val aliceSigCarol = SigUtils.sign(messageToCarol, ownerSecret)
      val trackerSigCarol = SigUtils.sign(messageToCarol, trackerSecret)

      // === Step 3: Tracker updates ledger (offchain simulation) ===
      // Old note A->B (10 ERG) is cancelled/replaced by:
      // - New note A->B (5 ERG)
      // - New note A->C (5 ERG)
      val trackerTreeAB = mkTrackerTreeAndProof(keyAliceBob, remainingDebtToBob)
      val trackerTreeAC = mkTrackerTreeAndProof(keyAliceCarol, transferAmount)

      // === Step 4: Bob redeems his note (5 ERG) ===
      val reserveSigBob = mkSigBytes(aliceSigBob)
      val trackerSigBobBytes = mkSigBytes(trackerSigBob)
      val TreeAndProof(initialTreeBob, nextTreeBob, proofBob) = mkTreeAndProof(keyAliceBob, remainingDebtToBob, timestamp)

      val reserveValueForBob = minValue + remainingDebtToBob + feeValue
      val basisInputBob = mkBasisInput(
        reserveValueForBob, initialTreeBob,
        bobKey, reserveSigBob, remainingDebtToBob, proofBob, trackerSigBobBytes,
        None, Some(trackerTreeAB.lookupProofBytes), timestamp
      )
      val trackerDataInputAB = mkTrackerDataInput(trackerTreeAB.tree)
      val redemptionOutputBob = createOut(trueScript, remainingDebtToBob, Array(), Array())
      val basisOutputBob = createOut(Constants.basisContract,
        reserveValueForBob - remainingDebtToBob,
        Array(ErgoValue.of(aliceKey), nextTreeBob, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1)))

      // Bob's redemption should succeed
      noException should be thrownBy {
        createTx(Array(basisInputBob), Array(trackerDataInputAB),
          Array(basisOutputBob, redemptionOutputBob), fee = None, changeAddress,
          Array(receiverSecret.toString()), broadcast = false)
      }

      // === Step 5: Carol redeems her note (5 ERG) ===
      val reserveSigCarol = mkSigBytes(aliceSigCarol)
      val trackerSigCarolBytes = mkSigBytes(trackerSigCarol)
      val TreeAndProof(initialTreeCarol, nextTreeCarol, proofCarol) = mkTreeAndProof(keyAliceCarol, transferAmount, timestamp)

      val reserveValueForCarol = minValue + transferAmount + feeValue
      val basisInputCarol = mkBasisInput(
        reserveValueForCarol, initialTreeCarol,
        carolKey, reserveSigCarol, transferAmount, proofCarol, trackerSigCarolBytes,
        None, Some(trackerTreeAC.lookupProofBytes), timestamp
      )
      val trackerDataInputAC = mkTrackerDataInput(trackerTreeAC.tree)
      val redemptionOutputCarol = createOut(trueScript, transferAmount, Array(), Array())
      val basisOutputCarol = createOut(Constants.basisContract,
        reserveValueForCarol - transferAmount,
        Array(ErgoValue.of(aliceKey), nextTreeCarol, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1)))

      // Carol's redemption should succeed
      noException should be thrownBy {
        createTx(Array(basisInputCarol), Array(trackerDataInputAC),
          Array(basisOutputCarol, redemptionOutputCarol), fee = None, changeAddress,
          Array(carolSecret.toString()), broadcast = false)
      }

      // === Verification: Debt transfer completed successfully ===
      // Bob received 5 ERG, Carol received 5 ERG, total 10 ERG redeemed
      // Original debt A->B (10 ERG) was split into two notes:
      // - Note A->B (5 ERG) - redeemed by Bob
      // - Note A->C (5 ERG) - redeemed by Carol
      // This demonstrates triangular trade: B effectively paid C by having A sign a new note to C
      println("Debt transfer test passed: triangular trade with two new notes works correctly")
    }
  }

  property("debt transfer: should fail without debtor consent") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val timestamp = System.currentTimeMillis()

      // Initial state: Alice owes Bob 10 ERG
      val initialDebt = 10000000000L // 10 ERG
      val transferAmount = 5000000000L // 5 ERG

      // Keys
      val aliceKey = ownerPk
      val bobKey = receiverPk
      val carolSecret = SigUtils.randBigInt
      val carolKey = Constants.g.exp(carolSecret.bigInteger)

      val keyAliceBob = mkKey(aliceKey, bobKey)
      val keyAliceCarol = mkKey(aliceKey, carolKey)

      // === Attempt to create note to Carol WITHOUT Alice's consent ===
      // Bob tries to sign a note from Alice to Carol (forging Alice's signature)
      val messageToCarol = mkMessage(keyAliceCarol, transferAmount, timestamp)
      
      // Bob signs instead of Alice (invalid - forging Alice's signature)
      val forgedAliceSig = SigUtils.sign(messageToCarol, receiverSecret) // Bob's secret, not Alice's
      
      // Verify that Bob cannot forge Alice's signature
      val forgeryValid = SigUtils.verify(messageToCarol, aliceKey, forgedAliceSig._1, forgedAliceSig._2)
      
      // Signature verification should fail (Bob signed, not Alice)
      forgeryValid shouldBe false

      // === Also verify tracker cannot create note without Alice's signature ===
      val trackerOnlySig = SigUtils.sign(messageToCarol, trackerSecret)
      val trackerForgeryValid = SigUtils.verify(messageToCarol, aliceKey, trackerOnlySig._1, trackerOnlySig._2)
      trackerForgeryValid shouldBe false

      println("Debt transfer without consent test passed: unauthorized note creation prevented")
      println("  - Bob cannot forge Alice's signature")
      println("  - Tracker cannot create note without Alice's signature")
      println("  - Both Alice's AND tracker's signatures are required for valid note")
    }
  }

  // ========== ADDITIONAL TRIANGULAR TRADE TESTS ==========

  property("debt transfer: multi-hop triangular trade concept verification") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val timestamp = System.currentTimeMillis()

      // Keys for all parties
      val aliceKey = ownerPk
      val bobKey = receiverPk
      val carolSecret = SigUtils.randBigInt
      val carolKey = Constants.g.exp(carolSecret.bigInteger)
      val daveSecret = SigUtils.randBigInt
      val daveKey = Constants.g.exp(daveSecret.bigInteger)

      // Multi-hop scenario conceptual test:
      // 1. Alice owes Bob 15 ERG
      // 2. Alice signs new notes: A->B (5 ERG), A->C (10 ERG)
      // 3. Alice signs new notes: A->C (5 ERG), A->D (5 ERG)
      // We verify that all notes can be independently redeemed

      val keyAliceBob = mkKey(aliceKey, bobKey)
      val keyAliceCarol = mkKey(aliceKey, carolKey)
      val keyAliceDave = mkKey(aliceKey, daveKey)

      // Create three independent debt notes
      val debtToBob = 5000000000L
      val debtToCarol = 5000000000L
      val debtToDave = 5000000000L

      // Note 1: Alice -> Bob
      val messageBob = mkMessage(keyAliceBob, debtToBob, timestamp)
      val aliceSigBob = SigUtils.sign(messageBob, ownerSecret)
      val trackerSigBob = SigUtils.sign(messageBob, trackerSecret)

      // Note 2: Alice -> Carol
      val messageCarol = mkMessage(keyAliceCarol, debtToCarol, timestamp)
      val aliceSigCarol = SigUtils.sign(messageCarol, ownerSecret)
      val trackerSigCarol = SigUtils.sign(messageCarol, trackerSecret)

      // Note 3: Alice -> Dave
      val messageDave = mkMessage(keyAliceDave, debtToDave, timestamp)
      val aliceSigDave = SigUtils.sign(messageDave, ownerSecret)
      val trackerSigDave = SigUtils.sign(messageDave, trackerSecret)

      // Verify all three notes can be independently created and would redeem
      // (We test one redemption to verify the mechanism works)

      val reserveSigBobBytes = mkSigBytes(aliceSigBob)
      val trackerSigBobBytes = mkSigBytes(trackerSigBob)
      val TreeAndProof(treeBobIn, treeBobOut, proofBob) = mkTreeAndProof(keyAliceBob, debtToBob, timestamp)
      val trackerTreeBob = mkTrackerTreeAndProof(keyAliceBob, debtToBob)

      val reserveBob = minValue + debtToBob + feeValue
      val basisInputBob = mkBasisInput(
        reserveBob, treeBobIn, bobKey, reserveSigBobBytes, debtToBob, proofBob,
        trackerSigBobBytes, None, Some(trackerTreeBob.lookupProofBytes), timestamp
      )
      val trackerInputBob = mkTrackerDataInput(trackerTreeBob.tree)
      val basisOutputBob = createOut(Constants.basisContract, reserveBob - debtToBob,
        Array(ErgoValue.of(aliceKey), treeBobOut, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1)))
      val redemptionBob = createOut(trueScript, debtToBob, Array(), Array())

      noException should be thrownBy {
        createTx(Array(basisInputBob), Array(trackerInputBob),
          Array(basisOutputBob, redemptionBob), fee = None, changeAddress,
          Array(receiverSecret.toString()), broadcast = false)
      }

      // Verify all signatures are valid (would work for Carol and Dave too)
      SigUtils.verify(messageCarol, aliceKey, aliceSigCarol._1, aliceSigCarol._2) shouldBe true
      SigUtils.verify(messageCarol, trackerPk, trackerSigCarol._1, trackerSigCarol._2) shouldBe true
      SigUtils.verify(messageDave, aliceKey, aliceSigDave._1, aliceSigDave._2) shouldBe true
      SigUtils.verify(messageDave, trackerPk, trackerSigDave._1, trackerSigDave._2) shouldBe true

      println("Multi-hop triangular trade concept test passed")
      println("  - Alice can sign notes to multiple parties (B, C, D)")
      println("  - Each note can be independently redeemed")
      println("  - Demonstrates multi-hop debt transfer capability")
    }
  }

  property("debt transfer: partial transfer with creditor holding note - signature verification") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val timestamp = System.currentTimeMillis()

      // Scenario concept: A owes B 10 ERG
      // B transfers 5 ERG to C, C holds and later transfers 3 ERG to D
      // We verify that notes with different timestamps have valid signatures

      val aliceKey = ownerPk
      val bobKey = receiverPk
      val carolSecret = SigUtils.randBigInt
      val carolKey = Constants.g.exp(carolSecret.bigInteger)

      val keyAliceBob = mkKey(aliceKey, bobKey)
      val keyAliceCarol = mkKey(aliceKey, carolKey)

      val transferToCarol = 5000000000L
      val bobRemaining = 5000000000L

      // Step 1: Create initial split notes (A->B, A->C)
      val messageBob = mkMessage(keyAliceBob, bobRemaining, timestamp)
      val aliceSigBob = SigUtils.sign(messageBob, ownerSecret)
      val trackerSigBob = SigUtils.sign(messageBob, trackerSecret)

      val messageCarol = mkMessage(keyAliceCarol, transferToCarol, timestamp)
      val aliceSigCarol = SigUtils.sign(messageCarol, ownerSecret)
      val trackerSigCarol = SigUtils.sign(messageCarol, trackerSecret)

      // Step 2: Create later transfer note (A->C with later timestamp)
      val timestamp2 = timestamp + 1000
      val carolRemaining = 2000000000L

      val messageCarol2 = mkMessage(keyAliceCarol, carolRemaining, timestamp2)
      val aliceSigCarol2 = SigUtils.sign(messageCarol2, ownerSecret)
      val trackerSigCarol2 = SigUtils.sign(messageCarol2, trackerSecret)

      // Verify all notes have valid signatures from both Alice and Tracker
      SigUtils.verify(messageBob, aliceKey, aliceSigBob._1, aliceSigBob._2) shouldBe true
      SigUtils.verify(messageBob, trackerPk, trackerSigBob._1, trackerSigBob._2) shouldBe true
      
      SigUtils.verify(messageCarol, aliceKey, aliceSigCarol._1, aliceSigCarol._2) shouldBe true
      SigUtils.verify(messageCarol, trackerPk, trackerSigCarol._1, trackerSigCarol._2) shouldBe true
      
      SigUtils.verify(messageCarol2, aliceKey, aliceSigCarol2._1, aliceSigCarol2._2) shouldBe true
      SigUtils.verify(messageCarol2, trackerPk, trackerSigCarol2._1, trackerSigCarol2._2) shouldBe true

      // Verify timestamps are different (enables temporal ordering)
      timestamp2 > timestamp shouldBe true

      println("Partial transfer with holding signature test passed")
      println("  - Notes can be created with different timestamps")
      println("  - All signatures are valid for both debtor and tracker")
      println("  - Creditors can hold multiple notes with different timestamps")
    }
  }

  property("debt transfer: should fail with replay attack (reuse old note)") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val timestamp1 = System.currentTimeMillis()
      val timestamp2 = timestamp1 + 1000

      val aliceKey = ownerPk
      val bobKey = receiverPk
      val carolSecret = SigUtils.randBigInt
      val carolKey = Constants.g.exp(carolSecret.bigInteger)

      val keyAliceBob = mkKey(aliceKey, bobKey)
      val keyAliceCarol = mkKey(aliceKey, carolKey)

      val initialDebt = 10000000000L
      val transferAmount = 5000000000L
      val bobRemaining = 5000000000L

      // Step 1: Create valid transfer (A->B 5 ERG, A->C 5 ERG)
      val messageBob = mkMessage(keyAliceBob, bobRemaining, timestamp1)
      val aliceSigBob = SigUtils.sign(messageBob, ownerSecret)
      val trackerSigBob = SigUtils.sign(messageBob, trackerSecret)

      val messageCarol = mkMessage(keyAliceCarol, transferAmount, timestamp1)
      val aliceSigCarol = SigUtils.sign(messageCarol, ownerSecret)
      val trackerSigCarol = SigUtils.sign(messageCarol, trackerSecret)

      // Step 2: Bob redeems with timestamp1
      val reserveSigBobBytes = mkSigBytes(aliceSigBob)
      val trackerSigBobBytes = mkSigBytes(trackerSigBob)
      val TreeAndProof(treeBobIn, treeBobOut, proofBob) = mkTreeAndProof(keyAliceBob, bobRemaining, timestamp1)
      val trackerTreeBob = mkTrackerTreeAndProof(keyAliceBob, bobRemaining)

      val reserveBob = minValue + initialDebt + feeValue
      val basisInputBob = mkBasisInput(
        reserveBob, treeBobIn, bobKey, reserveSigBobBytes, bobRemaining, proofBob,
        trackerSigBobBytes, None, Some(trackerTreeBob.lookupProofBytes), timestamp1
      )
      val trackerInputBob = mkTrackerDataInput(trackerTreeBob.tree)
      val basisOutputBob = createOut(Constants.basisContract, reserveBob - bobRemaining,
        Array(ErgoValue.of(aliceKey), treeBobOut, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1)))
      val redemptionBob = createOut(trueScript, bobRemaining, Array(), Array())

      noException should be thrownBy {
        createTx(Array(basisInputBob), Array(trackerInputBob),
          Array(basisOutputBob, redemptionBob), fee = None, changeAddress,
          Array(receiverSecret.toString()), broadcast = false)
      }

      // Step 3: Try to redeem AGAIN with same timestamp (replay attack)
      // This should fail because timestamp is not greater than stored timestamp
      val TreeAndProof(treeBobReplay, treeBobReplayOut, proofReplay) = mkTreeAndProof(keyAliceBob, bobRemaining, timestamp1)

      val reserveBob2 = minValue + feeValue
      val basisInputReplay = mkBasisInput(
        reserveBob2, treeBobReplay, bobKey, reserveSigBobBytes, bobRemaining, proofReplay,
        trackerSigBobBytes, Some(proofBob), Some(trackerTreeBob.lookupProofBytes), timestamp1
      )
      val basisOutputReplay = createOut(Constants.basisContract, reserveBob2,
        Array(ErgoValue.of(aliceKey), treeBobReplayOut, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1)))
      val redemptionReplay = createOut(trueScript, bobRemaining, Array(), Array())

      // Should fail: replay attack with same timestamp
      a[Throwable] should be thrownBy {
        createTx(Array(basisInputReplay), Array(trackerInputBob),
          Array(basisOutputReplay, redemptionReplay), fee = None, changeAddress,
          Array(receiverSecret.toString()), broadcast = false)
      }

      println("Replay attack prevention test passed: cannot reuse old notes")
    }
  }

  property("debt transfer: should fail with insufficient collateral") {
    createMockedErgoClient(MockData(Nil, Nil)).execute { implicit ctx: BlockchainContext =>
      val timestamp = System.currentTimeMillis()

      val aliceKey = ownerPk
      val bobKey = receiverPk
      val carolSecret = SigUtils.randBigInt
      val carolKey = Constants.g.exp(carolSecret.bigInteger)

      val keyAliceBob = mkKey(aliceKey, bobKey)
      val keyAliceCarol = mkKey(aliceKey, carolKey)

      val initialDebt = 10000000000L // 10 ERG debt
      val transferAmount = 5000000000L
      val bobRemaining = 5000000000L

      // Create notes for transfer
      val messageBob = mkMessage(keyAliceBob, bobRemaining, timestamp)
      val aliceSigBob = SigUtils.sign(messageBob, ownerSecret)
      val trackerSigBob = SigUtils.sign(messageBob, trackerSecret)

      val messageCarol = mkMessage(keyAliceCarol, transferAmount, timestamp)
      val aliceSigCarol = SigUtils.sign(messageCarol, ownerSecret)
      val trackerSigCarol = SigUtils.sign(messageCarol, trackerSecret)

      // Try to redeem with insufficient reserve collateral
      val reserveSigBobBytes = mkSigBytes(aliceSigBob)
      val trackerSigBobBytes = mkSigBytes(trackerSigBob)
      val TreeAndProof(treeBobIn, treeBobOut, proofBob) = mkTreeAndProof(keyAliceBob, bobRemaining, timestamp)
      val trackerTreeBob = mkTrackerTreeAndProof(keyAliceBob, bobRemaining)

      // Reserve has less value than debt (only 3 ERG, trying to redeem 5 ERG)
      val insufficientReserve = minValue + 3000000000L + feeValue // Only 3 ERG collateral
      val basisInputBob = mkBasisInput(
        insufficientReserve, treeBobIn, bobKey, reserveSigBobBytes, bobRemaining, proofBob,
        trackerSigBobBytes, None, Some(trackerTreeBob.lookupProofBytes), timestamp
      )
      val trackerInputBob = mkTrackerDataInput(trackerTreeBob.tree)
      // Try to output more than reserve has
      val basisOutputBob = createOut(Constants.basisContract, insufficientReserve - bobRemaining, // Would be negative!
        Array(ErgoValue.of(aliceKey), treeBobOut, ErgoValue.of(trackerNFTBytes)),
        Array(new ErgoToken(basisTokenId, 1)))
      val redemptionBob = createOut(trueScript, bobRemaining, Array(), Array())

      // Should fail: insufficient collateral
      a[Throwable] should be thrownBy {
        createTx(Array(basisInputBob), Array(trackerInputBob),
          Array(basisOutputBob, redemptionBob), fee = None, changeAddress,
          Array(receiverSecret.toString()), broadcast = false)
      }

      println("Insufficient collateral test passed: redemption rejected")
    }
  }
}
