package chaincash.contracts

import org.ergoplatform.ErgoAddressEncoder
import org.ergoplatform.appkit.{AppkitHelpers, ErgoValue, NetworkType}
import scorex.crypto.hash.Blake2b256
import scorex.util.encode.Base58
import sigmastate.eval.CGroupElement
import sigmastate.basics.CryptoConstants
import sigmastate.AvlTreeFlags
import sigmastate.Values.ErgoTree
import sigmastate.lang.{CompilerSettings, SigmaCompiler, TransformingSigmaBuilder}
import special.sigma.{AvlTree, GroupElement}
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.PlasmaMap

import java.util

object Constants {

  val networkType = NetworkType.MAINNET
  val networkPrefix = networkType.networkPrefix
  val ergoAddressEncoder = new ErgoAddressEncoder(networkPrefix)
  private val compiler = SigmaCompiler(CompilerSettings(networkPrefix, TransformingSigmaBuilder, lowerMethodCalls = true))

  def getAddressFromErgoTree(ergoTree: ErgoTree) = ergoAddressEncoder.fromProposition(ergoTree).get

  def substitute(contract: String, substitutionMap: Map[String, String] = Map.empty): String = {
    substitutionMap.foldLeft(contract){case (c, (k,v)) =>
      c.replace("$"+k, v)
    }
  }

  def readContract(path: String, substitutionMap: Map[String, String] = Map.empty) = {
    substitute(scala.io.Source.fromFile("contracts/" + path, "utf-8").getLines.mkString("\n"), substitutionMap)
  }

  def compile(ergoScript: String): ErgoTree = {
    AppkitHelpers.compile(new util.HashMap[String, Object](), ergoScript, networkPrefix)
  }


  val chainCashPlasmaParameters = PlasmaParameters(40, None)
  def emptyPlasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
  val emptyTreeErgoValue: ErgoValue[AvlTree] = emptyPlasmaMap.ergoValue
  val emptyTree: AvlTree = emptyTreeErgoValue.getValue

  val g: GroupElement = CGroupElement(CryptoConstants.dlogGroup.generator)

  val reserveContract = readContract("onchain/reserve.es", Map.empty)
  val reserveErgoTree = compile(reserveContract)
  val reserveAddress = getAddressFromErgoTree(reserveErgoTree)
  val reserveContractHash = Blake2b256(reserveErgoTree.bytes.tail)
  val reserveContractHashString = Base58.encode(reserveContractHash)

  val receiptContract = readContract("onchain/receipt.es", Map("reserveContractHash" -> reserveContractHashString))
  val receiptErgoTree = compile(receiptContract)
  val receiptAddress = getAddressFromErgoTree(receiptErgoTree)
  val receiptContractHash = Blake2b256(receiptErgoTree.bytes.tail)
  val receiptContractHashString = Base58.encode(receiptContractHash)

  val noteContract = readContract("onchain/note.es",
    Map("reserveContractHash" -> reserveContractHashString, "receiptContractHash" -> receiptContractHashString))
  val noteErgoTree = compile(noteContract)
  val noteAddress = getAddressFromErgoTree(noteErgoTree)

  // Basis contracts

  val basisContract = readContract("offchain/basis.es", Map())
  val basisErgoTree = compile(basisContract)
  val basisAddress = getAddressFromErgoTree(basisErgoTree)

  // contracts below are experimental and not finished ChainCash-on-Layer2 contracts

  val redemptionContract = scala.io.Source.fromFile("contracts/layer2-old/redemption.es", "utf-8").getLines.mkString("\n")
  val redemptionErgoTree = compile(redemptionContract)
  val redemptionAddress = getAddressFromErgoTree(redemptionErgoTree)

  val redemptionProducerContract = scala.io.Source.fromFile("contracts/layer2-old/redproducer.es", "utf-8").getLines.mkString("\n")
  val redemptionProducerErgoTree = compile(redemptionProducerContract)
  val redemptionProducerAddress = getAddressFromErgoTree(redemptionProducerErgoTree)
}

object Printer extends App {
  println("Basis p2s address: " + Constants.basisAddress)
  println("Redemption p2s address: " + Constants.redemptionAddress)
  println("Redemption producer p2s address: " + Constants.redemptionProducerAddress)
}
