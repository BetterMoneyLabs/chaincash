{
    // ERG variant

    // Data:
    //  - token #0 - identifying singleton token
    //  - R4 - signing key (as a group element)
    //  - R5 - refund init height (Int.MaxValue if not set)
    //
    // Actions:
    //  - redeem note (#0)
    //  - top up      (#1)
    //  - init refund (#2)
    //  - cancel refund (#3)
    //  - complete refund (#4)

    val ownerKey = SELF.R4[GroupElement].get // used in notes and unlock/lock/refund actions
    val selfOut = OUTPUTS(0)
    val selfPreserved =
            selfOut.propositionBytes == SELF.propositionBytes &&
            selfOut.tokens == SELF.tokens &&
            selfOut.R4[GroupElement].get == SELF.R4[GroupElement].get

    val action = getVar[Byte](0).get

    if (action == 0) {
      // redeem path
      // oracle provides gold price in nanoErg per kg in its R4 register

      // todo: prevent double redemption by maintaining a tree with redemptions

      val g: GroupElement = groupGenerator

      val redemptionContractTokenId = fromBase58("...") // todo: read from outer environment

      val redemptionInput = INPUTS(0)
      val redemptionInputOk == redemptionInput.tokens(0)._1 == redemptionContractTokenId

      val history = redemptionInput.R4[AvlTree].get
      val redeemPosition = redemptionInput.R6[Long].get

      // read last lead and redemption leaf

      // checking redemption leaf signature
      val rLeafTreeHashDigest = getVar[Coll[Byte]](1).get
      val rLeafReserveId = getVar[Coll[Byte]](2).get
      val rLeafNoteValue = getVar[Long](3).get
      val rLeafHolderId = getVar[Coll[Byte]](4).get
      val rLeafA = getVar[GroupElement](5).get
      val rLeafABytes = rLeafA.getEncoded
      val rLeafZBytes = getVar[Coll[Byte]](6).get
      val rLeafProperFormat = rLeafTreeHashDigest.size == 32 && rLeafReserveId.size == 32 && rLeafHolderId.size == 32
      val rLeafMessage = rLeafTreeHashDigest ++ rLeafReserveId ++ longToByteArray(rLeafNoteValue) ++ rLeafHolderId
      val rLeafEInt = byteArrayToBigInt(blake2b256(rLeafMessage)) // weak Fiat-Shamir - todo: should be strong
      val rLeafZ = byteArrayToBigInt(rLeafZBytes)
      val rLeafReserveIdValid = rLeafReserve.tokens(0)._1 == rLeafReserveId
      val rLeafReservePk = rLeafReserve.R4[GroupElement].get
      val rLeafProperSignature = (g.exp(rLeafZ) == rLeafA.multiply(lastLeafReservePk.exp(rLeafEInt))) && rLeafProperFormat && rLeafReserveIdValid

      // check holder's record signed
      val holderTreeHashDigest = getVar[Coll[Byte]](7).get
      val holderReserveId = getVar[Coll[Byte]](8).get
      val holderNoteValue = getVar[Long](9).get
      val holderHolderId = getVar[Coll[Byte]](10).get
      val holderA = getVar[GroupElement](11).get
      val holderABytes = holderA.getEncoded
      val holderZBytes = getVar[Coll[Byte]](12).get
      val holderProperFormat = holderTreeHashDigest.size == 32 && holderReserveId.size == 32 && holderHolderId.size == 32
      val holderMessage = holderTreeHashDigest ++ holderReserveId ++ longToByteArray(holderNoteValue) ++ holderHolderId
      val holderEInt = byteArrayToBigInt(blake2b256(holderMessage)) // weak Fiat-Shamir - todo: should be strong
      val holderZ = byteArrayToBigInt(holderZBytes)
      val holderReserveIdValid = holderReserve.tokens(0)._1 == holderReserveId
      val holderReservePk = holderReserve.R4[GroupElement].get
      val holderProperSignature = (g.exp(holderZ) == holderA.multiply(holderReservePk.exp(holderEInt))) && holderProperFormat && holderReserveIdValid

      // checking tree proofs of inclusion for redemption and last leafs
      val lastLeafPosition = redemptionInput.R5[Long].get
      val lastLeafKeyBytes = longToByteArray(lastLeafPosition)
      val rLeafPosition = redemptionInput.R6[Long].get
      val rLeafKeyBytes = longToByteArray(rLeafPosition)
      val proof = getVar[Coll[Byte]](13).get
      val properProof = history.get(rLeafKeyBytes, proof).get == (rLeafABytes ++ rLeafZBytes) &&
                        history.get(lastLeafKeyBytes, proof).get == (holderABytes ++ holderZBytes)

      val goldOracle = CONTEXT.dataInputs(0)
      val properOracle = goldOracle.tokens(0)._1 == fromBase58("2DfY1K4rW9zPVaQgaDp2KXgnErjxKPbbKF5mq1851MJE")
      val oracleRate = goldOracle.R4[Long].get / 1000000 // normalize to nanoerg per mg of gold
      val tokensRedeemed = holderNoteValue // 1 token == 1 mg of gold

      // 2% redemption fee
      val nanoergsToRedeem = tokensRedeemed * oracleRate * 98 / 100
      val redeemCorrect = (SELF.value - selfOut.value) <= nanoergsToRedeem

      sigmaProp(selfPreserved && redeemCorrect && rLeafProperSignature && holderProperSignature && properProof && properOracle)
    } else if (action == 1) {
      // top up
      sigmaProp(selfPreserved && (selfOut.value - SELF.value >= 1000000000)) // at least 1 ERG added
    } else {
      // refund
      // todo: check that refund delay is bigger than max contestation period
      // todo: write tests for refund paths, document them
      if (action == 2) {
        // init refund
        val correctHeight = selfOut.R5[Int].get >= HEIGHT - 5
        sigmaProp(selfPreserved && correctHeight) && proveDlog(ownerKey)
      } else if (action == 3) {
        // cancel refund
        val correctHeight = !(selfOut.R5[Int].isDefined)
        sigmaProp(selfPreserved && correctHeight) && proveDlog(ownerKey)
      } else if (action == 4) {
        // todo: complete refund is not possible now , there must be some ergs left to allow sigs check
        // complete refund
        val refundNotificationPeriod = 7200 // 10 days
        val correctHeight = (SELF.R5[Int].get + refundNotificationPeriod) >= HEIGHT
        sigmaProp(correctHeight) && proveDlog(ownerKey) // todo: check is it ok to check no conditions
      } else {
        sigmaProp(false)
      }
    }

}