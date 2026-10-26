// VAL-08 / F-562-1: credit of W-1 is durable only with the verified close of W.
// The mutation this rejects credits Machine before take_the_proposal: even though the
// proposal is withheld, its credited root then survives refusal and restart.
use montana_node::pulse::{self, withhold, Cohort};
use montana_node::{Configuration, Machine, NodeError};
use std::cell::RefCell;
use std::rc::Rc;

mod common;
use common::Scratch;

#[test]
fn a_refused_proposal_leaves_the_previous_credit_root_across_restart() {
    let at = Scratch::named("refused-close-credit");
    let secret = [0x6Du8; 32];
    mt_store::Store::open(&at.0)
        .unwrap()
        .bear_secret(&secret)
        .unwrap();
    let config = Configuration::at(&at.0, "127.0.0.1:0");
    let (machine, _) = Machine::start(&config).unwrap();
    let cohort = Cohort::of(&[Cohort::half_of(&secret)]).unwrap();
    let before = RefCell::new(None);
    let errand = |machine: &Machine, window| {
        if window == 2 {
            *before.borrow_mut() = Some((
                machine.value()?.encode()?,
                std::fs::read(at.0.join("window")).unwrap(),
                machine.head()?,
                machine.closed_window()?,
            ));
        }
        Ok(())
    };
    let candidate = Rc::new(RefCell::new(None));
    let seen = Rc::clone(&candidate);
    withhold::own_proposals(Some(Box::new(move |_, proposal| {
        if proposal.window == 2 {
            *seen.borrow_mut() = Some(proposal.credited_root);
            true
        } else {
            false
        }
    })));
    let result = pulse::live_doing(&machine, &cohort, 0, 3, 4, Some(&errand)).map(|_| ());
    withhold::own_proposals(None);
    assert!(
        matches!(
            result,
            Err(NodeError::Refused {
                what: "a window no proposal of its decision ever stood at"
            })
        ),
        "{result:?}"
    );
    let (plane, record, head, closed) = before.into_inner().unwrap();
    let old_root = machine.value().unwrap().credited().bytes();
    assert_ne!(
        candidate.borrow().unwrap(),
        old_root,
        "the refused candidate must actually include the new credit"
    );
    assert_eq!(machine.value().unwrap().encode().unwrap(), plane);
    assert_eq!(std::fs::read(at.0.join("window")).unwrap(), record);
    assert_eq!(machine.head().unwrap(), head);
    assert_eq!(machine.closed_window().unwrap(), closed);
    assert!(machine.proposal_of(2).unwrap().is_none());
    drop(machine);
    let (again, born) = Machine::start(&config).unwrap();
    assert!(!born);
    assert_eq!(again.value().unwrap().encode().unwrap(), plane);
    assert_eq!(again.head().unwrap(), head);
    assert_eq!(again.closed_window().unwrap(), closed);
    assert!(again.proposal_of(2).unwrap().is_none());
    let part = mt_derive::nullifier::part(&secret, 1).unwrap();
    assert!(again.value().unwrap().credited_branch(&part).is_none());
    pulse::live(&again, &cohort, 2, 1, 4).unwrap();
    let accepted_root = again.proposal_of(2).unwrap().unwrap().credited_root;
    assert_eq!(again.value().unwrap().credited().bytes(), accepted_root);
    assert!(again.value().unwrap().credited_branch(&part).is_some());
    drop(again);
    let (final_restart, _) = Machine::start(&config).unwrap();
    assert_eq!(final_restart.closed_window().unwrap(), Some(2));
    assert_eq!(
        final_restart.value().unwrap().credited().bytes(),
        accepted_root
    );
}
