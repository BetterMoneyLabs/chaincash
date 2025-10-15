name := "chaincash"

version := "0.2.1"
organization := "org.ergoplatform"
scalaVersion := "2.12.17"

Compile / unmanagedClasspath += baseDirectory.value / "contracts"

resolvers ++= Seq(
  "Sonatype Releases" at "https://oss.sonatype.org/content/repositories/releases/",
  "SonaType" at "https://oss.sonatype.org/content/groups/public",
  "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/",
  "Nexus Releases" at "https://s01.oss.sonatype.org/content/repositories/releases",
  "Nexus Snapshots" at "https://s01.oss.sonatype.org/content/repositories/snapshots"
)

libraryDependencies ++= Seq(
  ("io.github.k-singh" %% "plasma-toolkit" % "1.0.2").exclude("org.ethereum", "leveldbjni-all"),
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
  "org.ergoplatform" %% "ergo-appkit" % "5.0.3"
)

dependencyOverrides += "org.ergoplatform" % "ergo-appkit" % "5.0.3"
