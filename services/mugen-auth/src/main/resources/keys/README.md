# RS256 signing keys

`private.pem` is **gitignored** (`.gitignore` → `**/keys/private.pem`). A fresh
clone therefore has no private key and mugen-auth will fail to start until one
is generated. That is deliberate — a signing key in version control means anyone
with repo access can mint valid access tokens for any user.

`public.pem` **is** committed. It is not a secret, and mugen-gateway needs a copy
of it to verify tokens with zero DB calls (tasks.md 3.1).

## Regenerating

From this directory:

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private.pem
openssl rsa -in private.pem -pubout -out public.pem
```

2048-bit RSA, PKCS#8 (`-----BEGIN PRIVATE KEY-----`), which is the format
`PKCS8EncodedKeySpec` and Spring's `RsaKeyConverters` read directly — no
conversion step needed.

## After regenerating

1. Copy the new `public.pem` to mugen-gateway's resources, or the gateway will
   reject every token the auth service issues.
2. Every access and refresh token signed by the old key is now invalid. Existing
   sessions break; in production this is a full re-login event for all users.
