# Changelog

## 1.0.0

- First release. Homes → rooms → devices → device controls on the glasses HUD,
  driven entirely by the R08 one-axis input (NEXT / PREV / SELECT / BACK).
- Tuya Cloud client with HMAC-SHA256 request signing, token refresh and
  per-region endpoints.
- Datapoint typing: boolean switches toggle in place, enums open a value list,
  integers open a range-clamped stepper, reported-only datapoints render dimmed.
- Settings screen on the NexusUi kit: credentials, data center, optional UID,
  connection test and the uninstall row.
