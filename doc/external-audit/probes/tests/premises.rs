//! Characterization of the public snapshot, not desired security acceptance tests.
//! A pass reproduces a limitation or a narrowly scoped existing control.
use mt_crypto::{keypair_from_seed, SIGNATURE_SIZE};
use mt_messenger_e2e::media::{open_blob, seal_blob};
use mt_overlay::inbox::bucket_len;
use mt_overlay::muq::{sign_deposit, sign_subscribe_relay, HostDeposit, Queue};
use mt_overlay::queue_host::{QueueHost, SubscribeError};

#[test]
fn authenticated_sealing_preserves_the_plaintext_length_difference() {
    let key = [42; 32];
    let short = seal_blob(&key, &[1; 12], &[7; 16]);
    let long = seal_blob(&key, &[2; 12], &[7; 4096]);
    assert_eq!((short.len(), long.len()), (44, 4124));
    assert_eq!(long.len() - short.len(), 4096 - 16);
    assert_eq!(open_blob(&key, &short).unwrap(), vec![7; 16]);
    assert_eq!(open_blob(&key, &long).unwrap(), vec![7; 4096]);
    let mut altered = short;
    altered[12] ^= 1;
    assert!(open_blob(&key, &altered).is_none());
}

#[test]
fn bucket_padding_is_not_a_single_wire_width() {
    assert_eq!(bucket_len(16), Some(256));
    assert_eq!(bucket_len(257), Some(1024));
    assert_ne!(bucket_len(16), bucket_len(257));
}

fn queue() -> (QueueHost, Queue, mt_crypto::SecretKey, mt_crypto::SecretKey) {
    let (rpk, rsk) = keypair_from_seed(&[11; 32]).unwrap();
    let (spk, ssk) = keypair_from_seed(&[22; 32]).unwrap();
    let q = Queue::generate(*rpk.as_bytes(), Some(*spk.as_bytes()), 1000, 64).unwrap();
    let mut host = QueueHost::new();
    assert!(host.register_queue(q.clone(), 0));
    (host, q, rsk, ssk)
}

#[test]
fn a_queue_identifier_accepts_multiple_distinct_authorized_messages() {
    let (mut host, q, _, ssk) = queue();
    for n in [1, 2] {
        let mut deposit = HostDeposit {
            send_id: q.send_id,
            msg_id: [n; 16],
            ttl_windows: 10,
            shard_index: 0,
            shard_total: 1,
            nonce: [n; 16],
            ct: vec![n; 64],
            sig: [0; SIGNATURE_SIZE],
        };
        deposit.sig = *sign_deposit(&ssk, &deposit.send_id, &deposit.msg_id, &deposit.nonce)
            .unwrap()
            .as_bytes();
        host.deposit(&deposit, 100).unwrap();
    }
    assert_eq!(host.buffer_of(&q.recv_id).len(), 2);
}

#[test]
fn one_time_fetch_nonce_is_distinct_from_the_queue_identifier() {
    let (mut host, q, rsk, _) = queue();
    let nonce = host.issue_nonce(&q.recv_id);
    let sig = sign_subscribe_relay(&rsk, &q.recv_id, &nonce).unwrap();
    assert!(host.subscribe_relay(&q.recv_id, &nonce, &sig).is_ok());
    assert_eq!(
        host.subscribe_relay(&q.recv_id, &nonce, &sig),
        Err(SubscribeError::Replay)
    );
    let fresh = host.issue_nonce(&q.recv_id);
    let sig = sign_subscribe_relay(&rsk, &q.recv_id, &fresh).unwrap();
    assert!(host.subscribe_relay(&q.recv_id, &fresh, &sig).is_ok());
}
