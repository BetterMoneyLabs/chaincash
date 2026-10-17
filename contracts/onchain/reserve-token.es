{
    // Contract for custom token reserve (e.g. SigUSD or other custom tokens)
    // Uses Gold/ERG oracle (dataInputs(0)) and Spectrum ERG/token LP pool (dataInputs(1))
    // to determine the custom token value of redeemed notes.

    // Data:
    //  - token #0 - identifying singleton token (reserve NFT)
    //  - token #1 - custom reserve token (e.g. SigUSD)
    //  - R4 - signing key (as a group element)
    //  - R5 - tree of all the note tokens issued TODO: check preservation in actions
    //
    // Actions:
    //  - redeem note (#0)
    //  - top up      (#1)
    //  - mint note   (#2)

    val v = getVar[Byte](0).get
    val action = v / 10
    val index = v % 10

    val ownerKey = SELF.R4[GroupElement].get // reserve owner's key, used in notes and unlock/lock/refund actions
    val selfOut = OUTPUTS(index)

    // common checks for all the paths (preserving contract, ERG value, reserve NFT, reserve token ID, and owner key)
    val selfPreserved =
            selfOut.propositionBytes == SELF.propositionBytes &&
            selfOut.value >= SELF.value &&
            selfOut.tokens(0) == SELF.tokens(0) &&
            selfOut.tokens(1)._1 == SELF.tokens(1)._1 &&
            selfOut.R4[GroupElement].get == SELF.R4[GroupElement].get

    if (action == 0) {
      // redemption path

      // OUTPUTS:
      // #1 - receipt
      // #2 - buyback

      val g: GroupElement = groupGenerator

      // if set, re-redemption against receipt data is done, otherwise, a note is redeemed
      val receiptMode = getVar[Boolean](4).get

      // read note data if receiptMode == false, receipt data otherwise
      val noteInput = INPUTS(index)
      val noteTokenId = noteInput.tokens(0)._1
      val noteValue = noteInput.tokens(0)._2 // 1 token == 1 mg of gold
      val history = noteInput.R4[AvlTree].get
      val reserveId = SELF.tokens(0)._1
      val reserveTokenId = SELF.tokens(1)._1

      // oracle provides gold price in nanoErg per kg in its R4 register
      val goldOracle = CONTEXT.dataInputs(0)
      // todo: externalize oracle NFT id
      // the ID below is from the mainnet
      val properOracle = goldOracle.tokens(0)._1 == fromBase16("3c45f29a5165b030fdb5eaf5d81f8108f9d8f507b31487dd51f4ae08fe07cf4a")
      val oracleRate = goldOracle.R4[Long].get / 1000000 // normalize to nanoerg per mg of gold

      // Spectrum ERG/Token liquidity pool box provides token/ERG rate:
      // tokens(0) - pool NFT
      // tokens(1) - LP share token
      // tokens(2) - traded custom token matching reserveTokenId
      val lpBox = CONTEXT.dataInputs(1)
      val properLp =
        lpBox.tokens(0)._1 == fromBase16("$lpNftId") &&
        lpBox.tokens(2)._1 == reserveTokenId

      val lpErgReserves = lpBox.value.toBigInt
      val lpTokenReserves = lpBox.tokens(2)._2.toBigInt

      // 2% redemption fee, converted from nanoErg to custom reserve token units via Spectrum pool ratio
      val maxToRedeem = ((noteValue.toBigInt * oracleRate * lpTokenReserves * 98) / (lpErgReserves * 100)).toLong
      val redeemed = SELF.tokens(1)._2 - selfOut.tokens(1)._2

      // 0.2% going to buyback contract to support oracles network
      val buyBackCorrect = if (redeemed > 0) {
        val toOracle = redeemed * 2 / 1000
        // todo: externalize buyback NFT id
        // the ID below is from the mainnet
        val buyBackNFTId = fromBase16("bf24ed4af7eb5a7839c43aa6b240697d81b196120c837e1a941832c266d3755c")
        val buyBackInput = INPUTS(2)
        val buyBackOutput = OUTPUTS(2)

        val buyBackTokenIn = if (buyBackInput.tokens.size > 1 && buyBackInput.tokens(1)._1 == reserveTokenId) {
          buyBackInput.tokens(1)._2
        } else {
          0L
        }
        val buyBackTokenOut = if (buyBackOutput.tokens.size > 1 && buyBackOutput.tokens(1)._1 == reserveTokenId) {
          buyBackOutput.tokens(1)._2
        } else {
          0L
        }

        buyBackInput.tokens(0)._1 == buyBackNFTId &&
            buyBackOutput.tokens(0)._1 == buyBackNFTId &&
            (buyBackTokenOut - buyBackTokenIn) >= toOracle
      } else {
        true
      }
      val redeemCorrect = (redeemed <= maxToRedeem) && buyBackCorrect

      val position = getVar[Long](3).get
      val positionBytes = longToByteArray(position)

      val proof = getVar[Coll[Byte]](1).get
      val key = positionBytes ++ reserveId
      val value = history.get(key, proof).get

      val aBytes = value.slice(0, 33)
      val zBytes = value.slice(33, value.size)
      val a = decodePoint(aBytes)
      val z = byteArrayToBigInt(zBytes)

      val maxValueBytes = getVar[Coll[Byte]](2).get

      val message = positionBytes ++ maxValueBytes ++ noteTokenId
      val maxValue = byteArrayToLong(maxValueBytes)

      // Computing challenge
      val e: Coll[Byte] = blake2b256(aBytes ++ message ++ ownerKey.getEncoded) // strong Fiat-Shamir
      val eInt = byteArrayToBigInt(e) // challenge as big integer

      // Signature is valid if g^z = a * x^e
      val properSignature = (g.exp(z) == a.multiply(ownerKey.exp(eInt))) &&
                             noteValue <= maxValue

      // we check that receipt is properly formed, but we do not check receipt's contract here,
      // to avoid circular dependency as receipt contract depends on (hash of) our contract,
      // thus we are checking receipt contract in note and receipt contracts
      val receiptOutIndex = if (redeemed == 0) {
         getVar[Int](5).get
      } else {
         1
      }
      val receiptOut = OUTPUTS(receiptOutIndex)
      val properReceipt =
        receiptOut.tokens(0) == noteInput.tokens(0) &&
        receiptOut.R4[AvlTree].get == history  &&
        receiptOut.R5[Long].get == position    &&
        receiptOut.R6[Int].get >= HEIGHT - 20  &&  // 20 blocks for inclusion
        receiptOut.R6[Int].get <= HEIGHT &&
        receiptOut.R7[GroupElement].get == ownerKey

      // todo: could this be checked all the time ? if so then receiptMode can be eliminated
      val positionCorrect = if (receiptMode) {
        position < noteInput.R5[Long].get
      } else {
        true
      }

      sigmaProp(selfPreserved && properOracle && properLp && redeemCorrect && properSignature && properReceipt && positionCorrect)
    } else if (action == 1) {
      // top up
      // todo: check R5 preservation
      sigmaProp(selfPreserved && (selfOut.tokens(1)._2 - SELF.tokens(1)._2 >= 1)) // at least 1 custom token unit added
    } else if (action == 2) {
      // issue a note
      // todo: check R5 preservation
      sigmaProp(selfPreserved && (selfOut.tokens(1)._2 >= SELF.tokens(1)._2))
    } else {
      sigmaProp(false)
    }

}
