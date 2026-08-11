# Changelog

## 1.0.1

- The Tuya Access Secret is no longer written to a plaintext preferences file.
  Credentials now live in `EncryptedSharedPreferences`, sealed with an AES key
  held in the Android Keystore; a store written by 1.0.0 is migrated on first
  launch and then deleted, so upgrading does not leave the secret readable on
  disk. If the Keystore is unavailable the plugin fails closed and asks for the
  keys again rather than falling back to plaintext.
- Network responses are bounded (2 MiB) and rejected before parsing, and every
  call now carries a whole-call timeout in addition to the connect/read ones.
- Leaving a screen while it is still loading now abandons that request. A reply
  that landed after BACK could previously reopen the screen the wearer had just
  left.

## 1.0.0

- First release. Homes → rooms → devices → device controls on the glasses HUD,
  driven entirely by the R08 one-axis input (NEXT / PREV / SELECT / BACK).
- Tuya Cloud client with HMAC-SHA256 request signing, token refresh and
  per-region endpoints.
- Datapoint typing: boolean switches toggle in place, enums open a value list,
  integers open a range-clamped stepper, reported-only datapoints render dimmed.
- Settings screen on the NexusUi kit: credentials, data center, optional UID,
  connection test and the uninstall row.
