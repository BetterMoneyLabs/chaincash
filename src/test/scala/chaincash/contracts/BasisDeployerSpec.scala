package chaincash.contracts

import org.scalatest.{Matchers, PropSpec}
import sigma.ast.GroupElementConstant
import sigma.crypto.CryptoConstants

class BasisDeployerSpec extends PropSpec with Matchers {

  property("BasisDeployer should compile Basis contract successfully") {
    // This test verifies that the Basis contract can be compiled
    val basisContract = Constants.readContract("offchain/basis.es", Map.empty)
    basisContract should not be empty

    val basisErgoTree = Constants.compile(basisContract)
    basisErgoTree should not be null

    val basisAddress = Constants.getAddressFromErgoTree(basisErgoTree)
    // Address should be a valid mainnet P2S address, round-trippable via the encoder
    basisAddress shouldBe a [org.ergoplatform.Pay2SAddress]
    Constants.ergoAddressEncoder.fromString(basisAddress.toString).get shouldEqual basisAddress
  }

  property("BasisDeployer should create valid deployment request") (pending)

  property("BasisDeployer should create valid scan request") {
    val exampleReserveTokenId = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"

    val scanRequest = BasisDeployer.createBasisScanRequest(exampleReserveTokenId)

    scanRequest should include("scanName")
    scanRequest should include("Basis Reserve")
    scanRequest should include(exampleReserveTokenId)
    scanRequest should include("containsAsset")
  }

  property("BasisDeployer should have correct constants") {
    BasisConstants.REDEEM_ACTION shouldBe 0
    BasisConstants.TOP_UP_ACTION shouldBe 1
    BasisConstants.INITIATE_REFUND_ACTION shouldBe 2
    BasisConstants.COMPLETE_REFUND_ACTION shouldBe 3
    BasisConstants.MIN_TOP_UP_AMOUNT shouldBe 100000000L // 0.1 ERG
    BasisConstants.EMERGENCY_REDEMPTION_TIME_IN_BLOCKS shouldBe 2160 // 3 days in blocks (3 * 720)
    BasisConstants.REFUND_PERIOD_BLOCKS shouldBe 43200 // 2 months in blocks (60 * 720)
  }
}