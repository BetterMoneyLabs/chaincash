package chaincash

import chaincash.contracts.{Constants, Printer}
import org.scalatest.{Matchers, PropSpec}

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import scala.util.Try

class LegacyContractRetirementSpec extends PropSpec with Matchers {

  property("production Constants exports only the reviewed Basis v2 contract family") {
    Constants.publishedContracts shouldEqual Vector(
      "Basis v2" -> Constants.basisV2Address,
      "Basis-token v2" -> Constants.basisTokenV2Address
    )

    val exportedMethods = Constants.getClass.getMethods.map(_.getName).toSet
    val retiredGetters = Set(
      "readContract",
      "basisContract",
      "basisErgoTree",
      "basisAddress",
      "basisTokenContract",
      "basisTokenErgoTree",
      "basisTokenAddress",
      "basisPlasmaParameters",
      "reserveContract",
      "reserveContractHash",
      "reserveContractHashString",
      "reserveErgoTree",
      "reserveAddress",
      "receiptContract",
      "receiptContractHash",
      "receiptContractHashString",
      "receiptErgoTree",
      "receiptAddress",
      "noteContract",
      "noteErgoTree",
      "noteAddress",
      "redemptionContract",
      "redemptionErgoTree",
      "redemptionAddress",
      "redemptionProducerContract",
      "redemptionProducerErgoTree",
      "redemptionProducerAddress"
    )

    exportedMethods.intersect(retiredGetters) shouldBe empty
  }

  property("production classpath has no legacy address printer or transaction builders") {
    val retiredClasses = Seq(
      "chaincash.contracts.ContractsPrinter$",
      "chaincash.contracts.BasisDeployer$",
      "chaincash.contracts.BasisConstants$",
      "chaincash.contracts.BasisNoteCreator$",
      "chaincash.offchain.NoteUtils",
      "chaincash.offchain.ReserveUtils",
      "chaincash.offchain.Tester$"
    )

    retiredClasses.foreach { className =>
      withClue(className) {
        Try(Class.forName(className)).isFailure shouldBe true
      }
    }
  }

  property("production printer emits exactly the two published Basis v2 addresses") {
    val bytes = new ByteArrayOutputStream()
    val stream = new PrintStream(bytes, true, StandardCharsets.UTF_8.name())
    try {
      Console.withOut(stream) {
        Printer.main(Array.empty)
      }
    } finally {
      stream.close()
    }

    val outputLines = new String(bytes.toByteArray, StandardCharsets.UTF_8)
      .split("\\R")
      .map(_.trim)
      .filter(_.nonEmpty)
      .toVector

    outputLines shouldEqual Vector(
      s"Basis v2 p2s address: ${Constants.basisV2Address}",
      s"Basis-token v2 p2s address: ${Constants.basisTokenV2Address}"
    )
  }

  property("retired Basis v1 sources exist only on the test classpath") {
    val projectRoot = java.nio.file.Paths.get(sys.props("user.dir"))
    java.nio.file.Files.exists(projectRoot.resolve("contracts/offchain/basis.es")) shouldBe false
    java.nio.file.Files.exists(projectRoot.resolve("contracts/offchain/basis-token.es")) shouldBe false

    HistoricalContractFixtures.basisContract should not be empty
    HistoricalContractFixtures.basisTokenContract should not be empty
  }

  property("historical contract sources retain their pinned canonical-LF bytes") {
    HistoricalContractFixtures.expectedCanonicalLfSha256.keys.foreach { path =>
      withClue(path) {
        HistoricalContractFixtures.archivedSource(path) should not be empty
      }
    }
  }

  property("historical source identity is equal for LF and CRLF checkouts") {
    val lf = "first line\nsecond line\n".getBytes(StandardCharsets.UTF_8)
    val crlf = "first line\r\nsecond line\r\n".getBytes(StandardCharsets.UTF_8)
    val changed = "first line\nchanged line\n".getBytes(StandardCharsets.UTF_8)

    HistoricalContractFixtures.canonicalLfSha256(crlf) shouldEqual
      HistoricalContractFixtures.canonicalLfSha256(lf)
    HistoricalContractFixtures.canonicalLfSha256(changed) should not equal
      HistoricalContractFixtures.canonicalLfSha256(lf)
  }
}
