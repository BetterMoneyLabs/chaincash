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
    basisAddress.toString should startWith("W")
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

  property("BasisDeployer should calculate redemption fees correctly") {
    BasisConstants.calculateRedemptionFee(1000000000L) shouldBe 20000000L // 2% of 1 ERG
    BasisConstants.calculateRedemptionFee(500000000L) shouldBe 10000000L  // 2% of 0.5 ERG
    
    BasisConstants.calculateNetRedemption(1000000000L) shouldBe 980000000L // 1 ERG - 2%
    BasisConstants.calculateNetRedemption(500000000L) shouldBe 490000000L  // 0.5 ERG - 2%
  }

  property("BasisDeployer should have correct constants") {
    BasisConstants.REDEEM_ACTION shouldBe 0
    BasisConstants.TOP_UP_ACTION shouldBe 1
    BasisConstants.MIN_TOP_UP_AMOUNT shouldBe 1000000000L
    BasisConstants.EMERGENCY_REDEMPTION_TIME shouldBe 604800000L // 7 days in ms
    BasisConstants.REDEMPTION_FEE_PERCENTAGE shouldBe 2
  }
}