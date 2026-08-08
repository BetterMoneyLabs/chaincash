package chaincash

import chaincash.contracts.Constants
import scorex.crypto.hash.Blake2b256
import scorex.util.encode.Base58

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
  * Exact source fixtures for the retired ChainCash prototypes.
  *
  * These sources are loaded only from the test classpath. Production code must
  * not expose their contract text, ErgoTrees, addresses, or transaction builders.
  */
private[chaincash] object HistoricalContractFixtures {

  val expectedSha256: Map[String, String] = Map(
    "onchain/reserve.es" -> "14ac7744339c70f6d1252948aa85ce9684be1b99b32faae9993d6387e19f724e",
    "onchain/receipt.es" -> "c35f0aa6bbd9e272f11cabbab055c96fd47bfee2fb4d0b159f5ec18f0d131509",
    "onchain/note.es" -> "eeb985b7cd06b99b62b08fdb540c227656984c2de9ff29a4ef31804805b26c56",
    "layer2-old/reserve.es" -> "19b513ce118aa536fd95e13c7b41231caeda74c5a3c51d137ccbdf4093d4de8a",
    "layer2-old/redemption.es" -> "e6aa669a19b32e365ea7f980bfb97798c21e496eb889aeebb8dd88f27a30a930",
    "layer2-old/redproducer.es" -> "9c86583f3a3987ecdbe39f7ac2780c13d3c69c79414b3d5f6ff5a45fe3468a61",
    "layer2-old/note.es" -> "4a7a2e7cb806f0311275c6e41cb8c27b53f180d8511900f6ec09abf68a51319f"
  )

  def archivedSource(relativePath: String): String = {
    val resourcePath = s"/contracts/historical/$relativePath"
    val stream = Option(getClass.getResourceAsStream(resourcePath))
      .getOrElse(throw new IllegalStateException(s"Missing historical fixture: $resourcePath"))
    val bytes = try {
      val output = new java.io.ByteArrayOutputStream()
      val buffer = new Array[Byte](8192)
      var count = stream.read(buffer)
      while (count != -1) {
        output.write(buffer, 0, count)
        count = stream.read(buffer)
      }
      output.toByteArray
    } finally {
      stream.close()
    }

    val expected = expectedSha256.getOrElse(
      relativePath,
      throw new IllegalArgumentException(s"Unregistered historical fixture: $relativePath")
    )
    val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
      .map(byte => f"${byte & 0xff}%02x")
      .mkString
    require(actual == expected, s"Historical fixture digest mismatch for $relativePath")

    new String(bytes, StandardCharsets.UTF_8)
      .replace("\r\n", "\n")
      .stripSuffix("\n")
  }

  private def substitute(contract: String, values: Map[String, String]): String =
    values.foldLeft(contract) { case (source, (name, value)) =>
      source.replace("$" + name, value)
    }

  lazy val reserveContract: String = archivedSource("onchain/reserve.es")
  private lazy val reserveErgoTree = Constants.compile(reserveContract)
  private lazy val reserveContractHash = Base58.encode(Blake2b256(reserveErgoTree.bytes.tail))

  lazy val receiptContract: String = substitute(
    archivedSource("onchain/receipt.es"),
    Map("reserveContractHash" -> reserveContractHash)
  )
  private lazy val receiptErgoTree = Constants.compile(receiptContract)
  private lazy val receiptContractHash = Base58.encode(Blake2b256(receiptErgoTree.bytes.tail))

  lazy val noteContract: String = substitute(
    archivedSource("onchain/note.es"),
    Map(
      "reserveContractHash" -> reserveContractHash,
      "receiptContractHash" -> receiptContractHash
    )
  )
}
