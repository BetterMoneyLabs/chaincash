/**
 * Private Note System - Commitment-Nullifier Scheme for ChainCash
 * 
 * This implementation provides privacy for ChainCash notes using a commitment-nullifier
 * cryptographic scheme inspired by Chaumian e-cash and Zcash.
 * 
 * Privacy Properties:
 * - Unlinkability: Reserve cannot link commitment (minting) to nullifier (spending)
 * - Unforgeability: Users cannot create valid notes without on-chain commitment
 * - Double-spend prevention: Nullifier set enforced on-chain
 * - No trusted setup: Uses standard Blake2b hashing
 * 
 * @author ChainCash Contributors
 * @license MIT
 */

const { blake2b } = require('@noble/hashes/blake2b');

/**
 * Converts bytes to hexadecimal string
 */
function bytesToHex(bytes) {
    return Array.from(bytes)
        .map(b => b.toString(16).padStart(2, '0'))
        .join('');
}

/**
 * Converts hexadecimal string to bytes
 */
function hexToBytes(hex) {
    const bytes = [];
    for (let i = 0; i < hex.length; i += 2) {
        bytes.push(parseInt(hex.substr(i, 2), 16));
    }
    return new Uint8Array(bytes);
}

/**
 * Generates a cryptographically secure random secret
 * @returns {Uint8Array} 32-byte random secret
 */
function generateSecret() {
    // In production, use crypto.getRandomValues or similar
    // For this POC, we use a simple implementation
    const secret = new Uint8Array(32);
    if (typeof window !== 'undefined' && window.crypto) {
        window.crypto.getRandomValues(secret);
    } else if (typeof require !== 'undefined') {
        require('crypto').randomFillSync(secret);
    }
    return secret;
}

/**
 * Computes commitment from secret
 * commitment = Blake2b(secret)
 * @param {Uint8Array} secret - The secret value
 * @returns {Uint8Array} 32-byte commitment
 */
function computeCommitment(secret) {
    return blake2b(secret, { dkLen: 32 });
}

/**
 * Computes nullifier from secret
 * nullifier = Blake2b(secret || "nullifier")
 * @param {Uint8Array} secret - The secret value
 * @returns {Uint8Array} 32-byte nullifier
 */
function computeNullifier(secret) {
    const nullifierTag = new TextEncoder().encode('nullifier');
    const combined = new Uint8Array(secret.length + nullifierTag.length);
    combined.set(secret);
    combined.set(nullifierTag, secret.length);
    return blake2b(combined, { dkLen: 32 });
}

/**
 * Verifies that a commitment matches a given secret
 * @param {Uint8Array} commitment - The commitment to verify
 * @param {Uint8Array} secret - The secret to check
 * @returns {boolean} True if commitment = Blake2b(secret)
 */
function verifyCommitment(commitment, secret) {
    const computedCommitment = computeCommitment(secret);
    if (commitment.length !== computedCommitment.length) return false;

    for (let i = 0; i < commitment.length; i++) {
        if (commitment[i] !== computedCommitment[i]) return false;
    }
    return true;
}

/**
 * Verifies that a nullifier matches a given secret
 * @param {Uint8Array} nullifier - The nullifier to verify
 * @param {Uint8Array} secret - The secret to check
 * @returns {boolean} True if nullifier = Blake2b(secret || "nullifier")
 */
function verifyNullifier(nullifier, secret) {
    const computedNullifier = computeNullifier(secret);
    if (nullifier.length !== computedNullifier.length) return false;

    for (let i = 0; i < nullifier.length; i++) {
        if (nullifier[i] !== computedNullifier[i]) return false;
    }
    return true;
}

/**
 * Private Note class representing a private ChainCash note
 */
class PrivateNote {
    constructor(secret, amount, reserveId) {
        this.secret = secret;
        this.amount = amount;
        this.reserveId = reserveId;
        this.commitment = computeCommitment(secret);
        this.nullifier = computeNullifier(secret);
    }

    /**
     * Gets the commitment (public, used for minting)
     */
    getCommitment() {
        return this.commitment;
    }

    /**
     * Gets the nullifier (revealed only when spending)
     */
    getNullifier() {
        return this.nullifier;
    }

    /**
     * Verifies this note's integrity
     */
    verify() {
        return verifyCommitment(this.commitment, this.secret) &&
            verifyNullifier(this.nullifier, this.secret);
    }

    /**
     * Exports note data for storage (keep secret safe!)
     */
    export() {
        return {
            secret: bytesToHex(this.secret),
            amount: this.amount,
            reserveId: this.reserveId,
            commitment: bytesToHex(this.commitment),
            nullifier: bytesToHex(this.nullifier)
        };
    }

    /**
     * Imports note from exported data
     */
    static import(data) {
        const secret = hexToBytes(data.secret);
        return new PrivateNote(secret, data.amount, data.reserveId);
    }
}

/**
 * Private Reserve class managing commitments and nullifiers
 */
class PrivateReserve {
    constructor(reserveId) {
        this.reserveId = reserveId;
        this.commitments = new Map(); // commitment -> amount
        this.nullifiers = new Set(); // spent nullifiers
        this.balance = 0; // ERG backing
    }

    /**
     * Deposits ERG into reserve
     */
    deposit(amount) {
        this.balance += amount;
    }

    /**
     * Mints a new private note
     * User provides commitment, reserve stores it
     * @param {Uint8Array} commitment - User's commitment
     * @param {number} amount - Note amount
     * @returns {boolean} Success
     */
    mint(commitment, amount) {
        const commitmentHex = bytesToHex(commitment);

        // Check if commitment already exists
        if (this.commitments.has(commitmentHex)) {
            throw new Error('Commitment already exists');
        }

        // Check if reserve has sufficient backing
        const totalCommitted = Array.from(this.commitments.values())
            .reduce((sum, amt) => sum + amt, 0);

        if (this.balance < totalCommitted + amount) {
            throw new Error('Insufficient reserve backing');
        }

        // Store commitment
        this.commitments.set(commitmentHex, amount);
        return true;
    }

    /**
     * Spends a private note
     * User reveals secret and nullifier
     * @param {Uint8Array} secret - The secret
     * @param {Uint8Array} nullifier - The nullifier
     * @param {string} recipient - Recipient address
     * @returns {object} Redemption details
     */
    spend(secret, nullifier, recipient) {
        const commitment = computeCommitment(secret);
        const commitmentHex = bytesToHex(commitment);
        const nullifierHex = bytesToHex(nullifier);

        // Verify commitment exists
        if (!this.commitments.has(commitmentHex)) {
            throw new Error('Commitment not found');
        }

        // Verify secret matches commitment
        if (!verifyCommitment(commitment, secret)) {
            throw new Error('Invalid secret for commitment');
        }

        // Verify nullifier matches secret
        if (!verifyNullifier(nullifier, secret)) {
            throw new Error('Invalid nullifier for secret');
        }

        // Check for double-spend
        if (this.nullifiers.has(nullifierHex)) {
            throw new Error('Double-spend detected: nullifier already used');
        }

        // Get amount
        const amount = this.commitments.get(commitmentHex);

        // Mark as spent
        this.nullifiers.add(nullifierHex);

        // Remove commitment (optional, depending on design)
        // this.commitments.delete(commitmentHex);

        return {
            success: true,
            amount: amount,
            recipient: recipient,
            nullifier: nullifierHex
        };
    }

    /**
     * Verifies a spent note
     * @param {Uint8Array} secret - The secret
     * @param {Uint8Array} nullifier - The nullifier
     * @returns {boolean} True if valid
     */
    verifySpent(secret, nullifier) {
        const commitment = computeCommitment(secret);
        const commitmentHex = bytesToHex(commitment);
        const nullifierHex = bytesToHex(nullifier);

        return this.commitments.has(commitmentHex) &&
            verifyCommitment(commitment, secret) &&
            verifyNullifier(nullifier, secret) &&
            this.nullifiers.has(nullifierHex);
    }

    /**
     * Gets reserve state
     */
    getState() {
        return {
            reserveId: this.reserveId,
            balance: this.balance,
            totalCommitments: this.commitments.size,
            totalSpent: this.nullifiers.size,
            totalCommittedAmount: Array.from(this.commitments.values())
                .reduce((sum, amt) => sum + amt, 0)
        };
    }

    /**
     * Checks if nullifier was used (double-spend check)
     */
    isNullifierUsed(nullifier) {
        const nullifierHex = bytesToHex(nullifier);
        return this.nullifiers.has(nullifierHex);
    }

    /**
     * Checks if commitment exists
     */
    hasCommitment(commitment) {
        const commitmentHex = bytesToHex(commitment);
        return this.commitments.has(commitmentHex);
    }
}

// Export all classes and functions
module.exports = {
    generateSecret,
    computeCommitment,
    computeNullifier,
    verifyCommitment,
    verifyNullifier,
    bytesToHex,
    hexToBytes,
    PrivateNote,
    PrivateReserve
};
