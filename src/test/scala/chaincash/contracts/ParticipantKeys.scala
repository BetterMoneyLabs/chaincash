package chaincash.contracts

import scorex.crypto.encode.Base16
import sigma.GroupElement

/** Test-only keys retained for historical fixtures that require local secrets. */
private[chaincash] object ParticipantKeys {
  private val secrets = ParticipantSecretsReader.readSecrets()

  private def getSecret(name: String): BigInt =
    secrets.get(name) match {
      case Some(participant) => BigInt(participant.secretHex, 16)
      case None =>
        throw new IllegalArgumentException(
          s"Secret for '$name' not found in secrets file. " +
            s"Available participants: ${secrets.keys.mkString(", ")}"
        )
    }

  private def getAddress(name: String): String =
    secrets.get(name) match {
      case Some(participant) => participant.address
      case None =>
        throw new IllegalArgumentException(
          s"Address for '$name' not found in secrets file. " +
            s"Available participants: ${secrets.keys.mkString(", ")}"
        )
    }

  val trackerAddress: String = getAddress("tracker")
  val trackerSecret: BigInt = getSecret("tracker")
  require(
    AddressUtils.verifySecretMatchesAddress(trackerAddress, trackerSecret),
    s"Tracker's secret does not correspond to address $trackerAddress"
  )

  val aliceAddress: String = getAddress("alice")
  val aliceSecret: BigInt = getSecret("alice")
  require(
    AddressUtils.verifySecretMatchesAddress(aliceAddress, aliceSecret),
    s"Alice's secret does not correspond to address $aliceAddress"
  )

  val bobAddress: String = getAddress("bob")
  val bobSecret: BigInt = getSecret("bob")

  lazy val trackerPublicKey: GroupElement =
    AddressUtils.derivePublicKeyFromAddress(trackerAddress)
  lazy val alicePublicKey: GroupElement =
    AddressUtils.derivePublicKeyFromAddress(aliceAddress)
  lazy val bobPublicKey: GroupElement =
    AddressUtils.derivePublicKeyFromAddress(bobAddress)
  lazy val bobErgoTree: String = AddressUtils.deriveErgoTreeFromAddress(bobAddress)

  lazy val trackerPublicKeyHex: String = Base16.encode(trackerPublicKey.getEncoded.toArray)
  lazy val alicePublicKeyHex: String = Base16.encode(alicePublicKey.getEncoded.toArray)
  lazy val bobPublicKeyHex: String = Base16.encode(bobPublicKey.getEncoded.toArray)
}
