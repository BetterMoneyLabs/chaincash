import scala.collection.JavaConverters._

name := "chaincash"

version := "0.2.1"
organization := "org.ergoplatform"
scalaVersion := "2.12.17"

resolvers ++= Seq(
  "Sonatype Releases" at "https://oss.sonatype.org/content/repositories/releases/",
  "SonaType" at "https://oss.sonatype.org/content/groups/public",
  "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/",
  "Nexus Releases" at "https://s01.oss.sonatype.org/content/repositories/releases",
  "Nexus Snapshots" at "https://s01.oss.sonatype.org/content/repositories/snapshots"
)

libraryDependencies ++= Seq(
  ("io.github.k-singh" %% "plasma-toolkit" % "1.1.0").exclude("org.ethereum", "leveldbjni-all"),
  "io.swaydb" %% "swaydb" % "0.16.2",
  "com.squareup.okhttp3" % "mockwebserver" % "3.14.9",
  "org.scalatest" %% "scalatest" % "3.0.8" ,
  "org.scalacheck" %% "scalacheck" % "1.14.+"
)

val circeVersion = "0.12.3"
libraryDependencies ++= Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser"
).map(_ % circeVersion)

libraryDependencies ++= Seq(
  "org.scalaj" %% "scalaj-http" % "2.3.0"
)

libraryDependencies ++= Seq(
  "org.ergoplatform" %% "ergo-appkit" % "6.0.0",
  "org.ergoplatform" %% "ergo-appkit" % "6.0.0" % Test classifier "tests"
)

dependencyOverrides += "org.ergoplatform" %% "ergo-appkit" % "6.0.0"

lazy val verifyMainJarRetirement = taskKey[Unit](
  "Fail if the production JAR contains retired ChainCash or Basis v1 classes/resources"
)

verifyMainJarRetirement := {
  val jarPath = (Compile / packageBin).value
  val log = streams.value.log
  val jar = new java.util.jar.JarFile(jarPath)
  try {
    val entries = jar.entries().asScala.map(_.getName).toVector
    val forbiddenClassPrefixes = Vector(
      "chaincash/contracts/BasisDeployer",
      "chaincash/contracts/BasisConstants",
      "chaincash/contracts/BasisNoteCreator",
      "chaincash/contracts/ContractsPrinter",
      "chaincash/contracts/ParticipantKeys",
      "chaincash/contracts/ParticipantSecretsReader",
      "chaincash/offchain/NoteUtils",
      "chaincash/offchain/ReserveUtils",
      "chaincash/offchain/Tester"
    )
    val forbiddenResources = Set(
      "contracts/offchain/basis.es",
      "contracts/offchain/basis-token.es"
    )
    val forbiddenResourcePrefixes = Vector(
      "contracts/historical/",
      "contracts/onchain/",
      "contracts/layer2-old/"
    )

    val forbiddenEntries = entries.filter { entry =>
      forbiddenClassPrefixes.exists(entry.startsWith) ||
      forbiddenResources.contains(entry) ||
      forbiddenResourcePrefixes.exists(entry.startsWith)
    }

    if (forbiddenEntries.nonEmpty) {
      sys.error(
        "Retired ChainCash/Basis v1 entries leaked into the production JAR: " +
          forbiddenEntries.sorted.mkString(", ")
      )
    }

    log.info(s"Verified ${entries.size} production JAR entries: no retired ChainCash/Basis v1 surface")
  } finally {
    jar.close()
  }
}

Test / test := (Test / test).dependsOn(verifyMainJarRetirement).value
