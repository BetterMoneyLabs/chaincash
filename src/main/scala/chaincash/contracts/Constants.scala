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

  private def substitute(contract: String, substitutionMap: Map[String, String] = Map.empty): String = {
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
  // Basis AVL tree parameters
  // keyLength = 32 (Blake2b256 hash of ownerKey || receiverKey)
  val basisPlasmaParameters = PlasmaParameters(32, None)
  def emptyPlasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.InsertOnly, chainCashPlasmaParameters)
  val emptyTreeErgoValue: ErgoValue[AvlTree] = emptyPlasmaMap.ergoValue
  val emptyTree: AvlTree = emptyTreeErgoValue.getValue

  val g: GroupElement = CryptoConstants.dlogGroup.generator

  // Basis contracts

  val basisContract = readActiveContract("offchain/basis.es")
  val basisErgoTree = compile(basisContract)
  val basisAddress = getAddressFromErgoTree(basisErgoTree)

  // Basis-token contract (token-based reserve)
  val basisTokenContract = readActiveContract("offchain/basis-token.es")
  val basisTokenErgoTree = compile(basisTokenContract)
  val basisTokenAddress = getAddressFromErgoTree(basisTokenErgoTree)

  /** Contracts intentionally exposed by production address tooling. */
  val publishedContracts = Vector(
    "Basis" -> basisAddress,
    "Basis-token" -> basisTokenAddress
  )
}

object Printer extends App {
  Constants.publishedContracts.foreach { case (name, address) =>
    println(s"$name p2s address: $address")
  }

  // Example deployment info
  println("\nTo deploy Basis reserve:")
  println("1. Run BasisDeployer.main() for deployment requests")
  println("2. Use createBasisDeploymentRequest() with actual values")
}
