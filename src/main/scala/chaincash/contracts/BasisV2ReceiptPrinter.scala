package chaincash.contracts

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import scorex.util.encode.Base16

/** Prints deterministic source and full-ErgoTree material for the Basis v2 receipt. */
object BasisV2ReceiptPrinter extends App {
  private def sha256(bytes: Array[Byte]): String =
    Base16.encode(MessageDigest.getInstance("SHA-256").digest(bytes))

  private def emit(name: String, source: String, treeBytes: Array[Byte]): Unit = {
    println(s"$name.source.sha256=${sha256(source.getBytes(StandardCharsets.UTF_8))}")
    println(s"$name.ergoTree.sha256=${sha256(treeBytes)}")
    println(s"$name.ergoTree.hex=${Base16.encode(treeBytes)}")
  }

  emit("basis-v2", Constants.basisV2Contract, Constants.basisV2ErgoTree.bytes)
  emit("basis-token-v2", Constants.basisTokenV2Contract, Constants.basisTokenV2ErgoTree.bytes)
}
