package chaincash

import scorex.util.encode.Base16

/**
 * Helper methods for parsing and extracting data from scan endpoint responses.
 */
trait ScanBoxHelpers {

  /**
   * Parse scan response JSON and extract box data.
   * Expected format: Array of [{ "box": {"boxId": "...", "value": ..., "creationHeight": ..., "additionalRegisters": {...}, "assets": [...]} }]
   */
  def parseScanResponse(json: String): ScanBoxData = {
    import io.circe.parser._
    import io.circe.JsonObject
    
    val parsed = parse(json).toOption.get
    
    // Scan response is an array, get first element
    val firstElement = parsed.hcursor.downN(0)
    val box = firstElement.downField("box")
    
    val boxId = box.downField("boxId").as[String].toOption.get
    val value = box.downField("value").as[Long].toOption.get
    val creationHeight = box.downField("creationHeight").as[Int].toOption.get
    
    // Parse additionalRegisters as JsonObject first, then extract individual fields
    val registersCursor = box.downField("additionalRegisters")
    val r4 = registersCursor.downField("R4").as[String].toOption.getOrElse("")
    val r5 = registersCursor.downField("R5").as[String].toOption.getOrElse("")
    val r6 = registersCursor.downField("R6").as[String].toOption.getOrElse("")
    
    // Parse assets to get token ID - handle different JSON formats
    val assetsCursor = box.downField("assets")
    val tokenId = assetsCursor.as[Seq[io.circe.Json]].toOption.flatMap { assets =>
      assets.headOption.flatMap { json =>
        json.hcursor.downField("tokenId").as[String].toOption
      }
    }.getOrElse("")
    
    ScanBoxData(
      boxId = boxId,
      value = value,
      creationHeight = creationHeight,
      r4 = r4,
      r5 = r5,
      r6 = r6,
      tokenId = tokenId
    )
  }

  /**
   * Extract tracker NFT ID from reserve box R6 register.
   * R6 format: 07 (GroupElement type) + 03 (compressed) + 32 bytes of NFT ID
   */
  def extractTrackerNftFromR6(r6Hex: String): Array[Byte] = {
    // Skip the type prefix (0703) and extract the 32-byte NFT ID
    val bytes = Base16.decode(r6Hex).get
    // Format: 07 (type) + 03 (compressed flag) + 32 bytes
    bytes.drop(2).take(32)
  }

  /**
   * Extract public key bytes from R4 register.
   * R4 format: 07 (GroupElement type) + 03 (compressed) + 32 bytes
   * Returns the full 33-byte compressed public key (including 0x03 prefix)
   */
  def extractPublicKeyFromR4(r4Hex: String): Array[Byte] = {
    // Skip the type byte (07) and keep the compressed key (03 + 32 bytes)
    val bytes = Base16.decode(r4Hex).get
    // Format: 07 (type) + 03 (compressed flag) + 32 bytes
    // Return exactly 33 bytes (03 + 32 byte key)
    bytes.drop(1).take(33)
  }
}

/**
 * Case class holding parsed scan box data.
 */
case class ScanBoxData(
  boxId: String,
  value: Long,
  creationHeight: Int,
  r4: String,  // Owner/Tracker public key
  r5: String,  // AVL tree
  r6: String,  // Tracker NFT (for reserve box)
  tokenId: String
)
