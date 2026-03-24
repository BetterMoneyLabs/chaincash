package chaincash.contracts

import scala.io.Source
import scala.util.{Try, Success, Failure}
import java.io.File

/**
 * Utility for reading participant secrets from a CSV file.
 * 
 * Expected CSV format:
 * name,address,secret_hex
 * 
 * The file is searched in the following locations (first found wins):
 * 1. secrets/participants.local.csv (git-ignored, for local development)
 * 2. secrets/participants.csv (git-ignored, fallback)
 * 
 * @throws IllegalArgumentException if the file is not found or has invalid format
 */
object ParticipantSecretsReader {

  case class Participant(name: String, address: String, secretHex: String)

  private val PossiblePaths = Seq(
    "secrets/participants.local.csv",
    "secrets/participants.csv"
  )

  /**
   * Reads participant secrets from CSV file.
   * 
   * @return Map of participant name to (address, secret) tuple
   * @throws IllegalArgumentException if file not found or invalid format
   */
  def readSecrets(): Map[String, Participant] = {
    val file = findSecretsFile()
    parseCSV(file)
  }

  /**
   * Finds the secrets file in possible locations.
   */
  private def findSecretsFile(): java.io.File = {
    PossiblePaths
      .map(new java.io.File(_))
      .find(_.exists())
      .getOrElse {
        throw new IllegalArgumentException(
          s"Participant secrets file not found. Searched: ${PossiblePaths.mkString(", ")}. " +
          s"Copy participants.csv.template to secrets/participants.local.csv and fill in your secrets."
        )
      }
  }

  /**
   * Parses the CSV file.
   */
  private def parseCSV(file: File): Map[String, Participant] = {
    val lines = Try(Source.fromFile(file).getLines().toList).getOrElse {
      throw new IllegalArgumentException(s"Cannot read secrets file: ${file.getAbsolutePath}")
    }

    lines
      .filter(line => line.trim.nonEmpty && !line.trim.startsWith("#"))
      .map(parseLine)
      .collect { case Right(p) => p.name -> p }
      .toMap
  }

  /**
   * Parses a single CSV line.
   * Returns Left(error message) for invalid lines, Right(participant) for valid ones.
   */
  private def parseLine(line: String): Either[String, Participant] = {
    val parts = line.split(",", -1)  // -1 to keep empty trailing parts
    
    if (parts.length != 3) {
      Left(s"Invalid line (expected 3 fields): $line")
    } else {
      val name = parts(0).trim
      val address = parts(1).trim
      val secretHex = parts(2).trim
      
      if (name.isEmpty) {
        Left(s"Empty name in line: $line")
      } else if (address.isEmpty) {
        Left(s"Empty address in line: $line")
      } else if (secretHex.isEmpty) {
        Left(s"Empty secret in line: $line")
      } else if (!secretHex.matches("^[0-9a-fA-F]+$")) {
        Left(s"Invalid hex secret in line: $line")
      } else {
        Right(Participant(name, address, secretHex))
      }
    }
  }
}
