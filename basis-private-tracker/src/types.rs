//! Basis Private Tracker - Types and Core Structures
//! 
//! This module defines the core types for tracking private Basis notes:
//! - PrivateNote: Off-chain bearer notes with blind signatures
//! - Nullifier: Double-spend prevention identifiers
//! - ReserveState: On-chain reserve tracking
//! - BlindSignature: Placeholder for Schnorr blind signatures

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use blake2::{Blake2b512, digest::consts::U32};
use std::collections::HashSet;

pub type Blake2b256 = Blake2b512<U32>;

/// 32-byte array for serialsand nullifiers
pub type Bytes32 = [u8; 32];

/// Public key placeholder (in production, use secp256k1 Point)
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct PublicKey {
    pub bytes: Vec<u8>, // 33 bytes compressed or 65 bytes uncompressed
}

impl PublicKey {
    pub fn from_bytes(bytes: Vec<u8>) -> Self {
        Self { bytes }
    }

    pub fn as_bytes(&self) -> &[u8] {
        &self.bytes
    }
}

/// Blind Schnorr signature (A, z)
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct BlindSignature {
    pub a: Vec<u8>,  // Random point A' (33 bytes compressed)
    pub z: Vec<u8>,  // Scalar response z' (32 bytes)
}

impl BlindSignature {
    pub fn new(a: Vec<u8>, z: Vec<u8>) -> Self {
        Self { a, z }
    }

    /// Serialize to bytes for on-chain use
    pub fn to_bytes(&self) -> Vec<u8> {
        let mut bytes = Vec::new();
        bytes.extend_from_slice(&self.a);
        bytes.extend_from_slice(&self.z);
        bytes
    }

    /// Deserialize from bytes
    pub fn from_bytes(bytes: &[u8]) -> Result<Self, String> {
        if bytes.len() != 65 {
            return Err(format!("Invalid signature length: {}", bytes.len()));
        }
        Ok(Self {
            a: bytes[0..33].to_vec(),
            z: bytes[33..65].to_vec(),
        })
    }
}

/// Private Basis note - bearer instrument with blind signature
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct PrivateNote {
    pub denomination: u64,       // Amount in nanoERG
    pub serial: Bytes32,          // Random 32-byte serial number
    pub blind_signature: BlindSignature,  // Mint's signature on note commitment
}

impl PrivateNote {
    pub fn new(denomination: u64, serial: Bytes32, blind_signature: BlindSignature) -> Self {
        Self {
            denomination,
            serial,
            blind_signature,
        }
    }

    /// Compute note commitment: hash(denom || serial)
    pub fn commitment(&self) -> Bytes32 {
        let mut hasher = Blake2b256::new();
        hasher.update(&self.denomination.to_be_bytes());
        hasher.update(&self.serial);
        let result = hasher.finalize();
        let mut commitment = [0u8; 32];
        commitment.copy_from_slice(&result);
        commitment
    }

    /// Compute nullifier: hash("nullifier" || serial || mint_pubkey)
    pub fn nullifier(&self, mint_pubkey: &PublicKey) -> Nullifier {
        Nullifier::compute(&self.serial, mint_pubkey)
    }

    /// Verify blind signature (placeholder - production needs ECC ops)
    /// In production, verify: G^z == A * PK_mint^e
    /// where e = hash(A || commitment || PK_mint)
    pub fn verify_signature(&self, mint_pubkey: &PublicKey) -> bool {
        // Placeholder: In PoC tests, we'll assume signatures are valid
        // Production would use secp256k1 library to verify
        // 
        // let commitment = self.commitment();
        // let e = hash(sig.a || commitment || mint_pubkey);
        // verify_schnorr(sig.a, sig.z, e, mint_pubkey)
        
        !self.blind_signature.a.is_empty() && !self.blind_signature.z.is_empty()
    }
}

/// Nullifier - prevents double-spending
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct Nullifier(pub Bytes32);

impl Nullifier {
    /// Compute nullifier: hash("nullifier" || serial || mint_pubkey)
    pub fn compute(serial: &Bytes32, mint_pubkey: &PublicKey) -> Self {
        let mut hasher = Blake2b256::new();
        
        // Domain separation prefix
        let prefix = Blake2b256::digest(b"nullifier");
        hasher.update(&prefix);
        
        // Serial number
        hasher.update(serial);
        
        // Mint public key (binds to specific reserve)
        hasher.update(mint_pubkey.as_bytes());
        
        let result = hasher.finalize();
        let mut nullifier = [0u8; 32];
        nullifier.copy_from_slice(&result);
        Self(nullifier)
    }

    pub fn as_bytes(&self) -> &[u8; 32] {
        &self.0
    }

    pub fn from_bytes(bytes: [u8; 32]) -> Self {
        Self(bytes)
    }
}

/// Reserve contract state (on-chain)
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ReserveState {
    pub reserve_nft: Bytes32,          // Singleton NFT identifying reserve
    pub mint_pubkey: PublicKey,        // R4: Mint's public key
    pub erg_balance: u64,              // ERG value in reserve box
    pub nullifier_tree_root: Bytes32,  // R5: AVL tree root of spent nullifiers
    pub tracker_nft: Bytes32,          // R6: Tracker NFT ID
}

impl ReserveState {
    pub fn new(
        reserve_nft: Bytes32,
        mint_pubkey: PublicKey,
        erg_balance: u64,
        nullifier_tree_root: Bytes32,
        tracker_nft: Bytes32,
    ) -> Self {
        Self {
            reserve_nft,
            mint_pubkey,
            erg_balance,
            nullifier_tree_root,
            tracker_nft,
        }
    }

    /// Check if reserve is solvent (has enough ERG for outstanding notes)
    pub fn is_solvent(&self, outstanding_value: u64) -> bool {
        self.erg_balance >= outstanding_value
    }
}

/// Tracker state - maintains spent nullifier set
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct TrackerState {
    pub tracker_nft: Bytes32,
    pub spent_nullifiers: HashSet<Nullifier>,
    pub issued_notes_count: u64,
    pub redeemed_notes_count: u64,
}

impl TrackerState {
    pub fn new(tracker_nft: Bytes32) -> Self {
        Self {
            tracker_nft,
            spent_nullifiers: HashSet::new(),
            issued_notes_count: 0,
            redeemed_notes_count: 0,
        }
    }

    /// Check if a nullifier has been spent
    pub fn is_spent(&self, nullifier: &Nullifier) -> bool {
        self.spent_nullifiers.contains(nullifier)
    }

    /// Mark a nullifier as spent
    pub fn mark_spent(&mut self, nullifier: Nullifier) -> Result<(), String> {
        if self.is_spent(&nullifier) {
            return Err("Nullifier already spent (double-spend attempt)".to_string());
        }
        self.spent_nullifiers.insert(nullifier);
        self.redeemed_notes_count += 1;
        Ok(())
    }

    /// Record note issuance
    pub fn record_issuance(&mut self) {
        self.issued_notes_count += 1;
    }

    /// Calculate outstanding notes value (simplified - assumes fixed denomination)
    pub fn outstanding_notes(&self, denomination: u64) -> u64 {
        let outstanding_count = self.issued_notes_count - self.redeemed_notes_count;
        outstanding_count * denomination
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_nullifier_computation() {
        let serial = [42u8; 32];
        let mint_pubkey = PublicKey::from_bytes(vec![0x02; 33]); // Mock compressed pubkey

        let nullifier = Nullifier::compute(&serial, &mint_pubkey);
        
        // Nullifier should be deterministic
        let nullifier2 = Nullifier::compute(&serial, &mint_pubkey);
        assert_eq!(nullifier, nullifier2);

        // Different serial -> different nullifier
        let serial2 = [43u8; 32];
        let nullifier3 = Nullifier::compute(&serial2, &mint_pubkey);
        assert_ne!(nullifier, nullifier3);

        // Different mint key -> different nullifier (prevents cross-reserve replay)
        let mint_pubkey2 = PublicKey::from_bytes(vec![0x03; 33]);
        let nullifier4 = Nullifier::compute(&serial, &mint_pubkey2);
        assert_ne!(nullifier, nullifier4);
    }

    #[test]
    fn test_note_commitment() {
        let serial = [1u8; 32];
        let sig = BlindSignature::new(vec![2u8; 33], vec![3u8; 32]);
        let note = PrivateNote::new(1_000_000_000, serial, sig);

        let commitment = note.commitment();
        
        // Commitment should be deterministic
        let commitment2 = note.commitment();
        assert_eq!(commitment, commitment2);

        // Different denomination -> different commitment
        let note2 = PrivateNote::new(2_000_000_000, serial, note.blind_signature.clone());
        assert_ne!(note.commitment(), note2.commitment());
    }

    #[test]
    fn test_tracker_double_spend_prevention() {
        let mut tracker = TrackerState::new([0u8; 32]);
        let nullifier = Nullifier([1u8; 32]);

        // First spend should succeed
        assert!(!tracker.is_spent(&nullifier));
        assert!(tracker.mark_spent(nullifier).is_ok());
        assert!(tracker.is_spent(&nullifier));

        // Second spend should fail
        assert!(tracker.mark_spent(nullifier).is_err());
    }

    #[test]
    fn test_reserve_solvency() {
        let reserve = ReserveState::new(
            [0u8; 32],
            PublicKey::from_bytes(vec![0x02; 33]),
            10_000_000_000, // 10 ERG
            [0u8; 32],
            [0u8; 32],
        );

        // Solvent if outstanding < balance
        assert!(reserve.is_solvent(5_000_000_000));
        assert!(reserve.is_solvent(10_000_000_000));

        // Insolvent if outstanding > balance
        assert!(!reserve.is_solvent(15_000_000_000));
    }
}
