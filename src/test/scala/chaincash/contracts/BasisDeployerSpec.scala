package chaincash.contracts

import chaincash.HistoricalContractFixtures
import org.scalatest.{Matchers, PropSpec}

import java.nio.file.{Files, Paths}
import scala.util.Try

/** Regression boundary for the retired Basis v1 deployment helper. */
class BasisDeployerSpec extends PropSpec with Matchers {

  property("historical Basis v1 source remains compilable only through the test fixture") {
    val historicalSource = HistoricalContractFixtures.basisContract
    historicalSource should not be empty

    val historicalTree = Constants.compile(historicalSource)
    historicalTree.bytes shouldEqual HistoricalContractFixtures.basisErgoTree.bytes

    val historicalAddress = Constants.getAddressFromErgoTree(historicalTree)
    historicalAddress shouldBe a [org.ergoplatform.Pay2SAddress]
    Constants.ergoAddressEncoder.fromString(historicalAddress.toString).get shouldEqual historicalAddress
  }

  property("production Basis v1 deployment and note builders are absent") {
    Seq(
      "chaincash.contracts.BasisDeployer$",
      "chaincash.contracts.BasisConstants$",
      "chaincash.contracts.BasisNoteCreator$"
    ).foreach { className =>
      withClue(className) {
        Try(Class.forName(className)).isFailure shouldBe true
      }
    }
  }

  property("Basis v1 ErgoScript has no production source path") {
    val projectRoot = Paths.get(sys.props("user.dir"))
    Files.exists(projectRoot.resolve("contracts/offchain/basis.es")) shouldBe false
    Files.exists(projectRoot.resolve("contracts/offchain/basis-token.es")) shouldBe false

    HistoricalContractFixtures.basisContract should not be empty
    HistoricalContractFixtures.basisTokenContract should not be empty
  }
}
