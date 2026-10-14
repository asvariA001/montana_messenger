//! The next window's public part must not open the previous standing commitment.

use montana_node::attest::Standing;
use montana_node::pulse::Cohort;
use mt_suite::standing::{weight_commit, Blind, LIFT, Q, VALUES, VALUE_DIGITS};

// An observer has only the commitment and a later public part. If that part
// is the blind, subtracting the commitment of zero removes all randomness.
// The remaining coefficients disclose every base-256 digit of every value.
fn recover_from_public_part(commitment: &[u8], published_part: [u8; 32]) -> Option<[u64; VALUES]> {
    let zero = weight_commit(0, 0, 0, Blind::of(published_part, 0))
        .ok()?
        .serialize();
    if commitment.len() != zero.len() {
        return None;
    }
    let mut values = [0u64; VALUES];
    for (index, (held, mask)) in commitment
        .chunks_exact(4)
        .zip(zero.chunks_exact(4))
        .enumerate()
    {
        let held = u32::from_le_bytes(held.try_into().ok()?);
        let mask = u32::from_le_bytes(mask.try_into().ok()?);
        if held >= Q {
            return None;
        }
        let digit = (held + Q - mask) % Q;
        if index < VALUES * VALUE_DIGITS {
            if !digit.is_multiple_of(LIFT) || digit / LIFT > u32::from(u8::MAX) {
                return None;
            }
            values[index / VALUE_DIGITS] |= u64::from(digit / LIFT) << (8 * (index % VALUE_DIGITS));
        } else if digit != 0 {
            return None;
        }
    }
    Some(values)
}

#[test]
fn a_later_public_part_cannot_open_the_actual_nodes_standing() {
    let secret = [0x64; 32];
    let window = 1_000;
    let weight = 1_234_567;
    let cohort = Cohort::of(&[Cohort::half_of(&secret)]).expect("one admitted machine");
    let branch = cohort.branch_of(&secret).expect("membership");
    let standing =
        Standing::of_the_window(&secret, window, &branch.siblings, branch.position, weight)
            .expect("a real presence and standing commitment");
    let later_public_part =
        mt_derive::nullifier::part(&secret, window + 1).expect("published later");

    // Establish that the observer reconstructs all three quantities for the
    // retired derivation, without receiving the secret in its recovery API.
    let old = weight_commit(weight, 0, window, Blind::of(later_public_part, window))
        .expect("the old derivation")
        .serialize();
    assert_eq!(
        recover_from_public_part(&old, later_public_part),
        Some([weight, 0, window])
    );
    assert_eq!(
        recover_from_public_part(&standing.commitment, later_public_part),
        None
    );
}
