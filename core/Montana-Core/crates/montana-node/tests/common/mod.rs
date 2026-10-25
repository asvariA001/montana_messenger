// What every vector of this crate needs and none of them owns: a fresh directory retained for inspection, the two public values of a person a payer needs, and the byte pattern the
// vectors draw their keys from.
//
// **One place, because seven were seven.** Each of these stood copied into every file that used it,
// and copies of one thing diverge at the first edit while both read correctly on their own. A
// helper of a vector is a thing of the tree exactly as a constant of the protocol is.
//
// Each vector binary compiles this module for itself and uses the part of it that it needs, so the
// compiler judges the rest unused in that binary and would be right to. That is a property of how
// integration vectors are built and not a dead item of this tree.
#![allow(dead_code)]

use montana_wallet::pay::Payee;
use std::path::PathBuf;

pub mod walk;

// Each run creates a fresh directory and retains it for failure inspection.
// A predictable name must never erase files left by another run or operator.
pub struct Scratch(pub PathBuf);

impl Scratch {
    pub fn named(what: &str) -> Self {
        use std::sync::atomic::{AtomicU64, Ordering};
        static NEXT: AtomicU64 = AtomicU64::new(0);
        loop {
            let nonce = NEXT.fetch_add(1, Ordering::Relaxed);
            let at =
                std::env::temp_dir().join(format!("montana-{what}-{}-{nonce}", std::process::id()));
            match std::fs::create_dir(&at) {
                Ok(()) => return Self(at),
                Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => continue,
                Err(error) => panic!("cannot create test directory: {error}"),
            }
        }
    }
}

// A pattern of bytes a vector draws a key from: one seed, one index, and nothing of chance in it, so
// a vector that fails fails the same way twice.
pub fn of(seed: u8, at: u8) -> [u8; 32] {
    let mut out = [seed; 32];
    out[31] = at;
    out
}

// A proposal of `window` carrying the roots `plane` gives for it against `cohort`: what a close of a
// window keeps, and nothing a vector verifies a signature or a proof of. Two runners give two names
// to one decision.
pub fn proposal_over(
    window: u64,
    previous: [u8; 32],
    plane: &montana_node::state::State,
    cohort: &montana_node::pulse::Cohort,
    runner: u8,
) -> mt_state::layout::Proposal {
    let roots = plane
        .roots(cohort, window, &[])
        .expect("the roots of a plane");
    mt_state::layout::Proposal {
        window,
        protocol_version: mt_state::PROTOCOL_VERSION,
        previous,
        final_beacon: [0x22; 32],
        note_root: roots.note_root,
        nullifier_root: roots.nullifier_root,
        record_root: roots.record_root,
        machine_root: roots.machine_root,
        admitted_root: roots.admitted_root,
        operations_root: roots.operations_root,
        roll: roots.roll,
        admitted_count: roots.admitted_count,
        cement_aggregate: [0x99; 32],
        cement_count: 1,
        census: montana_node::certificate::empty_census().expect("the Decree names the census"),
        suite_id: mt_net::SUITE,
        runner_pubkey: vec![runner; mt_codec::size::SIGNING_PUBLIC_KEY],
        ticket_proof: vec![0x00; mt_state::PRESENCE_PROOF_LEN],
        signature: vec![0x5E; mt_codec::size::SIGNATURE],
    }
}

// The two public values a payer is handed: the naming half of the payee and the key its notes are
// paid to. Nothing else about a payee is arranged with them.
pub fn payee_of(seed: u8) -> (Payee, [u8; 32]) {
    let nf_key = of(seed, 0);
    (
        Payee {
            half: mt_derive::note::nf_pk(&nf_key),
            note_pk: of(seed, 1),
        },
        nf_key,
    )
}
