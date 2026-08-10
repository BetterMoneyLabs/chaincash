{
  // Basis ERG reserve, ABI generation v2.
  // See basis-v2.md. V1 contracts are intentionally left unchanged.

  val packedOpt = getVar[Byte](0)
  val selfRegistersDefined =
    SELF.R4[GroupElement].isDefined &&
    SELF.R5[AvlTree].isDefined &&
    SELF.R6[Coll[Byte]].isDefined &&
    SELF.R7[Long].isDefined &&
    SELF.R8[Long].isDefined &&
    SELF.R9[Coll[Byte]].isDefined

  if (!packedOpt.isDefined || !selfRegistersDefined) {
    sigmaProp(false)
  } else {
    val packed = packedOpt.get
    val action = packed / 10
    val outputIndex = packed % 10

    val ownerKey = SELF.R4[GroupElement].get
    val identity = groupGenerator.exp(byteArrayToBigInt(fromBase16("00")))
    val redeemedTree = SELF.R5[AvlTree].get
    val trackerNftId = SELF.R6[Coll[Byte]].get
    val refundHeight = SELF.R7[Long].get
    val emergencyHeight = SELF.R8[Long].get
    val predecessorId = SELF.R9[Coll[Byte]].get

    val singletonShape =
      SELF.tokens.size == 1 &&
      SELF.tokens(0)._2 == 1L
    val redeemedTreeShape =
      redeemedTree.keyLength == 32 &&
      redeemedTree.valueLengthOpt.isDefined &&
      redeemedTree.valueLengthOpt.get == 24 &&
      redeemedTree.isInsertAllowed &&
      redeemedTree.isUpdateAllowed &&
      !redeemedTree.isRemoveAllowed
    val selfShape =
      packed >= 0 &&
      singletonShape &&
      trackerNftId.size == 32 &&
      predecessorId.size == 32 &&
      emergencyHeight > 0L &&
      refundHeight >= 0L &&
      ownerKey != identity &&
      redeemedTreeShape

    if (!selfShape) {
      sigmaProp(false)
    } else if (action == 0) {
      val receiverOpt = getVar[GroupElement](1)
      val reserveSigOpt = getVar[Coll[Byte]](2)
      val totalDebtOpt = getVar[Long](3)
      val timestampOpt = getVar[Long](4)
      val updateProofOpt = getVar[Coll[Byte]](5)
      val priorProofOpt = getVar[Coll[Byte]](7)
      val outputShape =
        outputIndex >= 0 &&
        OUTPUTS.size > outputIndex + 1
      val contextShape =
        receiverOpt.isDefined &&
        reserveSigOpt.isDefined &&
        totalDebtOpt.isDefined &&
        timestampOpt.isDefined &&
        updateProofOpt.isDefined &&
        priorProofOpt.isDefined

      if (!outputShape || !contextShape) {
        sigmaProp(false)
      } else {
        val selfOut = OUTPUTS(outputIndex)
        val payout = OUTPUTS(outputIndex + 1)
        val successorRegistersDefined =
          selfOut.R4[GroupElement].isDefined &&
          selfOut.R5[AvlTree].isDefined &&
          selfOut.R6[Coll[Byte]].isDefined &&
          selfOut.R7[Long].isDefined &&
          selfOut.R8[Long].isDefined &&
          selfOut.R9[Coll[Byte]].isDefined
        val payoutLineageDefined = payout.R4[Coll[Byte]].isDefined

        if (!successorRegistersDefined || !payoutLineageDefined) {
          sigmaProp(false)
        } else {
          val receiver = receiverOpt.get
          val reserveSig = reserveSigOpt.get
          val totalDebt = totalDebtOpt.get
          val timestamp = timestampOpt.get
          val updateProof = updateProofOpt.get
          val priorProof = priorProofOpt.get
          val reserveNftId = SELF.tokens(0)._1

          // "BASIS" || ABI 2 || Ergo-mainnet domain 0 || ERG kind 0.
          val domainTag = fromBase16("4241534953020000")
          val claimKey = blake2b256(
            domainTag ++
            reserveNftId ++
            trackerNftId ++
            ownerKey.getEncoded ++
            receiver.getEncoded
          )
          val message =
            claimKey ++
            longToByteArray(totalDebt) ++
            longToByteArray(timestamp)

          val reserveSigShape = reserveSig.size >= 64 && reserveSig.size <= 66
          val properReserveSignature = if (reserveSigShape) {
            val aBytes = reserveSig.slice(0, 33)
            val zBytes = reserveSig.slice(33, reserveSig.size)
            val a = decodePoint(aBytes)
            val z = byteArrayToBigInt(zBytes)
            val e = byteArrayToBigInt(
              blake2b256(aBytes ++ message ++ ownerKey.getEncoded)
            )
            a != identity &&
            groupGenerator.exp(z) == a.multiply(ownerKey.exp(e))
          } else {
            false
          }

          // A proof is mandatory for both membership and non-membership.
          val priorOpt = redeemedTree.get(claimKey, priorProof)
          val zeroState =
            longToByteArray(0L) ++
            longToByteArray(0L) ++
            longToByteArray(0L)
          val priorRaw = priorOpt.getOrElse(zeroState)
          val priorShape = !priorOpt.isDefined || priorRaw.size == 24
          val priorBytes = if (priorRaw.size == 24) priorRaw else zeroState
          val storedTimestamp = byteArrayToLong(priorBytes.slice(0, 8))
          val storedTotalDebt = byteArrayToLong(priorBytes.slice(8, 16))
          val storedRedeemed = byteArrayToLong(priorBytes.slice(16, 24))
          val storedStateValid =
            storedTimestamp >= 0L &&
            storedTotalDebt >= 0L &&
            storedRedeemed >= 0L &&
            storedRedeemed <= storedTotalDebt
          val claimProgressValid = if (priorOpt.isDefined) {
            (timestamp == storedTimestamp && totalDebt == storedTotalDebt) ||
            (timestamp > storedTimestamp && totalDebt >= storedTotalDebt)
          } else {
            timestamp > 0L && totalDebt > 0L
          }

          val successorCommon =
            selfOut.propositionBytes == SELF.propositionBytes &&
            selfOut.tokens == SELF.tokens &&
            selfOut.R4[GroupElement].get == ownerKey &&
            selfOut.R6[Coll[Byte]].get == trackerNftId &&
            selfOut.R7[Long].get == refundHeight &&
            selfOut.R8[Long].get == emergencyHeight &&
            selfOut.R9[Coll[Byte]].get == SELF.id
          val receiverCondition = proveDlog(receiver)
          val receiverValid = receiver != identity
          val payoutBound =
            payout.propositionBytes == receiverCondition.propBytes &&
            payout.tokens.size == 0 &&
            payout.R4[Coll[Byte]].get == SELF.id
          val amount = SELF.value - selfOut.value
          val valueFlowValid =
            selfOut.value < SELF.value &&
            amount > 0L &&
            payout.value == amount &&
            selfOut.value + payout.value == SELF.value
          val available = if (
            storedRedeemed >= 0L && totalDebt >= storedRedeemed
          ) {
            totalDebt - storedRedeemed
          } else {
            0L
          }
          val amountValid = amount > 0L && amount <= available
          val newRedeemed = if (amountValid) {
            storedRedeemed + amount
          } else {
            storedRedeemed
          }
          val nextValue =
            longToByteArray(timestamp) ++
            longToByteArray(totalDebt) ++
            longToByteArray(newRedeemed)
          val nextTree = redeemedTree
            .insertOrUpdate(Coll((claimKey, nextValue)), updateProof)
            .get
          val stateTransitionValid =
            nextTree == selfOut.R5[AvlTree].get

          val emergency = HEIGHT >= emergencyHeight
          val trackerEvidenceValid = if (emergency) {
            true
          } else {
            val trackerSigOpt = getVar[Coll[Byte]](6)
            val trackerProofOpt = getVar[Coll[Byte]](8)
            val trackerInputPresent = CONTEXT.dataInputs.size > 0
            if (
              !trackerSigOpt.isDefined ||
              !trackerProofOpt.isDefined ||
              !trackerInputPresent
            ) {
              false
            } else {
              val tracker = CONTEXT.dataInputs(0)
              val trackerRegistersDefined =
                tracker.R4[GroupElement].isDefined &&
                tracker.R5[AvlTree].isDefined
              val trackerTokenShape =
                tracker.tokens.size == 1 &&
                tracker.tokens(0)._1 == trackerNftId &&
                tracker.tokens(0)._2 == 1L
              if (!trackerRegistersDefined || !trackerTokenShape) {
                false
              } else {
                val trackerKey = tracker.R4[GroupElement].get
                val trackerTree = tracker.R5[AvlTree].get
                val trackerTreeShape =
                  trackerTree.keyLength == 32 &&
                  trackerTree.valueLengthOpt.isDefined &&
                  trackerTree.valueLengthOpt.get == 8 &&
                  trackerTree.isInsertAllowed &&
                  trackerTree.isUpdateAllowed &&
                  !trackerTree.isRemoveAllowed
                val trackerDebtOpt = trackerTree.get(claimKey, trackerProofOpt.get)
                val trackerDebtValid = if (trackerDebtOpt.isDefined) {
                  val debtBytes = trackerDebtOpt.get
                  debtBytes.size == 8 &&
                  byteArrayToLong(debtBytes) >= totalDebt
                } else {
                  false
                }
                val trackerSig = trackerSigOpt.get
                val trackerSigShape = trackerSig.size >= 64 && trackerSig.size <= 66
                val properTrackerSignature = if (trackerSigShape) {
                  val aBytes = trackerSig.slice(0, 33)
                  val zBytes = trackerSig.slice(33, trackerSig.size)
                  val a = decodePoint(aBytes)
                  val z = byteArrayToBigInt(zBytes)
                  val e = byteArrayToBigInt(
                    blake2b256(aBytes ++ message ++ trackerKey.getEncoded)
                  )
                  a != identity &&
                  groupGenerator.exp(z) == a.multiply(trackerKey.exp(e))
                } else {
                  false
                }
                trackerKey != identity &&
                trackerTreeShape && trackerDebtValid && properTrackerSignature
              }
            }
          }

          sigmaProp(
            successorCommon &&
            receiverValid &&
            payoutBound &&
            valueFlowValid &&
            priorShape &&
            storedStateValid &&
            claimProgressValid &&
            amountValid &&
            stateTransitionValid &&
            properReserveSignature &&
            trackerEvidenceValid
          ) && receiverCondition
        }
      }
    } else if (action == 1 || action == 2) {
      val outputShape = outputIndex >= 0 && OUTPUTS.size > outputIndex
      if (!outputShape) {
        sigmaProp(false)
      } else {
        val selfOut = OUTPUTS(outputIndex)
        val successorRegistersDefined =
          selfOut.R4[GroupElement].isDefined &&
          selfOut.R5[AvlTree].isDefined &&
          selfOut.R6[Coll[Byte]].isDefined &&
          selfOut.R7[Long].isDefined &&
          selfOut.R8[Long].isDefined &&
          selfOut.R9[Coll[Byte]].isDefined
        if (!successorRegistersDefined) {
          sigmaProp(false)
        } else {
          val successorCommon =
            selfOut.propositionBytes == SELF.propositionBytes &&
            selfOut.tokens == SELF.tokens &&
            selfOut.R4[GroupElement].get == ownerKey &&
            selfOut.R5[AvlTree].get == redeemedTree &&
            selfOut.R6[Coll[Byte]].get == trackerNftId &&
            selfOut.R8[Long].get == emergencyHeight &&
            selfOut.R9[Coll[Byte]].get == SELF.id
          if (action == 1) {
            sigmaProp(
              successorCommon &&
              selfOut.R7[Long].get == refundHeight &&
              selfOut.value - SELF.value >= 100000000L
            )
          } else {
            sigmaProp(
              successorCommon &&
              refundHeight == 0L &&
              selfOut.R7[Long].get >= HEIGHT.toLong &&
              selfOut.R7[Long].get <= HEIGHT.toLong + 30L &&
              selfOut.value >= SELF.value
            ) && proveDlog(ownerKey)
          }
        }
      }
    } else if (action == 3) {
      sigmaProp(
        refundHeight > 0L &&
        HEIGHT.toLong >= 43200L &&
        refundHeight <= HEIGHT.toLong - 43200L
      ) && proveDlog(ownerKey)
    } else {
      sigmaProp(false)
    }
  }
}
