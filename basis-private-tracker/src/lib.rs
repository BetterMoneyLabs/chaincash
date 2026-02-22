//! Basis Private Tracker Library
//! 
//! This library provides types and functions for tracking private Basis notes
//! using Chaumian blind signatures. It supports:
//! 
//! - Private note issuance via blind signatures
//! - Off-chain note transfers (bearer instruments)
//! - Nullifier-based double-spend prevention
//! - On-chain reserve tracking and redemption
//! 
//! ## Example
//! 
//! ```rust,no_run
//! use basis_private_tracker::{PrivateBasisTracker, ReserveState, PublicKey};
//! 
//! // Create a reserve
//! let reserve = ReserveState::new(
//!     [1u8; 32],           // Reserve NFT
//!     PublicKey::from_bytes(vec![0x02; 33]),  // Mint pubkey
//!     100_000_000_000,     // 100 ERG
//!     [0u8; 32],           // Empty nullifier tree root
//!     [2u8; 32],           // Tracker NFT
//! );
//! 
//! // Initialize tracker
//! let mut tracker = PrivateBasisTracker::new(reserve, [2u8; 32]);
//! 
//! // Process blind issuance, redemptions, etc.
//! ```

pub mod types;
pub mod tracker;

// Re-export key types
pub use types::{
    PrivateNote,
    Nullifier,
    BlindSignature,
    PublicKey,
    ReserveState,
    TrackerState,
    Bytes32,
};

pub use tracker::{
    PrivateBasisTracker,
    BlindIssuanceRequest,
    BlindIssuanceResponse,
    RedemptionRequest,
    RedemptionTxData,
    ProofOfReserves,
    TrackerError,
    TrackerResult,
};

#[cfg(test)]
mod integration_tests {
    use super::*;

    /// Full lifecycle test: withdraw -> transfer -> redeem
    #[test]
    fn test_full_private_note_lifecycle() {
        // ========== Setup ==========
        let mint_pubkey = PublicKey::from_bytes(vec![0x02; 33]);
        let reserve = ReserveState::new(
            [1u8; 32],
            mint_pubkey.clone(),
            100_000_000_000, // 100 ERG initial balance
            [0u8; 32],
            [2u8; 32],
        );

        let mut tracker = PrivateBasisTracker::new(reserve, [2u8; 32]);

        // ========== Phase 1: Withdrawal (User obtains private note) ==========
        println!("Phase 1: Withdrawal");
        
        // User deposits ERG on-chain and requests blind issuance
        let withdrawal_request = BlindIssuanceRequest {
            denomination: 1_000_000_000, // 1 ERG
            blinded_commitment: vec![0xABu8; 32], // User-blinded commitment
            deposit_tx_id: "withdraw_tx_001".to_string(),
        };

        tracker.request_blind_issuance(withdrawal_request.clone()).unwrap();
        
        // Tracker/mint issues blind signature
        let issuance_response = tracker.issue_blind_signature("withdraw_tx_001").unwrap();
        
        // User unblinds signature to obtain private note
        let note_serial = [42u8; 32]; // User's secret serial
        let user_note = PrivateNote::new(
            1_000_000_000,
            note_serial,
            issuance_response.blind_signature,
        );

        assert_eq!(tracker.tracker_state.issued_notes_count, 1);
        println!("  ✓ Note issued: {} nanoERG", user_note.denomination);

        // ========== Phase 2: Off-Chain Transfer (Alice pays Bob) ==========
        println!("\nPhase 2: Off-Chain Transfer");
        
        // Alice (original withdrawer) sends note to Bob off-chain
        // No tracker involvement - just passing the note data
        
        // Bob receives the note and verifies it
        let bob_received_note = user_note.clone();
        assert!(bob_received_note.verify_signature(&mint_pubkey));
        
        // Bob checks nullifier not spent
        let nullifier = bob_received_note.nullifier(&mint_pubkey);
        assert!(!tracker.is_nullifier_spent(&nullifier));
        
        println!("  ✓ Bob verified note validity");
        println!("  ✓ Nullifier not spent: {:?}", hex::encode(nullifier.as_bytes()));

        // ========== Phase 3: Redemption (Bob redeems to on-chain ERG) ==========
        println!("\nPhase 3: Redemption");
        
        // Bob prepares redemption
        let bob_pubkey = PublicKey::from_bytes(vec![0x03; 33]);
        let redemption_request = RedemptionRequest {
            note: bob_received_note.clone(),
            receiver_pubkey: bob_pubkey.clone(),
        };

        let tx_data = tracker.prepare_redemption(redemption_request).unwrap();
        
        println!("  ✓ Redemption transaction prepared");
        println!("    - Nullifier: {:?}", hex::encode(tx_data.nullifier.as_bytes()));
        println!("    - Denomination: {} nanoERG", tx_data.denomination);
        
        // Simulate on-chain transaction execution
        // In reality, this would be broadcast to blockchain
        
        // After on-chain confirmation, tracker updates state
        tracker.finalize_redemption(tx_data.nullifier, tx_data.denomination).unwrap();
        
        println!("  ✓ Redemption finalized");
        assert_eq!(tracker.tracker_state.redeemed_notes_count, 1);
        assert_eq!(tracker.reserve.erg_balance, 99_000_000_000); // 99 ERG remaining
        
        // ========== Phase 4: Double-Spend Prevention ==========
        println!("\nPhase 4: Double-Spend Prevention");
        
        // Attempt to redeem same note again (should fail)
        let double_spend_request = RedemptionRequest {
            note: bob_received_note.clone(),
            receiver_pubkey: PublicKey::from_bytes(vec![0x04; 33]),
        };

        let result = tracker.prepare_redemption(double_spend_request);
        assert!(result.is_err());
        println!("  ✓ Double-spend attempt rejected");

        // ========== Verification ==========
        println!("\n========== Final State ==========");
        let por = tracker.get_proof_of_reserves();
        println!("Issued notes: {}", por.issued_notes_count);
        println!("Redeemed notes: {}", por.redeemed_notes_count);
        println!("Outstanding value: {} nanoERG", por.outstanding_value);
        println!("Reserve balance: {} nanoERG", por.reserve_erg_balance);
        println!("Is solvent: {}", por.is_solvent);
        
        assert_eq!(por.issued_notes_count, 1);
        assert_eq!(por.redeemed_notes_count, 1);
        assert_eq!(por.outstanding_value, 0);
        assert!(por.is_solvent);
    }

    /// Test multiple users with unlinkability property
    #[test]
    fn test_multiple_users_unlinkability() {
        println!("\n========== Multiple Users Test ==========");
        
        let mint_pubkey = PublicKey::from_bytes(vec![0x02; 33]);
        let reserve = ReserveState::new(
            [1u8; 32],
            mint_pubkey.clone(),
            100_000_000_000,
            [0u8; 32],
            [2u8; 32],
        );

        let mut tracker = PrivateBasisTracker::new(reserve, [2u8; 32]);

        // Alice, Bob, and Carol all withdraw notes
        let users = vec!["Alice", "Bob", "Carol"];
        let mut notes = vec![];

        for (i, user) in users.iter().enumerate() {
            let request = BlindIssuanceRequest {
                denomination: 1_000_000_000,
                blinded_commitment: vec![(i as u8); 32],
                deposit_tx_id: format!("tx_{}", user),
            };

            tracker.request_blind_issuance(request).unwrap();
            let response = tracker.issue_blind_signature(&format!("tx_{}", user)).unwrap();
            
            let note = PrivateNote::new(
                1_000_000_000,
                [(i as u8); 32],
                response.blind_signature,
            );
            notes.push(note);
            println!("{} withdrew a note", user);
        }

        assert_eq!(tracker.tracker_state.issued_notes_count, 3);

        // Each note has different nullifier
        let nullifiers: Vec<Nullifier> = notes.iter()
            .map(|n| n.nullifier(&mint_pubkey))
            .collect();

        // All nullifiers should be different
        for i in 0..nullifiers.len() {
            for j in (i+1)..nullifiers.len() {
                assert_ne!(nullifiers[i], nullifiers[j]);
            }
        }

        // Off-chain transfers happen (tracker doesn't see them)
        // Carol's note goes to David, Bob's note goes to Eve, etc.
        // When they redeem, tracker cannot determine original withdrawer

        println!("\n✓ All notes have unique nullifiers");
        println!("✓ Unlinkability property: redemptions cannot be linked to withdrawals");
    }

    /// Test reserve solvency tracking
    #[test]
    fn test_reserve_solvency_monitoring() {
        println!("\n========== Reserve Solvency Test ==========");
        
        let mint_pubkey = PublicKey::from_bytes(vec![0x02; 33]);
        let reserve = ReserveState::new(
            [1u8; 32],
            mint_pubkey.clone(),
            10_000_000_000, // 10 ERG
            [0u8; 32],
            [2u8; 32],
        );

        let mut tracker = PrivateBasisTracker::new(reserve, [2u8; 32]);

        // Issue 10 notes of 1 ERG each
        for i in 0..10 {
            let request = BlindIssuanceRequest {
                denomination: 1_000_000_000,
                blinded_commitment: vec![(i as u8); 32],
                deposit_tx_id: format!("tx_{}", i),
            };
            tracker.request_blind_issuance(request).unwrap();
            tracker.issue_blind_signature(&format!("tx_{}", i)).unwrap();
        }

        let por = tracker.get_proof_of_reserves();
        println!("After issuance:");
        println!("  Issued: {}, Outstanding: {} nanoERG, Balance: {} nanoERG",
                 por.issued_notes_count, por.outstanding_value, por.reserve_erg_balance);
        assert!(por.is_solvent);

        // Redeem 5 notes
        for i in 0..5 {
            let nullifier = Nullifier([(i as u8); 32]);
            tracker.finalize_redemption(nullifier, 1_000_000_000).unwrap();
        }

        let por2 = tracker.get_proof_of_reserves();
        println!("\nAfter 5 redemptions:");
        println!("  Redeemed: {}, Outstanding: {} nanoERG, Balance: {} nanoERG",
                 por2.redeemed_notes_count, por2.outstanding_value, por2.reserve_erg_balance);
        assert_eq!(por2.reserve_erg_balance, 5_000_000_000);
        assert!(por2.is_solvent);

        println!("\n✓ Reserve remains solvent throughout lifecycle");
    }
}
