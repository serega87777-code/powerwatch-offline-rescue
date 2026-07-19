# Privacy checklist before publishing logs or screenshots

Before opening issues or publishing debugging material, remove:

- BLE MAC addresses;
- serial numbers;
- phone model / OS identifiers if you do not want them public;
- local filesystem paths and usernames;
- account names, emails, notification data, and contacts;
- screenshots showing personal data.

The repository maintainers may ask you to re-submit sanitized logs if private identifiers are included.

Recommended placeholders:

```text
AA:BB:CC:DD:EE:FF      example BLE MAC
SERIAL_REDACTED        serial number
/path/to/project       local path
```
