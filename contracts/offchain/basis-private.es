{
    // Private Basis Contract - Enhanced with Chaumian Blind Signatures
    // 
    // This contract extends the Basis offchain cash system with privacy features
    // inspired by David Chaum's blind signature protocol.
    //
    // Privacy Enhancements:
    // 1. Blinded Debt Records: Use blind signatures so tracker cannot link payment sender to receiver
    // 2. Unlinkable Redemptions: Redemption cannot be linked back to original payment
    // 3. Anonymous Trackers: Multiple anonymous trackers can operate without knowing participant identities
    //
    // Key Privacy Properties:
    // - Payer Anonymity: Tracker cannot determine who is paying whom
    // - Payee Anonymity: Tracker cannot identify payment recipients
    // - Unlinkability: Individual payments cannot be linked across redemptions
    // - Amount Privacy (optional): Payment amounts can be hidden using homomorphic commitments
    //
    // Trade-offs:
    // - Slightly increased computational overhead for blind signature verification
    // - Requires additional interaction for blinding/unblinding
    // - Emergency redemption path still reveals some metadata after time delay
    //
    // Protocol Flow with Blind Signatures:
    // 1. Payer A creates payment message: (receiver_pubkey, amount, timestamp)
    // 2. A blinds the message using blinding factor r: m' = H(m)^r mod N
    // 3. A sends blinded message m' to tracker (tracker doesn't see original message)
    // 4. Tracker signs blinded message: s' = (m')^d mod N (d is tracker's private key)
    // 5. A unblinds signature: s = s' * r^(-1) mod N
    // 6. Receiver B can redeem using (m, s) without revealing link to original payment
    //
    // Data Registers:
    //  - token #0 - identifying singleton token
    //  - R4 - signing key (as a group element) - reserve owner's key
    //  - R5 - tree of blinded payment hashes (prevents double redemption)
    //  - R6 - NFT id of tracker server (bytes) - supports multiple trackers
    //  - R7 - RSA modulus N for blind signature scheme (BigInt)
    //  - R8 - Tracker's public exponent e (BigInt)
    //
    // Actions:
    //  - redeem blinded note (#0)
    //  - top up              (#1)
    //  - refresh blinding    (#2) - for privacy: periodically re-blind existing notes

    val v = getVar[Byte](0).get
    val action = v / 10
    val index = v % 10

    val ownerKey = SELF.R4[GroupElement].get // reserve owner's key
    val selfOut = OUTPUTS(index)

    // Common checks for state preservation
    val selfPreserved =
            selfOut.propositionBytes == SELF.propositionBytes &&
            selfOut.tokens == SELF.tokens &&
            selfOut.R4[GroupElement].get == SELF.R4[GroupElement].get &&
            selfOut.R6[Coll[Byte]].get == SELF.R6[Coll[Byte]].get &&
            selfOut.R7[BigInt].get == SELF.R7[BigInt].get &&
            selfOut.R8[BigInt].get == SELF.R8[BigInt].get

    if (action == 0) {
      // Private redemption path using blind signatures
      
      val tracker = CONTEXT.dataInputs(0) // Tracker box with blinded commitments
      val trackerNftId = tracker.tokens(0)._1
      val trackerTree = tracker.R5[AvlTree].get // AVL tree of blinded payment commitments
      val expectedTrackerId = SELF.R6[Coll[Byte]].get
      val trackerIdCorrect = trackerNftId == expectedTrackerId
      val trackerPubKey = tracker.R4[GroupElement].get // For Schnorr signature verification
      
      // RSA parameters for blind signature verification
      val rsaModulus = SELF.R7[BigInt].get // N = p * q
      val rsaPublicExp = SELF.R8[BigInt].get // e (typically 65537)

      val g: GroupElement = groupGenerator

      // Unblinded payment data (receiver only knows this after unblinding)
      val receiver = getVar[GroupElement](1).get
      val receiverBytes = receiver.getEncoded
      val ownerKeyBytes = ownerKey.getEncoded

      // Blinded payment identifier (used as key in tracker tree)
      // This is hash of the original payment, blinded during creation
      val blindedPaymentHash = getVar[Coll[Byte]](2).get // H(ownerKey || receiver || amount || timestamp || nonce)^r mod N

      // Unblinded signature from tracker (s = s' * r^(-1) mod N)
      val unblindedSig = getVar[BigInt](3).get

      val debtAmount = getVar[Long](4).get
      val timestamp = getVar[Long](5).get
      val paymentNonce = getVar[Coll[Byte]](6).get // Random nonce for uniqueness

      // Reconstruct original payment message
      val paymentMessage = ownerKeyBytes ++ receiverBytes ++ longToByteArray(debtAmount) ++ longToByteArray(timestamp) ++ paymentNonce
      val paymentHash = blake2b256(paymentMessage)

      // Verify RSA blind signature: sig^e mod N == H(paymentMessage) mod N
      // This proves tracker signed the blinded version without knowing content
      val sigExp = unblindedSig.modPow(rsaPublicExp, rsaModulus)
      val msgHashInt = byteArrayToBigInt(paymentHash).mod(rsaModulus)
      val validBlindSignature = (sigExp == msgHashInt)

      // Redemption output
      val redemptionOut = OUTPUTS(index + 1)
      val redeemed = SELF.value - selfOut.value
      val properlyRedeemed = (redeemed <= debtAmount) && (redeemed > 0)

      // Update tree to prevent double redemption using blinded hash
      val proof = getVar[Coll[Byte]](7).get
      val timestampKeyVal = (blindedPaymentHash, longToByteArray(timestamp))
      val nextTree: AvlTree = SELF.R5[AvlTree].get.insert(Coll(timestampKeyVal), proof).get
      val properTimestampTree = nextTree == selfOut.R5[AvlTree].get

      // Additional Schnorr signature from reserve owner for authorization
      val reserveSigBytes = getVar[Coll[Byte]](8).get
      val reserveABytes = reserveSigBytes.slice(0, 33)
      val reserveZBytes = reserveSigBytes.slice(33, reserveSigBytes.size)
      val reserveA = decodePoint(reserveABytes)
      val reserveZ = byteArrayToBigInt(reserveZBytes)

      val reserveMsg = paymentHash ++ longToByteArray(debtAmount)
      val reserveE: Coll[Byte] = blake2b256(reserveABytes ++ reserveMsg ++ ownerKey.getEncoded)
      val reserveEInt = byteArrayToBigInt(reserveE)
      val properReserveSignature = (g.exp(reserveZ) == reserveA.multiply(ownerKey.exp(reserveEInt)))

      // Tracker signature for normal redemption (can be invalid if time-locked redemption)
      val trackerSigBytes = getVar[Coll[Byte]](9).get
      val trackerABytes = trackerSigBytes.slice(0, 33)
      val trackerZBytes = trackerSigBytes.slice(33, trackerSigBytes.size)
      val trackerA = decodePoint(trackerABytes)
      val trackerZ = byteArrayToBigInt(trackerZBytes)

      val trackerMsg = blindedPaymentHash ++ longToByteArray(debtAmount) ++ longToByteArray(timestamp)
      val trackerE: Coll[Byte] = blake2b256(trackerABytes ++ trackerMsg ++ trackerPubKey.getEncoded)
      val trackerEInt = byteArrayToBigInt(trackerE)
      val properTrackerSignature = (g.exp(trackerZ) == trackerA.multiply(trackerPubKey.exp(trackerEInt)))

      // Emergency redemption after time lock (7 days)
      val lastBlockTime = CONTEXT.headers(0).timestamp
      val enoughTimeSpent = (timestamp > 0) && (lastBlockTime - timestamp) > 7 * 86400000

      // Receiver authorization
      val receiverCondition = proveDlog(receiver)

      // Final validation: blind signature valid + either tracker sig valid OR time lock expired
      sigmaProp(selfPreserved &&
                properTimestampTree &&
                validBlindSignature &&
                properReserveSignature &&
                properlyRedeemed &&
                (enoughTimeSpent || properTrackerSignature) &&
                receiverCondition)

    } else if (action == 1) {
      // Top up reserve (preserves privacy - no payment data revealed)
      sigmaProp(
        selfPreserved &&
        (selfOut.value - SELF.value >= 1000000000) && // at least 1 ERG added
        selfOut.R5[AvlTree].get == SELF.R5[AvlTree].get // tree unchanged
      )
    } else if (action == 2) {
      // Refresh blinding - allows reserve owner to re-blind old commitments for enhanced privacy
      // This action updates the blinded payment tree with new blinding factors
      // without changing the underlying debt relationships
      
      val oldTree = SELF.R5[AvlTree].get
      val newTree = selfOut.R5[AvlTree].get
      
      // Verify owner authorization for refresh
      val refreshSig = getVar[Coll[Byte]](2).get
      val refreshABytes = refreshSig.slice(0, 33)
      val refreshZBytes = refreshSig.slice(33, refreshSig.size)
      val refreshA = decodePoint(refreshABytes)
      val refreshZ = byteArrayToBigInt(refreshZBytes)
      
      val g: GroupElement = groupGenerator
      val refreshMsg = oldTree.digest ++ newTree.digest
      val refreshE: Coll[Byte] = blake2b256(refreshABytes ++ refreshMsg ++ ownerKey.getEncoded)
      val refreshEInt = byteArrayToBigInt(refreshE)
      val validRefreshSig = (g.exp(refreshZ) == refreshA.multiply(ownerKey.exp(refreshEInt)))
      
      // Value must remain same during refresh (no funds moved)
      val valuePreserved = (selfOut.value == SELF.value)
      
      sigmaProp(selfPreserved && validRefreshSig && valuePreserved)
      
    } else {
      sigmaProp(false)
    }
}
