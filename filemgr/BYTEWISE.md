# Optional Bytewise MIME detection

Mnemosyne continues to use its Tika MIME repository by default. A deployment can opt into
[Bytewise](https://github.com/chrismattmann/bytewise) without changing the file-manager API:

```text
-Dorg.apache.oodt.mime.detector=bytewise
-Dorg.apache.oodt.bytewise.url=http://127.0.0.1:8765
-Dorg.apache.oodt.bytewise.timeoutMillis=30000
```

The Bytewise process must be warm before ingestion starts. Mnemosyne sends the original local
reference to `GET /detect/{url-encoded-path}` once when constructing the `Reference`; the resulting
MIME value then flows through the existing product and metadata path. Callers do not batch files
and do not need a workflow change.

If the service is unavailable, times out, returns a non-200 response, or omits `mime_type`, the
detector records a warning and falls back to the existing Tika repository detector. The fallback
counter is available from `BytewiseServiceMimeTypeDetector.getFallbackCount()` for deployment
health checks. An unknown detector name fails immediately instead of silently selecting a backend.

This integration is compatible with Java 8 bytecode and has been exercised under JDK 21. The
Bytewise service remains a separately deployed Python package; no neural runtime is embedded in
Mnemosyne.
