// What an operator's default lines may say, and where the rest goes (#22, K1). A default line names
// an event and its window; it carries no address of this machine or of another, no part of a
// nullifier, no key and no amount. Those details go to one file in the data directory, opened for
// appending and closed to its owner, and only there: the formatter of a detail runs only once that
// file stands, so a line printed where no file was opened (a test, a tool) never formats one.

use std::fs::{File, OpenOptions};
use std::io::Write;
use std::os::unix::fs::OpenOptionsExt;
use std::path::Path;
use std::sync::{Mutex, OnceLock};

static DETAILS: OnceLock<Mutex<File>> = OnceLock::new();

/// The name of the owner-only file of details, in the data directory.
pub const FILE: &str = "private.log";

/// Keeps the details of this process in the data directory, once. A file that stands keeps its
/// mode: it is appended to and never widened.
pub fn keep_at(data: &Path) -> std::io::Result<()> {
    let file = OpenOptions::new()
        .append(true)
        .create(true)
        .mode(0o600)
        .open(data.join(FILE))?;
    let _ = DETAILS.set(Mutex::new(file));
    Ok(())
}

/// Prints the public line and appends the detail, with its UTC second, to the owner-only file.
pub fn say(public: &str, detail: impl FnOnce() -> String) {
    println!("{public}");
    note(detail);
}

/// Appends a detail to the owner-only file and prints nothing.
pub fn note(detail: impl FnOnce() -> String) {
    let Some(file) = DETAILS.get() else {
        return;
    };
    let second = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    if let Ok(mut file) = file.lock() {
        let _ = writeln!(file, "{second} {}", detail());
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;

    // One test, so the order of keeping and noting within this process is the order written here.
    #[test]
    fn a_detail_is_formatted_only_into_a_file_closed_to_its_owner() {
        // Before the file stands, the formatter must not run: the public line is all there is.
        note(|| panic!("a detail was formatted with no owner-only file"));
        let data = std::env::temp_dir().join(format!("mt-private-{}", std::process::id()));
        std::fs::create_dir_all(data.as_path()).expect("a temporary directory");
        keep_at(data.as_path()).expect("the file of details stands");
        note(|| "a detail".to_string());
        let at = data.join(FILE);
        let mode = std::fs::metadata(at.as_path())
            .expect("the file stands")
            .permissions()
            .mode();
        assert_eq!(mode & 0o777, 0o600);
        let written = std::fs::read_to_string(at.as_path()).expect("the file reads");
        assert!(written.ends_with(" a detail\n"), "{written}");
        let _ = std::fs::remove_dir_all(data.as_path());
    }
}
