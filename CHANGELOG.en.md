# Changelog

## 0.0.9

- Fix startup progress overflowing across stages with many mods.
- Keep unchanged files in the stage's final count; report Connector and FFAPI outcomes separately.
- Show download attempt and byte progress; ignore events from old stages and attempts.
- Add optional segmented file downloads, off by default. Concurrent files and ranges share global and per-host request limits.
- Share timeout and stall protection with single-stream downloads. Queueing, probes, ranges, and fallback consume the same total deadline. Error bodies retain a three-second cap.
- Fix leaked request capacity after interrupted waits, and stop cancelled downloads from falling back and continuing.
- Stop segment workers and clean temporary parts when a transfer ends.
- Validate the full Content-Range and content encoding; do not send weak ETags in If-Range. Invalid or compressed responses cannot be accepted as valid ranges.
- Simplify the bilingual READMEs and use standard path separators in source archives.

Segmenting does not guarantee better speed. Initial installation or activation of Connector / FFAPI still requires one restart; dynamic mounting is not supported.

## 0.0.8

- Rewrite configuration text in both languages.
- Improve target-file protection, duplicate detection across multiple mod IDs, negative-cache fingerprints, and interrupted replacement recovery.
- Keep the recovery journal when both placement and restoration fail.
- Make archive ordering and timestamps reproducible.

Use the checksum list attached to each Release to identify published binaries. Historical test counts and unpublished candidate hashes do not identify the final release.
