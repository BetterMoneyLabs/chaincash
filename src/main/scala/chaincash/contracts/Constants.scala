package chaincash.contracts

import chaincash.offchain.SigUtils._
import org.ergoplatform.ErgoAddressEncoder
import org.ergoplatform.appkit.{AppkitHelpers, ErgoValue, NetworkType}
import sigma.crypto.CryptoConstants
import sigma.data.AvlTreeFlags
import sigma.ast.ErgoTree
import sigma.{AvlTree, GroupElement}
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.PlasmaMap

import java.util

object Constants {

  val networkType = NetworkType.MAINNET
  val networkPrefix = networkType.networkPrefix
  val ergoAddressEncoder = new ErgoAddressEncoder(networkPrefix)

  def getAddressFromErgoTree(ergoTree: ErgoTree) = ergoAddressEncoder.fromProposition(ergoTree).get

  def substitute(contract: String, substitutionMap: Map[String, String] = Map.empty): String = {
    substitutionMap.foldLeft(contract){case (c, (k,v)) =>
      c.replace("$"+k, v)
    }
  }

  private def readActiveContract(path: String, substitutionMap: Map[String, String] = Map.empty) = {
    val contract = scala.io.Source.fromFile("contracts/" + path, "utf-8").getLines.mkString("\n")
    substitute(contract, substitutionMap)
  }

  def compile(ergoScript: String): ErgoTree = {
    // Compile under v6 (blockVersion 4) to enable AvlTree.insertOrUpdate.
    AppkitHelpers.compile(new util.HashMap[String, Object](), ergoScript, networkPrefix, 4: Byte)
  }


  // ChainCash AVL tree parameters
  // keyLength = 40 (positionBytes ++ reserveId = 8 + 32 bytes)
  // valueLength = None (dynamic, for Long values)
  val chainCashPlasmaParameters = PlasmaParameters(40, None)
  def emptyPlasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
  val emptyTreeErgoValue: ErgoValue[AvlTree] = emptyPlasmaMap.ergoValue
  val emptyTree: AvlTree = emptyTreeErgoValue.getValue

  val g: GroupElement = CryptoConstants.dlogGroup.generator

  // Basis v2 is the only contract generation exposed by production tooling.
  // Historical v1 sources live under src/test/resources and cannot be loaded
  // through this object.
  val basisV2Contract = readActiveContract("offchain/basis-v2.es")
  val basisV2ErgoTree = compile(basisV2Contract)
  val basisV2Address = getAddressFromErgoTree(basisV2ErgoTree)

  val basisTokenV2Contract = readActiveContract("offchain/basis-token-v2.es")
  val basisTokenV2ErgoTree = compile(basisTokenV2Contract)
  val basisTokenV2Address = getAddressFromErgoTree(basisTokenV2ErgoTree)

  /** Contracts intentionally exposed by production address tooling. */
  val publishedContracts = Vector(
    "Basis v2" -> basisV2Address,
    "Basis-token v2" -> basisTokenV2Address
  )
}

object Printer extends App {
  Constants.publishedContracts.foreach { case (name, address) =>
    println(s"$name p2s address: $address")
  }
}
