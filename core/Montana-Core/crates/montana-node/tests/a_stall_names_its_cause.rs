// CNS-20: these reject a live pulse that conflates absent evidence, refused proof and absent quorum.
use montana_node::{pulse, say::Verdict, NodeError};
use mt_state::Layout;
mod common;
use common::{stall, walk};

#[test]
fn no_quorum_is_named_when_closing_pledges_are_withheld() {
    let (_at, machine, cohort) = stall::machine("stall-quorum");
    let errand = |machine: &montana_node::Machine, _| stall::cut_closing_pledges(machine, &cohort);
    let error = pulse::live_doing(&machine, &cohort, 0, 1, 1, Some(&errand))
        .err()
        .expect("a stalled pulse");
    assert!(
        matches!(error, NodeError::Stalled { on: Verdict::NoQuorum, what } if what.contains("agreement undecided")),
        "{error:?}"
    );
    assert_eq!(machine.counts().status().unwrap().on, Verdict::NoQuorum);
}

#[test]
fn no_data_is_named_when_no_beacon_can_be_collected() {
    let (_at, machine, cohort) = stall::machine("stall-data");
    let errand = |machine: &montana_node::Machine, _| stall::withhold(machine);
    let error = pulse::live_doing(&machine, &cohort, 0, 1, 1, Some(&errand))
        .err()
        .expect("a stalled pulse");
    assert!(
        matches!(
            error,
            NodeError::Stalled {
                on: Verdict::NoData,
                ..
            }
        ),
        "{error:?}"
    );
    assert_eq!(machine.counts().status().unwrap().on, Verdict::NoData);
}

#[test]
fn proof_refused_precedes_the_data_missing_for_a_beacon() {
    let (_at, machine, cohort) = stall::machine("stall-proof");
    let errand = |machine: &montana_node::Machine, _| {
        let previous = montana_node::certificate::Predecessor::Genesis.id()?;
        let mut beacon = walk::opening(0, previous);
        beacon.cement_count = 1; // No valid heavy leaf supplies the claimed cement.
        let (mut heavies, _, _) =
            walk::heavies(&stall::SECRETS[..1], &cohort, 0, &beacon, previous);
        heavies[0].signature[0] ^= 1; // An actual verifier refusal, not a fabricated counter.
        stall::put(machine, &beacon.bytes().unwrap())?;
        stall::put(machine, &heavies[0].bytes().unwrap())?;
        stall::withhold(machine)
    };
    let error = pulse::live_doing(&machine, &cohort, 0, 1, 1, Some(&errand))
        .err()
        .expect("a stalled pulse");
    assert!(
        matches!(
            error,
            NodeError::Stalled {
                on: Verdict::ProofRefused,
                ..
            }
        ),
        "{error:?}"
    );
    assert!(machine.counts().refusals() > 0);
    assert_eq!(machine.counts().status().unwrap().on, Verdict::ProofRefused);
}
