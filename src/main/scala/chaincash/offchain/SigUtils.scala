package chaincash.offchain

import sigmastate.basics.CryptoConstants
import special.sigma.GroupElement
import sigmastate.eval._
import sigmastate.basics.SecP256K1Group
import java.security.SecureRandom
import scala.annotation.tailrec

object SigUtils {

  def randBigInt: BigInt = {
    val random = new SecureRandom()
    val values = new Array[Byte](32)
    random.nextBytes(values)
    BigInt(values).mod(SecP256K1Group.q)
  }

  @tailrec
  def sign(msg: Array[Byte], secretKey: BigInt): (GroupElement, BigInt) = {
    val g: GroupElement = CryptoConstants.dlogGroup.generator

    val pk = g.exp(secretKey.bigInteger)

    val r = randBigInt
    val a: GroupElement = g.exp(r.bigInteger)
    val e = scorex.crypto.hash.Blake2b256(a.getEncoded.toArray ++ msg ++ pk.getEncoded.toArray)
    val z = (r + secretKey * BigInt(e)) % CryptoConstants.groupOrder

    if(z.bitLength <= 255) {
      (a, z)
    } else {
      sign(msg,secretKey)
    }
  }

}