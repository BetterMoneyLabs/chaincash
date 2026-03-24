package chaincash.contracts

import org.scalatest.{Matchers, PropSpec}
import sigmastate.Values.GroupElementConstant
import sigmastate.basics.CryptoConstants
import sigmastate.eval.CGroupElement

class BasisDeployerSpec extends PropSpec with Matchers {

  property("BasisDeployer should compile Basis contract successfully") {
    // This test verifies that the Basis contract can be compiled
    val basisContract = Constants.readContract("offchain/basis.es", Map.empty)
    basisContract should not be empty

    val basisErgoTree = Constants.compile(basisContract)
    basisErgoTree should not be null

    val basisAddress = Constants.getAddressFromErgoTree(basisErgoTree)
    // Address should be valid Ergo address (starts with valid prefix for the network)
    basisAddress.toString should (startWith("9") or startWith("W") or startWith("3") or startWith("RtQ"))
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
    BasisConstants.MIN_TOP_UP_AMOUNT shouldBe 100000000L // 0.1 ERG
    BasisConstants.EMERGENCY_REDEMPTION_TIME_IN_BLOCKS shouldBe 2160 // 3 days in blocks (3 * 720)
  }
}