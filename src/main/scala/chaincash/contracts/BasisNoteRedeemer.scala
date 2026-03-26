package chaincash.contracts

import chaincash.offchain.SigUtils
import com.google.common.primitives.Longs
import io.circe.{Decoder, Json}
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._
import org.ergoplatform.{ErgoAddressEncoder, P2PKAddress}
import org.ergoplatform.appkit.NetworkType
import scorex.crypto.encode.Base16
import scorex.crypto.hash.Blake2b256
import sigmastate.basics.CryptoConstants
import sigmastate.basics.DLogProtocol.ProveDlog
import sigmastate.eval._
import sigmastate.serialization.{GroupElementSerializer, ValueSerializer}
import sigmastate.Values.AvlTreeConstant
import special.sigma.{AvlTree, GroupElement}
import sigmastate.AvlTreeFlags
import work.lithos.plasma.collections.PlasmaMap
import scala.util.{Try, Success, Failure}
import java.net.{URL, HttpURLConnection}

/**
 * Utility for redeeming Basis IOU notes with tracker signature.
 * Uses the tracker signature from the note for normal redemption (no emergency period needed).
 * Generates real AVL proofs for empty tree (first redemption) scenario.
 *
 * Usage:
 *   sbt "runMain chaincash.contracts.BasisNoteRedeemer --note-json note.json --reserve-box <box_id>"
 */
object BasisNoteRedeemer extends App {

  val networkType = NetworkType.MAINNET
  val ergoAddressEncoder = new ErgoAddressEncoder(networkType.networkPrefix)
  val g: GroupElement = CryptoConstants.dlogGroup.generator

  // Tracker's public key from ParticipantKeys (verified to match tracker address)
  val trackerPublicKey: GroupElement = ParticipantKeys.trackerPublicKey

  val REDEEM_ACTION: Byte = 0

  // Token IDs (must match deployed contract)
  val basisReserveNftId = "4b2d8b7beb3eaac8234d9e61792d270898a43934d6a27275e4f3a044609c9f2a"
  val trackerNftId = "8b1ab583bb085ecbd8fa9bc2fd59784afcdfce5496eb146bb3dd04664b56822a"

  // Plasma tree configuration (must match basis.es contract and TrackerBoxSetup)
  // Uses Constants.chainCashPlasmaParameters for consistency
  val InsertUpdate = AvlTreeFlags(insertAllowed = true, updateAllowed = true, removeAllowed = false)

  /**
   * Get current blockchain height from node API.
   */
  def getCurrentHeight(): Int = {
    val urlStr = s"$nodeUrl/info"
    val url = new URL(urlStr)
    val conn = url.openConnection().asInstanceOf[HttpURLConnection]
    try {
      conn.setRequestMethod("GET")
      conn.setRequestProperty("api_key", apiKey)
      val responseCode = conn.getResponseCode
      if (responseCode == 200) {
        val source = scala.io.Source.fromInputStream(conn.getInputStream)
        try {
          val jsonStr = source.mkString
          parse(jsonStr) match {
            case Right(json) =>
              json.hcursor.downField("fullHeight").as[Int].toOption.getOrElse(1750221)
            case Left(_) => 1750221
          }
        } finally {
          source.close()
        }
      } else 1750221
    } finally {
      conn.disconnect()
    }
  }

  case class SignatureJson(
    a: String,
    z: String
  )

  case class NoteJson(
    payerKey: String,
    payeeKey: String,
    totalDebt: Long,
    totalDebtERG: Double,
    payerSignature: Option[SignatureJson],
    trackerSignature: Option[SignatureJson],
    signature: Option[SignatureJson], // legacy field for backward compatibility
    message: String,
    noteKey: String
  ) {
    // Get tracker signature (prioritize new field, fallback to legacy)
    def getTrackerSignature: SignatureJson = {
      trackerSignature.orElse(payerSignature).getOrElse {
        throw new RuntimeException("Note must include trackerSignature for normal redemption")
      }
    }

    // Get payer signature
    def getPayerSignature: SignatureJson = {
      payerSignature.orElse(signature).getOrElse {
        throw new RuntimeException("Note must include payerSignature")
      }
    }
  }

  case class IOUNote(
    payerKey: GroupElement,
    payeeKey: GroupElement,
    totalDebt: Long,
    payerSignatureA: GroupElement,
    payerSignatureZ: BigInt,
    trackerSignatureA: GroupElement,
    trackerSignatureZ: BigInt
  )

  def parseNoteJson(filePath: String): NoteJson = {
    val source = scala.io.Source.fromFile(filePath)
    try {
      val content = source.getLines.mkString("\n")
      parse(content) match {
        case Right(json) =>
          // Try to parse as NoteJson directly first
          json.as[NoteJson] match {
            case Right(n) => n
            case Left(_) =>
              // Try to extract note from wrapper object (SetupTrackerState format)
              json.hcursor.downField("note").as[NoteJson] match {
                case Right(n) => n
                case Left(err) => throw new RuntimeException(s"Invalid note JSON: ${err.getMessage}")
              }
          }
        case Left(err) => throw new RuntimeException(s"Invalid JSON: ${err.getMessage}")
      }
    } finally {
      source.close()
    }
  }

  def noteFromJson(noteJson: NoteJson): IOUNote = {
    val payerSig = noteJson.getPayerSignature
    val trackerSig = noteJson.getTrackerSignature
    IOUNote(
      payerKey = GroupElementSerializer.fromBytes(Base16.decode(noteJson.payerKey).get),
      payeeKey = GroupElementSerializer.fromBytes(Base16.decode(noteJson.payeeKey).get),
      totalDebt = noteJson.totalDebt,
      payerSignatureA = GroupElementSerializer.fromBytes(Base16.decode(payerSig.a).get),
      payerSignatureZ = BigInt(payerSig.z, 16),
      trackerSignatureA = GroupElementSerializer.fromBytes(Base16.decode(trackerSig.a).get),
      trackerSignatureZ = BigInt(trackerSig.z, 16)
    )
  }

  def verifyNote(note: IOUNote, message: Array[Byte]): Boolean = {
    val payerValid = SigUtils.verify(message, note.payerKey, note.payerSignatureA, note.payerSignatureZ)
    val trackerValid = SigUtils.verify(message, trackerPublicKey, note.trackerSignatureA, note.trackerSignatureZ)
    payerValid && trackerValid
  }

  /**
   * Generates a real AVL proof for tracker tree lookup.
   * For first redemption (empty tree), creates a tree with the debt record and generates proof.
   *
   * Note: Uses InsertOnly flags and Constants.chainCashPlasmaParameters to match
   * the tracker box creation (TrackerBoxSetup).
   */
  def generateTrackerAvlProof(payerKey: String, payeeKey: String, totalDebt: Long): String = {
    // Create PlasmaMap with InsertOnly flags and correct parameters (must match tracker box)
    val InsertOnly = AvlTreeFlags(insertAllowed = true, updateAllowed = false, removeAllowed = false)
    val plasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](InsertOnly, Constants.chainCashPlasmaParameters)

    // Create the key: hash(payerKey || payeeKey)
    val key = Blake2b256(
      Base16.decode(payerKey).get ++
      Base16.decode(payeeKey).get
    )

    // Insert the debt record into the tree (updates map in place)
    plasmaMap.insert((key, Longs.toByteArray(totalDebt)))
    plasmaMap.prover.generateProof()

    // Generate proof for the key lookup
    val lookupProof = plasmaMap.lookUp(key).proof.bytes

    // Encode proof as hex string
    Base16.encode(lookupProof)
  }

  /**
   * Generates a real AVL proof for inserting redeemed amount into reserve tree.
   * For first redemption, creates a tree from scratch and generates insert proof.
   *
   * Note: Uses InsertOnly flags and Constants.chainCashPlasmaParameters to match
   * the reserve box creation (BasisDeployer).
   */
  def generateReserveInsertProof(payerKey: String, payeeKey: String, redeemedAmount: Long): (Array[Byte], AvlTree) = {
    // Create PlasmaMap with InsertOnly flags and correct parameters (must match reserve box)
    val InsertOnly = AvlTreeFlags(insertAllowed = true, updateAllowed = false, removeAllowed = false)
    val plasmaMap = new PlasmaMap[Array[Byte], Array[Byte]](InsertOnly, Constants.chainCashPlasmaParameters)

    // Create the key: hash(payerKey || payeeKey)
    val key = Blake2b256(
      Base16.decode(payerKey).get ++
      Base16.decode(payeeKey).get
    )

    // Insert the redeemed amount into the tree
    val insertResult = plasmaMap.insert((key, Longs.toByteArray(redeemedAmount)))

    // Get the insert proof and updated tree
    val insertProof = insertResult.proof.bytes
    val updatedTree = plasmaMap.ergoValue.getValue()

    (insertProof, updatedTree)
  }

  def encodeByteValue(b: Byte): String = {
    Base16.encode(ValueSerializer.serialize(b))
  }

  def encodeGroupElementValue(ge: GroupElement): String = {
    Base16.encode(ValueSerializer.serialize(ge))
  }

  def encodeCollByteValue(bytes: Array[Byte]): String = {
    Base16.encode(ValueSerializer.serialize(bytes))
  }

  def encodeLongValue(l: Long): String = {
    Base16.encode(ValueSerializer.serialize(l))
  }

  /**
   * Fetch reserve box and extract its AVL tree (R5 register).
   */
  def fetchReserveTree(reserveBoxId: String): Option[AvlTree] = {
    val urlStr = s"$nodeUrl/utxo/byId/$reserveBoxId"
    val url = new URL(urlStr)
    val conn = url.openConnection().asInstanceOf[HttpURLConnection]
    try {
      conn.setRequestMethod("GET")
      val responseCode = conn.getResponseCode
      if (responseCode == 200) {
        val source = scala.io.Source.fromInputStream(conn.getInputStream)
        try {
          val jsonStr = source.mkString
          parse(jsonStr) match {
            case Right(json) =>
              json.hcursor.downField("additionalRegisters").downField("R5").as[String].toOption.flatMap { r5Hex =>
                val bytes = Base16.decode(r5Hex).get
                val avlTreeConstant = ValueSerializer.deserialize(bytes).asInstanceOf[AvlTreeConstant]
                Some(avlTreeConstant.value)
              }
            case Left(_) => None
          }
        } finally {
          source.close()
        }
      } else None
    } finally {
      conn.disconnect()
    }
  }

  def buildTransaction(
    note: IOUNote,
    reserveBoxId: String,
    trackerBoxId: String,
    reserveOwnerSecret: BigInt,
    trackerSecret: BigInt,
    trackerProof: String,
    redeemedAmount: Long,
    feeAmount: Long = 0L
  ): String = {
    // Create redemption message: key || totalDebt
    val ownerKeyBytes = note.payerKey.getEncoded.toArray
    val receiverBytes = note.payeeKey.getEncoded.toArray
    val key = Blake2b256(ownerKeyBytes ++ receiverBytes)
    val redemptionMessage = key ++ Longs.toByteArray(note.totalDebt)

    // Generate reserve owner's signature on redemption message
    val (reserveSigA, reserveSigZ) = SigUtils.sign(redemptionMessage, reserveOwnerSecret)
    val reserveSigBytes = GroupElementSerializer.toBytes(reserveSigA) ++ reserveSigZ.toByteArray
    val reserveSigEncoded = Base16.encode(reserveSigBytes)

    // Generate tracker's signature on redemption message
    val (trackerSigA, trackerSigZ) = SigUtils.sign(redemptionMessage, trackerSecret)
    val trackerSigBytes = GroupElementSerializer.toBytes(trackerSigA) ++ trackerSigZ.toByteArray
    val trackerSigEncoded = Base16.encode(trackerSigBytes)

    // Generate BOTH AVL proofs required by the contract:
    // 1. Reserve insert proof (context var 5) - for inserting redeemed amount into reserve tree
    // 2. Tracker lookup proof (context var 8) - for looking up debt in tracker tree (already provided)
    val payerKeyHex = Base16.encode(note.payerKey.getEncoded.toArray)
    val payeeKeyHex = Base16.encode(note.payeeKey.getEncoded.toArray)
    val (reserveInsertProof, updatedReserveTree) = generateReserveInsertProof(
      payerKeyHex, payeeKeyHex, redeemedAmount
    )
    val reserveInsertProofEncoded = encodeCollByteValue(reserveInsertProof)
    val updatedReserveTreeEncoded = Base16.encode(ValueSerializer.serialize(AvlTreeConstant(updatedReserveTree)))

    // Build context extension with BOTH AVL proofs
    val contextVars = Map(
      "0" -> Base16.encode(ValueSerializer.serialize(REDEEM_ACTION)),
      "1" -> encodeGroupElementValue(note.payeeKey),
      "2" -> encodeCollByteValue(Base16.decode(reserveSigEncoded).get),
      "3" -> encodeLongValue(note.totalDebt),
      "5" -> reserveInsertProofEncoded,  // NEW: Reserve insert proof
      "6" -> encodeCollByteValue(Base16.decode(trackerSigEncoded).get),
      "8" -> encodeCollByteValue(Base16.decode(trackerProof).get)
    )

    val reserveValue = 50000000L
    val receiverValue = 50000000L
    val creationHeight = getCurrentHeight()

    // Get receiver's ergoTree from payeeKey (P2PK: 0008cd + pubkey)
    val receiverErgoTree = s"0008cd${Base16.encode(note.payeeKey.getEncoded.toArray)}"

    // Get basis ergoTree as hex string
    val basisErgoTreeHex = Base16.encode(Constants.basisErgoTree.bytes)

    val txJson = s"""{
       |  "inputs": [{
       |    "boxId": "$reserveBoxId",
       |    "extension": ${mapToJson(contextVars)}
       |  }],
       |  "dataInputs": [{"boxId": "$trackerBoxId"}],
       |  "outputs": [
       |    {
       |      "ergoTree": "$basisErgoTreeHex",
       |      "creationHeight": $creationHeight,
       |      "value": $reserveValue,
       |      "assets": [{"tokenId": "$basisReserveNftId", "amount": 1}],
       |      "additionalRegisters": {
       |        "R4": "${encodeGroupElementValue(note.payerKey)}",
       |        "R5": "$updatedReserveTreeEncoded",
       |        "R6": "0e20$trackerNftId"
       |      }
       |    },
       |    {
       |      "ergoTree": "$receiverErgoTree",
       |      "creationHeight": $creationHeight,
       |      "value": $receiverValue,
       |      "assets": [],
       |      "additionalRegisters": {}
       |    }
       |  ],
       |  "fee": $feeAmount
       |}""".stripMargin

    // Wrap in TransactionSigningRequest format (OpenAPI spec)
    s"""{"tx": $txJson}"""
  }

  def mapToJson(m: Map[String, String]): String = {
    m.map { case (k, v) => s""""$k": "$v"""" }.mkString("{", ",", "}")
  }

  def redeem(
    noteJson: NoteJson,
    reserveBoxId: String,
    trackerBoxId: String,
    outputFile: Option[String],
    reserveOwnerSecret: BigInt,
    trackerSecret: BigInt
  ): Unit = {
    println("=== Basis Note Redeemer (Normal Redemption) ===")
    println()

    val note = noteFromJson(noteJson)
    val message = Base16.decode(noteJson.message).get

    println("--- Note Details ---")
    println(s"Payer:   ${noteJson.payerKey.take(16)}...")
    println(s"Payee:   ${noteJson.payeeKey.take(16)}...")
    println(s"Amount:  ${noteJson.totalDebt} nanoERG (${noteJson.totalDebtERG} ERG)")
    println()

    println("--- Verifying Note Signatures ---")
    val signaturesValid = verifyNote(note, message)
    println(s"Payer signature valid:   ${SigUtils.verify(message, note.payerKey, note.payerSignatureA, note.payerSignatureZ)}")
    println(s"Tracker signature valid: ${SigUtils.verify(message, trackerPublicKey, note.trackerSignatureA, note.trackerSignatureZ)}")
    println(s"Overall valid: $signaturesValid")
    println()

    if (!signaturesValid) {
      println("ERROR: Note signatures are invalid. Cannot proceed with redemption.")
      sys.exit(1)
    }

    println("--- Building Transaction ---")
    println("Generating new signatures for redemption message.")
    println("Generating real AVL proof for tracker tree.")
    println()

    // Generate real AVL proof from tracker tree with debt record
    val avlProof = generateTrackerAvlProof(noteJson.payerKey, noteJson.payeeKey, noteJson.totalDebt)
    println(s"Tracker AVL proof: ${avlProof.take(64)}...")
    println()

    val txJson = buildTransaction(note, reserveBoxId, trackerBoxId, reserveOwnerSecret, trackerSecret, avlProof, noteJson.totalDebt)

    outputFile match {
      case Some(file) =>
        val writer = new java.io.FileWriter(file)
        try {
          writer.write(txJson)
          println(s"Transaction saved to: $file")
        } finally {
          writer.close()
        }
      case None =>
        println("\n=== Unsigned Transaction JSON ===")
        println(txJson)
    }

    println()
    println("=== Next Steps ===")
    println("1. Sign: curl -X POST http://localhost:9053/wallet/transaction/sign -H api_key: <key> -d @tx.json")
    println("2. Broadcast: curl -X POST http://localhost:9053/transactions -H api_key: <key> -d '{\"tx\": {...}}'")
    println()
    println("NOTE: This transaction generates new signatures for the redemption message.")
    println("      The note signatures are only for verification of the IOU agreement.")
  }

  def printUsage(): Unit = {
    println("=== Basis Note Redeemer ===")
    println("Redeem IOU notes with tracker signature (normal redemption)")
    println()
    println("Usage: BasisNoteRedeemer --note-json <file> --reserve-box <id> [options]")
    println()
    println("Required:")
    println("  --note-json <file>       IOU note JSON with tracker signature from BasisNoteCreator")
    println("  --reserve-box <id>       Reserve box ID to redeem from (or 'auto' to fetch from scan API)")
    println()
    println("Optional:")
    println("  --tracker-box <id>       Tracker box ID (or 'auto' to fetch from scan API, default)")
    println("  --output <file>          Save transaction to file (default: stdout)")
    println("  --reserve-owner-secret   Reserve owner's secret key (default: from ParticipantKeys)")
    println("  --tracker-secret         Tracker's secret key (default: from ParticipantKeys)")
    println("  --help, -h               Show this help")
    println()
    println("Examples:")
    println("  # With explicit box IDs:")
    println("  sbt \"runMain chaincash.contracts.BasisNoteRedeemer --note-json note.json --reserve-box abc123... --tracker-box xyz789...\"")
    println()
    println("  # Auto-fetch box IDs from node scan API:")
    println("  sbt \"runMain chaincash.contracts.BasisNoteRedeemer --note-json note.json --reserve-box auto --tracker-box auto\"")
    println()
    println("Note: The note must include trackerSignature for normal redemption.")
    println("      Reserve owner and tracker secrets are needed to sign the redemption.")
    println("      For auto-fetch, set ERGO_NODE_URL and ERGO_API_KEY environment variables.")
  }

  case class Args(
    noteJsonFile: Option[String] = None,
    reserveBoxId: Option[String] = None,
    trackerBoxId: Option[String] = None,
    outputFile: Option[String] = None,
    reserveOwnerSecret: Option[String] = None,
    trackerSecret: Option[String] = None,
    help: Boolean = false
  )

  def parseArgs(args: Array[String]): Either[String, Args] = {
    var result = Args()
    var i = 0
    while (i < args.length) {
      args(i) match {
        case "--note-json" =>
          if (i + 1 < args.length) {
            result = result.copy(noteJsonFile = Some(args(i + 1)))
            i += 1
          } else return Left("Missing value for --note-json")
        case "--reserve-box" =>
          if (i + 1 < args.length) {
            result = result.copy(reserveBoxId = Some(args(i + 1)))
            i += 1
          } else return Left("Missing value for --reserve-box")
        case "--tracker-box" =>
          if (i + 1 < args.length) {
            result = result.copy(trackerBoxId = Some(args(i + 1)))
            i += 1
          } else return Left("Missing value for --tracker-box")
        case "--output" =>
          if (i + 1 < args.length) {
            result = result.copy(outputFile = Some(args(i + 1)))
            i += 1
          } else return Left("Missing value for --output")
        case "--reserve-owner-secret" =>
          if (i + 1 < args.length) {
            result = result.copy(reserveOwnerSecret = Some(args(i + 1)))
            i += 1
          } else return Left("Missing value for --reserve-owner-secret")
        case "--tracker-secret" =>
          if (i + 1 < args.length) {
            result = result.copy(trackerSecret = Some(args(i + 1)))
            i += 1
          } else return Left("Missing value for --tracker-secret")
        case "--help" | "-h" =>
          result = result.copy(help = true)
        case arg =>
          return Left(s"Unknown argument: $arg")
      }
      i += 1
    }
    Right(result)
  }

  // Node API configuration
  val nodeUrl = sys.env.getOrElse("ERGO_NODE_URL", "http://127.0.0.1:9053")
  val apiKey = sys.env.getOrElse("ERGO_API_KEY", "hello")

  def fetchUnspentBoxes(scanId: Int): Option[String] = {
    val urlStr = s"$nodeUrl/scan/$scanId/boxes/unspent"
    val url = new URL(urlStr)
    val conn = url.openConnection().asInstanceOf[HttpURLConnection]
    try {
      conn.setRequestMethod("GET")
      conn.setRequestProperty("api_key", apiKey)
      val responseCode = conn.getResponseCode
      if (responseCode == 200) {
        val source = scala.io.Source.fromInputStream(conn.getInputStream)
        try {
          val jsonStr = source.mkString
          parse(jsonStr) match {
            case Right(json) =>
              json.hcursor.downField("items").downArray.downField("boxId").as[String].toOption
            case Left(_) => None
          }
        } finally {
          source.close()
        }
      } else None
    } finally {
      conn.disconnect()
    }
  }

  def fetchReserveAndTrackerBoxes(reserveBoxId: Option[String], trackerBoxId: Option[String]): Either[String, (String, String)] = {
    // Fetch reserve box (scanId=38)
    val actualReserveBoxId = reserveBoxId match {
      case Some("auto") | None =>
        Console.err.println("Fetching reserve box (scanId=38)...")
        fetchUnspentBoxes(38) match {
          case Some(id) =>
            Console.err.println(s"  Found reserve box: $id")
            id
          case None => return Left("Reserve box not found. Make sure the reserve is created and scanned with scanId=38")
        }
      case Some(id) => id
    }

    // Fetch tracker box (scanId=36)
    val actualTrackerBoxId = trackerBoxId match {
      case Some("auto") | None =>
        Console.err.println("Fetching tracker box (scanId=36)...")
        fetchUnspentBoxes(36) match {
          case Some(id) =>
            Console.err.println(s"  Found tracker box: $id")
            id
          case None => return Left("Tracker box not found. Make sure the tracker is created and scanned with scanId=36")
        }
      case Some(id) => id
    }

    Right((actualReserveBoxId, actualTrackerBoxId))
  }

  // Main
  if (args.isEmpty) {
    printUsage()
    sys.exit(0)
  }

  parseArgs(args) match {
    case Left(err) =>
      println(s"Error: $err")
      println()
      printUsage()
      sys.exit(1)
    case Right(cli) =>
      if (cli.help) {
        printUsage()
      } else if (cli.noteJsonFile.isEmpty) {
        println("Error: --note-json is required")
        printUsage()
        sys.exit(1)
      } else {
        // Fetch box IDs (supports 'auto' for scan API fetching)
        fetchReserveAndTrackerBoxes(cli.reserveBoxId, cli.trackerBoxId) match {
          case Left(err) =>
            println(s"Error: $err")
            sys.exit(1)
          case Right((reserveBoxId, trackerBoxId)) =>
            // Get secrets from command line or use defaults from ParticipantKeys
            val reserveOwnerSecret = cli.reserveOwnerSecret match {
              case Some(s) => BigInt(s)
              case None => ParticipantKeys.aliceSecret // default: Alice is reserve owner
            }
            val trackerSecret = cli.trackerSecret match {
              case Some(s) => BigInt(s)
              case None => ParticipantKeys.trackerSecret // default: use tracker secret from ParticipantKeys
            }

            Try {
              val noteJson = parseNoteJson(cli.noteJsonFile.get)
              redeem(noteJson, reserveBoxId, trackerBoxId, cli.outputFile, reserveOwnerSecret, trackerSecret)
            } match {
              case Success(_) =>
              case Failure(e) =>
                println(s"ERROR: ${e.getMessage}")
                sys.exit(1)
            }
        }
      }
  }
}
