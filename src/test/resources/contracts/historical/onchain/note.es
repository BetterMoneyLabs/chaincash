{
    // Note contract

    // It has two execution paths:

    // spend: full spending or with change

    // redeem:

    // when redemption, receipt is created which is allowing to do another redemption against earlier reserve

    // box data:
    //
    // registers:
    // R4 - history of ownership (under AVL+ tree),
    //      tree contains reserveId as a key, signature as value,
    //      and message under the signature is position in the tree, note value and token id
    // R5 - current holder of the note (public key given as a group element)
    // R6 - current length of the spendings chain (as long int)
    //
    // tokens:
    // #0 - token which amount is equal to note value at the moment of issueance, in unit of account (mg of gold).
    //      The token is denoting notes started from an initial one which has all the tokens of this ID
    //
    //  to create a note (issue new money accounted in milligrams of gols), one needs to create a box locked with this
    //  contract, R4 containing empty AVL+ tree digest, R5 containing public key (encoded elliptic curve point) of the
    //  issuer, R6 equals to 0, and tokens slot #0 contains new token with all the issuance locked in the box. If
    //  any of the conditions not met (any register has another value, some tokens sent to other address or contract),
    //  the note should be ignored by ChainCash software

    val action = getVar[Byte](0).get // also encodes note output # in tx outputs

    val holder = SELF.R5[GroupElement].get // used in both paths

    if (action >= 0) {
      // spending path

      val g: GroupElement = groupGenerator

      val history = SELF.R4[AvlTree].get

      val reserve = CONTEXT.dataInputs(0)
      val reserveId = reserve.tokens(0)._1

      val noteTokenId = SELF.tokens(0)._1
      val noteValue = SELF.tokens(0)._2

      val selfOutput = OUTPUTS(action)

      val position = SELF.R6[Long].get
      val positionBytes = longToByteArray(position)
      val noteValueBytes = longToByteArray(noteValue)
      val message = positionBytes ++ noteValueBytes ++ noteTokenId

      // a of signature in (a, z)
      val a = getVar[GroupElement](1).get
      val aBytes = a.getEncoded

      // Computing challenge
      val e: Coll[Byte] = blake2b256(aBytes ++ message ++ holder.getEncoded) // strong Fiat-Shamir
      val eInt = byteArrayToBigInt(e) // challenge as big integer

      // z of signature in (a, z)
      val zBytes = getVar[Coll[Byte]](2).get
      val z = byteArrayToBigInt(zBytes)

      // Signature is valid if g^z = a * x^e
      val properSignature = g.exp(z) == a.multiply(holder.exp(eInt))

      val properReserve = holder == reserve.R4[GroupElement].get

      val leafValue = aBytes ++ zBytes
      val leafKey = positionBytes ++ reserveId
      val keyVal = (leafKey, leafValue)
      val proof = getVar[Coll[Byte]](3).get

      val nextTree: Option[AvlTree] = history.insert(Coll(keyVal), proof)
      // This will fail if the operation failed or the proof is incorrect due to calling .get on the Option
      val outputDigest: Coll[Byte] = nextTree.get.digest

      def nextNoteCorrect(noteOut: Box): Boolean = {
          val outHistory = noteOut.R4[AvlTree].get

          val insertionPerformed = outHistory.digest == outputDigest && outHistory.enabledOperations == history.enabledOperations
          val sameScript = noteOut.propositionBytes == SELF.propositionBytes
          val nextHolderDefined = noteOut.R5[GroupElement].isDefined
          val valuePreserved = noteOut.value >= SELF.value
          val positionIncreased = noteOut.R6[Long].get == (position + 1)

          positionIncreased && insertionPerformed && sameScript && nextHolderDefined && valuePreserved
      }

      val changeIdx = getVar[Byte](4) // optional index of change output

      val outputsValid = if(changeIdx.isDefined) {
        val changeOutput = OUTPUTS(changeIdx.get)

        // strict equality to prevent moving tokens to other contracts
        (selfOutput.tokens(0)._2 + changeOutput.tokens(0)._2) == SELF.tokens(0)._2 &&
            nextNoteCorrect(selfOutput) &&
            nextNoteCorrect(changeOutput)
      } else {
        selfOutput.tokens(0) == SELF.tokens(0) && nextNoteCorrect(selfOutput)
      }

      // proveDlog(holder) is needed to prevent changing output notes in the mempool
      sigmaProp(proveDlog(holder) && properSignature && properReserve && outputsValid)
    } else {
      // action < 0

      // redemption path
      // called by setting action variable to any negative value
      // absolute value of the action denotes index of reserve in transaction inputs

      // we just check current holder's signature here

      // it is checked that note token is locked in receipt in the reserve contract

      // we check that the note is spent along with a reserve contract box.
      // we drop version byte during ergotrees comparison
      // signature of note holder is also required

      val index = -action

      val reserveInput = INPUTS(index)
      val reserveInputErgoTree = reserveInput.propositionBytes
      val treeHash = blake2b256(reserveInputErgoTree.slice(1, reserveInputErgoTree.size))
      val reserveSpent = treeHash == fromBase58("$reserveContractHash")

      // we check receipt contract here, and other fields in reserve contract, see comments in reserve.es
      val receiptOutputErgoTree = OUTPUTS(index).propositionBytes
      val receiptTreeHash = blake2b256(receiptOutputErgoTree.slice(1, receiptOutputErgoTree.size))
      val receiptCreated = receiptTreeHash == fromBase58("$receiptContractHash")

      proveDlog(holder) && sigmaProp(reserveSpent && receiptCreated)
    }

}