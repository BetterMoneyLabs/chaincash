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

  val expectedCanonicalLfSha256: Map[String, String] = Map(
    "onchain/reserve.es" -> "3f39c13879748230cd08ee10d9970370506509e966229b735af77486843fa335",
    "onchain/receipt.es" -> "69c24be8426e7c7c8fdf77ab248e4549b8cc9832122aed010a54e304357869ea",
    "onchain/note.es" -> "5a145dfa334efa60a26fe25ed136611992f207807ecb628c4c8ec196d940df16",
    "layer2-old/reserve.es" -> "026c29b169f39f75a262590388364aea926a270c6fa78511199c1ae1512336e4",
    "layer2-old/redemption.es" -> "b87614ec6aacbed34a0bf7950629d505cc6f204d1b4e7db52c5b43a133e122c6",
    "layer2-old/redproducer.es" -> "9e9842716a49d631bc67ecf201da42c020d441e3329b9fd7778ea14c9dcc8351",
    "layer2-old/note.es" -> "797b69c823ff392d5a941cbd02b9297341c5926c2ce721ab6735f71f3c181e1b"
  )

  private[chaincash] def canonicalLfBytes(bytes: Array[Byte]): Array[Byte] =
    new String(bytes, StandardCharsets.UTF_8)
      .replace("\r\n", "\n")
      .getBytes(StandardCharsets.UTF_8)

  private[chaincash] def canonicalLfSha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256")
      .digest(canonicalLfBytes(bytes))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

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

    val expected = expectedCanonicalLfSha256.getOrElse(
      relativePath,
      throw new IllegalArgumentException(s"Unregistered historical fixture: $relativePath")
    )
    val canonicalBytes = canonicalLfBytes(bytes)
    val actual = canonicalLfSha256(bytes)
    require(actual == expected, s"Historical fixture digest mismatch for $relativePath")

    new String(canonicalBytes, StandardCharsets.UTF_8)
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
