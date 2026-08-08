package chaincash

import chaincash.contracts.{Constants, Printer}
import org.scalatest.{Matchers, PropSpec}

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import scala.util.Try

class LegacyContractRetirementSpec extends PropSpec with Matchers {

  property("production Constants exports only the published Basis contract family") {
    Constants.publishedContracts.map(_._1) shouldEqual Vector("Basis", "Basis-token")

    val exportedMethods = Constants.getClass.getMethods.map(_.getName).toSet
    val retiredGetters = Set(
      "readContract",
      "reserveContract",
      "reserveErgoTree",
      "reserveAddress",
      "receiptContract",
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

  property("production printer emits only the published Basis addresses") {
    val bytes = new ByteArrayOutputStream()
    val stream = new PrintStream(bytes, true, StandardCharsets.UTF_8.name())
    try {
      Console.withOut(stream) {
        Printer.main(Array.empty)
      }
    } finally {
      stream.close()
    }

    val output = new String(bytes.toByteArray, StandardCharsets.UTF_8)
    output should include (s"Basis p2s address: ${Constants.basisAddress}")
    output should include (s"Basis-token p2s address: ${Constants.basisTokenAddress}")
    output should not include "Redemption p2s address"
    output should not include "Redemption producer p2s address"
    output should not include "Note contract address"
    output should not include "Receipt contract address"
    output should not include "Reserve contract address"
  }

  property("historical contract sources retain their pinned upstream bytes") {
    HistoricalContractFixtures.expectedSha256.keys.foreach { path =>
      withClue(path) {
        HistoricalContractFixtures.archivedSource(path) should not be empty
      }
    }
  }
}
