# unsplice-mbox

unsplice-mbox takes an mbox(-ish) file and separates into both a new mbox file without attachments and a directory containing attachments. Instead of the original attachments, emails will have a substitute file indicating the name of the attachment data.

Attachments are decoded (typically from base64) and stored in a content-addressable way.

unsplice-mbox was primarily tested with a single Gmail takeout containing about 100K emails over a span of 20 years. While this contained many different edge cases, it is unlikely that all situations are being handled. Feel free to open an issue or submit a patch if you encounter some parsing errors.


## Rationale

Making incremental backups of recurring Gmail takeouts is fairly inefficient because the takeout is a single large file and not append-only.
