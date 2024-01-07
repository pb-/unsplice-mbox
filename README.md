# unsplice-mbox

unsplice-mbox takes an mbox(-ish) file and separates into both a new mbox file without attachments and a directory containing attachments. Instead of the original attachments, emails will have a substitute file indicating the name of the attachment data.

Attachments are decoded (typically from base64) and stored in a content-addressable way.

unsplice-mbox was primarily tested with a single Gmail takeout containing about 100K emails over a span of 20 years. While this contained many different edge cases, it is unlikely that all situations are being handled. Feel free to open an issue or submit a patch if you encounter some parsing errors.


## Download/installation

You can get a [prebuilt release](releases/) (Java JRE required) or build it yourself (requires a JDK and Clojure):

```shell
make release
ls target/unsplice-mbox
```


## Usage

```shell
# will put attachments into output-mbox.d/
unsplice-mbox input-mbox output-mbox
```


## Rationale

Making incremental backups of recurring Gmail takeouts is fairly inefficient because the takeout is a single large file and not append-only.
