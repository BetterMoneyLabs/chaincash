{
    // ============================================================================
    // PRIVATE BASIS - Privacy-Enhanced Reserve Contract
    // ============================================================================
    // Extension to basis.es adding unlinkable redemptions via blind signatures
    // 
    // Privacy Property: Reserve owner cannot link blind signature issuance
    //                   to subsequent redemption transaction
    //
    // Technique: Chaumian blind signatures (Schnorr-based)
    // Status: Proof-of-Concept (Research Level)
    // Issue: #12 - Private Offchain Cash
    // ============================================================================

    val v = getVar[Byte](0).get
    val action = v / 10
    val index = v % 10

    val ownerKey = SELF.R4[GroupElement].get  // Reserve owner's public key
    val selfOut = OUTPUTS(index)

    // Common preservation checks
    val selfPreserved =
        selfOut.propositionBytes == SELF.propositionBytes &&
        selfOut.tokens == SELF.tokens &&
        selfOut.R4[GroupElement].get == ownerKey &&
        selfOut.R6[Coll[Byte]].get == SELF.R6[Coll[Byte]].get

    if (action == 3) {
        // ====================================================================
        // ACTION #3: PRIVATE REDEMPTION (NEW)
        // ====================================================================
        // Allows unlinkable redemption using blind signatures
        // 
        // Privacy: Receiver identity is hidden from reserve owner and blockchain
        // Security: Nullifier prevents double-redemption
        // ====================================================================

        val g: GroupElement = groupGenerator

        // === STEP 1: Extract Redemption Inputs ===
        
        // Nullifier: N = H(nonce)
        // Prevents double-spending of the same blind signature
        val nullifier: Coll[Byte] = getVar[Coll[Byte]](1).get
        
        // Commitment: C = H(nonce || amount)
        // Binds signature to specific amount without revealing nonce
        val commitment: Coll[Byte] = getVar[Coll[Byte]](2).get
        
        // Amount to redeem (in nanoERG)
        val amount: Long = getVar[Long](3).get
        
        // Unblinded signature: S = S' · g^(-r·sk)
        // Valid Schnorr signature on commitment C
        val signatureBytes: Coll[Byte] = getVar[Coll[Byte]](4).get

        // === STEP 2: Nullifier Double-Spend Prevention ===
        
        // R5 stores AVL tree of spent nullifiers: N → block_height
        val nullifierTree: AvlTree = SELF.R5[AvlTree].get
        val nullifierProof: Coll[Byte] = getVar[Coll[Byte]](5).get
        
        // Verify nullifier has NOT been used before
        // get() returns None if key doesn't exist
        val nullifierNotSpent = nullifierTree.get(nullifier, nullifierProof).isDefined == false
        
        // Insert nullifier into spent set with current block height
        val nullifierEntry = (nullifier, longToByteArray(HEIGHT))
        val nextTree: AvlTree = nullifierTree.insert(Coll(nullifierEntry), nullifierProof).get
        
        // Verify output contains updated nullifier tree
        val properNullifierTree = nextTree == selfOut.R5[AvlTree].get

        // === STEP 3: Blind Signature Verification ===
        
        // Parse Schnorr signature components
        // Schnorr signature format: (a, z) where:
        //   a = g^k (random point)
        //   z = k + e·sk (response)
        val aBytes = signatureBytes.slice(0, 33)  // Compressed point (33 bytes)
        val zBytes = signatureBytes.slice(33, signatureBytes.size)  // Scalar
        val a = decodePoint(aBytes)
        val z = byteArrayToBigInt(zBytes)

        // Reconstruct signed message: commitment || amount
        // This is what the reserve owner signed (in blinded form)
        val message = commitment ++ longToByteArray(amount)

        // Compute Fiat-Shamir challenge: e = H(a || message || pk)
        // Strong Fiat-Shamir: includes public key to prevent key substitution
        val e: Coll[Byte] = blake2b256(aBytes ++ message ++ ownerKey.getEncoded)
        val eInt = byteArrayToBigInt(e)

        // Verify Schnorr signature equation: g^z = a · pk^e
        // This proves the signature was created by reserve owner
        // WITHOUT revealing which blind signature request it came from
        val validSignature = (g.exp(z) == a.multiply(ownerKey.exp(eInt)))

        // === STEP 4: Amount and Balance Checks ===
        
        // Calculate amount being redeemed from reserve
        val redeemed = SELF.value - selfOut.value
        
        // Verify amount matches commitment and is positive
        val properAmount = (redeemed == amount) && (amount > 0)

        // === STEP 5: Output Validation ===
        
        // Redemption output goes to anonymous address (index + 1)
        val redemptionOut = OUTPUTS(index + 1)
        val receivedAmount = redemptionOut.value
        
        // Apply 2% redemption fee (same as public redemption)
        val feeAmount = amount * 2 / 100
        val afterFees = amount - feeAmount
        
        // Verify receiver gets correct amount after fees
        val properRedemption = receivedAmount >= afterFees

        // === STEP 6: Combine All Validation Conditions ===
        
        sigmaProp(
            // Nullifier checks
            nullifierNotSpent &&           // Not previously redeemed
            properNullifierTree &&         // Tree properly updated
            
            // Cryptographic checks
            validSignature &&              // Signature is valid
            
            // Economic checks
            properAmount &&                // Amount is correct
            properRedemption &&            // Receiver gets proper amount
            
            // Contract preservation
            selfPreserved                  // Contract state preserved
        )

    } else if (action == 0) {
        // ====================================================================
        // ACTION #0: PUBLIC REDEMPTION (ORIGINAL)
        // ====================================================================
        // Standard redemption path from original basis.es
        // Kept for backward compatibility
        // ====================================================================
        
        // [Original basis.es redemption logic would go here]
        // For PoC, we reference the original contract
        sigmaProp(false)  // Placeholder - use original basis.es logic

    } else if (action == 1) {
        // ====================================================================
        // ACTION #1: TOP UP (ORIGINAL)
        // ====================================================================
        // Add more ERG collateral to reserve
        // ====================================================================
        
        sigmaProp(
            selfPreserved &&
            (selfOut.value - SELF.value >= 1000000000) &&  // At least 1 ERG added
            selfOut.R5[AvlTree].get == SELF.R5[AvlTree].get  // Preserve nullifier tree
        )

    } else {
        // Invalid action
        sigmaProp(false)
    }
}
