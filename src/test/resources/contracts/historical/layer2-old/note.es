{
    // todo: rework note contracts to produce valid redemption, check that the new contract is compatible with redproducer

    // Note contract

    // It has two execution paths:

    // spend: full spending or with change

    // redeem:

    // to create a note, ...

    // box data:
    // R4 - history of ownership (under AVL+ tree),
    //      tree contains reserveId as a key, signature as value,
    //      and message is note value and token id
    // R5 - current holder of the note (public key given as a group element)
    // R6 - current length of the chain (as long int)
    // R7 - note value (as long int)

    val g: GroupElement = groupGenerator

    val history = SELF.R4[AvlTree].get

    val action = getVar[Byte](0).get

    val reserve = CONTEXT.dataInputs(0)
    val reserveId = reserve.tokens(0)._1

    if (action >= 0) {
      // spending path

      val holder = SELF.R5[GroupElement].get

      val selfOutput = OUTPUTS(action)

      // Message to be signed is position + note amount + note id
      val position = SELF.R6[Long].get
      val noteValueBytes = longToByteArray(SELF.R7[Long].get)
      val positionBytes = longToByteArray(position)
      val message = positionBytes ++ noteValueBytes ++ SELF.id

      // Computing challenge
      val e: Coll[Byte] = blake2b256(message) // weak Fiat-Shamir - todo: should be strong
      val eInt = byteArrayToBigInt(e) // challenge as big integer

      // a of signature in (a, z)
      val a = getVar[GroupElement](1).get
      val aBytes = a.getEncoded

      // z of signature in (a, z)
      val zBytes = getVar[Coll[Byte]](2).get
      val z = byteArrayToBigInt(zBytes)

      val properHolder = holder == reserve.R4[GroupElement].get

      // Signature is valid if g^z = a * x^e
      val properSignature = (g.exp(z) == a.multiply(holder.exp(eInt))) && properHolder

      val keyBytes = longToByteArray(position) ++ reserveId

      val valueBytes = aBytes ++ zBytes

      val keyVal = (keyBytes, valueBytes)
      val proof = getVar[Coll[Byte]](3).get

      val nextTree: Option[AvlTree] = history.insert(Coll(keyVal), proof)
      // This will fail if the operation failed or the proof is incorrect due to calling .get on the Option
      val outputDigest: Coll[Byte] = nextTree.get.digest

      def nextNoteCorrect(noteOut: Box): Boolean = {
        val insertionPerformed = noteOut.R4[AvlTree].get.digest == outputDigest
        val sameScript = noteOut.propositionBytes == SELF.propositionBytes
        val nextHolderDefined = noteOut.R5[GroupElement].isDefined
        val valuePreserved = noteOut.value >= SELF.value
        val positionIncreased = noteOut.R6[Long].get == (position + 1)

        positionIncreased && insertionPerformed && sameScript && nextHolderDefined && valuePreserved
      }

      val changeIdx = getVar[Byte](4) // optional index of change output

      val selfValue = SELF.R6[Long].get
      val selfOutValue = selfOutput.R6[Long].get

      val outputsValid = if(changeIdx.isDefined) {
        val changeOutput = OUTPUTS(changeIdx.get)

        val changeOutValue = changeOutput.R6[Long].get

        (selfOutValue + changeOutValue) <= selfValue && // burn allowed
            selfOutValue > 0 &&
            changeOutValue > 0 &&
            nextNoteCorrect(selfOutput) &&
            nextNoteCorrect(changeOutput)
      } else {
        (selfOutValue == selfValue) && nextNoteCorrect(selfOutput)
      }

      sigmaProp(properSignature && outputsValid)
    } else {
      // redemption path

      // called by setting action variable to any negative value, -1 considered as standard by offchain apps

      val redeemOutput = OUTPUTS(0)
      val redemptionCorrect = redeemOutput.tokens(0)._1 == fromBase58("") //todo: redemption token id
      val historyCorrect = redeemOutput.R4[AvlTree].get == SELF.R4[AvlTree].get
      val redemptionDeadlineValid = redeemOutput.R5[Int].get >= HEIGHT + 1440

      sigmaProp(redemptionCorrect && historyCorrect && redemptionDeadlineValid)
    }

}