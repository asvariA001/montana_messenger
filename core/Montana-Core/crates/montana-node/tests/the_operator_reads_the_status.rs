#![cfg(unix)]
// CNS-20: rejects stale-file success, a second writer, and an operator verdict hidden by a prefix.
use montana_node::{pulse, status, Configuration, Machine};
use std::process::Command;
mod common;
use common::stall;

fn status_of(at: &std::path::Path) -> std::process::Output {
    Command::new(env!("CARGO_BIN_EXE_montana-node"))
        .args(["--status", "--data"])
        .arg(at)
        .output()
        .unwrap()
}

#[test]
fn the_status_is_from_the_live_process_and_disappears_with_its_lock() {
    let (at, machine, cohort) = stall::machine("operator-status");
    let machine = std::sync::Arc::new(machine);
    let server = status::Server::open(&at.0, std::sync::Arc::clone(&machine)).unwrap();
    let empty = status_of(&at.0);
    assert_eq!(empty.status.code(), Some(4));
    assert!(String::from_utf8(empty.stderr)
        .unwrap()
        .starts_with("no diagnostics"));

    let errand = |machine: &Machine, _| stall::withhold(machine);
    assert!(pulse::live_doing(&machine, &cohort, 0, 1, 1, Some(&errand)).is_err());
    let expected = machine.counts().status().unwrap().to_string();
    let read = status_of(&at.0);
    assert_eq!(
        read.status.code(),
        Some(montana_node::say::Verdict::NoData.exit_code().into())
    );
    assert_eq!(String::from_utf8(read.stderr).unwrap().trim(), expected);
    assert!(expected.starts_with("no data; asking "));

    let second = Command::new(env!("CARGO_BIN_EXE_montana-node"))
        .arg("--data")
        .arg(&at.0)
        .args(["--listen", "127.0.0.1:0"])
        .output()
        .unwrap();
    assert!(
        !second.status.success(),
        "two processes held one data directory"
    );
    assert!(Machine::start(&Configuration::at(&at.0, "127.0.0.1:0")).is_err());
    drop(server);
    drop(machine);
    // Keep the locator and socket on disk: their existence must not imply a live process.
    assert!(at.0.join("pulse-status").exists());
    let stopped = status_of(&at.0);
    assert_eq!(stopped.status.code(), Some(4));
    assert!(String::from_utf8(stopped.stderr)
        .unwrap()
        .starts_with("no diagnostics"));
    let (restarted, _) = Machine::start(&Configuration::at(&at.0, "127.0.0.1:0")).unwrap();
    let server = status::Server::open(&at.0, std::sync::Arc::new(restarted)).unwrap();
    let new = status_of(&at.0);
    assert!(
        !new.status.success(),
        "a new process inherited an old verdict"
    );
    drop(server);
}

#[test]
fn a_status_query_does_not_create_a_missing_directory() {
    let at = common::Scratch::named("missing-status");
    let missing = at.0.join("absent");
    let answer = status_of(&missing);
    assert!(!answer.status.success());
    assert!(String::from_utf8(answer.stderr)
        .unwrap()
        .starts_with("no diagnostics"));
    assert!(!missing.exists());
}
