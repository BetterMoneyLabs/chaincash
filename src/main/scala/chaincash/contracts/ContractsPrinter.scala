package chaincash.contracts

import Constants._
import scorex.util.encode.Base16
import sigmastate.Values.ByteArrayConstant
import sigmastate.serialization.ValueSerializer

object ContractsPrinter extends App {

  println(s"Note contract address: $noteAddress")

  println(s"Receipt contract address: $receiptAddress")

  println(s"Reserve contract address: $reserveAddress")


  val noteScriptBa = ByteArrayConstant(noteErgoTree.bytes)
  val reserveScriptBa = ByteArrayConstant(reserveErgoTree.bytes)


  val noteContractTrackingRule = s"""
    |{
    |  "scanName": "Note tracker",
    |  "walletInteraction": "off",
    |  "removeOffchain": false,
    |  "trackingRule": {
    |    "predicate": "equals",
    |    "value": "${Base16.encode(ValueSerializer.serialize(noteScriptBa))}"
    |  }
    |}
    """.stripMargin

  val reserveContractTrackingRule = s"""
    |
    |{
    |  "scanName": "Reserve tracker",
    |  "walletInteraction": "off",
    |  "removeOffchain": false,
    |  "trackingRule": {
    |    "predicate": "equals",
    |    "value": "${Base16.encode(ValueSerializer.serialize(reserveScriptBa))}"
    |  }
    |}
    |""".stripMargin

  println("==========Note tracking rule================")
  println(noteContractTrackingRule)
  println("==========Reserve tracking rule==============")
  println(reserveContractTrackingRule)

}
