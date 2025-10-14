package chaincash.contracts

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sigmastate.Values.GroupElementConstant
import sigmastate.basics.CryptoConstants
import sigmastate.eval.CGroupElement

class BasisDeployerSpec extends AnyFlatSpec with Matchers {

  "BasisDeployer" should "compile Basis contract successfully" in {
    // This test verifies that the Basis contract can be compiled
    val basisContract = Constants.readContract("offchain/basis.es", Map.empty)
    basisContract should not be empty
    
    val basisErgoTree = Constants.compile(basisContract)
    basisErgoTree should not be null
    
    val basisAddress = Constants.getAddressFromErgoTree(basisErgoTree)
    basisAddress.toString should startWith("9")
  }

  it should "create valid deployment request" in {
    val exampleOwnerKey = KeyUtils.exampleOwnerKey
    val exampleTrackerNftId = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    val exampleReserveTokenId = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
    
    val deploymentRequest = BasisDeployer.createBasisDeploymentRequest(
      exampleOwnerKey,
      exampleTrackerNftId,
      exampleReserveTokenId
    )
    
    deploymentRequest should include("address")
    deploymentRequest should include(BasisDeployer.basisAddress.toString)
    deploymentRequest should include(exampleTrackerNftId)
    deploymentRequest should include(exampleReserveTokenId)
    deploymentRequest should include("1000000000") // Initial collateral
  }

  it should "create valid scan request" in {
    val exampleReserveTokenId = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
    
    val scanRequest = BasisDeployer.createBasisScanRequest(exampleReserveTokenId)
    
    scanRequest should include("scanName")
    scanRequest should include("Basis Reserve")
    scanRequest should include(exampleReserveTokenId)
    scanRequest should include("containsAsset")
  }

  it should "calculate redemption fees correctly" in {
    BasisConstants.calculateRedemptionFee(1000000000L) shouldBe 20000000L // 2% of 1 ERG
    BasisConstants.calculateRedemptionFee(500000000L) shouldBe 10000000L  // 2% of 0.5 ERG
    
    BasisConstants.calculateNetRedemption(1000000000L) shouldBe 980000000L // 1 ERG - 2%
    BasisConstants.calculateNetRedemption(500000000L) shouldBe 490000000L  // 0.5 ERG - 2%
  }

  it should "have correct constants" in {
    BasisConstants.REDEEM_ACTION shouldBe 0
    BasisConstants.TOP_UP_ACTION shouldBe 1
    BasisConstants.MIN_TOP_UP_AMOUNT shouldBe 1000000000L
    BasisConstants.EMERGENCY_REDEMPTION_TIME shouldBe 604800000L // 7 days in ms
    BasisConstants.REDEMPTION_FEE_PERCENTAGE shouldBe 2
  }
}