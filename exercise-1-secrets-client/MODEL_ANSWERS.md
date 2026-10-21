# Exercise 1 (secrets client SDK): model answers for research and design

> Written from fact-checked research, but this file itself did not get its own fact-check pass. Check numbers and limits against the linked sources before you quote them.

Use this file in two ways:

- **After your attempt:** compare your notes with each section. Read "Common mistakes" and the "Senior signal" lines first.
- **As a practice partner:** keep the section open while the candidate talks. Tick the facts she covers. The "Senior signal" lines mark what separates a senior answer from a mid-level one.

All facts were checked on 2026-10-21 against official docs, release pages, Maven Central and source code. "UNVERIFIED" marks a claim that no primary source confirms. "Inference" marks reasoning, not a sourced fact. Acme, its endpoints and its customer numbers are fictional.

| Section | Prompt | Time box |
|---|---|---|
| 1 | Research A: remove the static API key ("secret zero") | 20-25 min |
| 2 | Research B: should `Secret.value()` be a String? | 20 min |
| 3 | Research B2: pick a cache library | 20 min |
| 4 | Design (Flow A): rotation-aware secret delivery at scale | 30-40 min |
| 5 | Design + implement B2: stale-if-error | 35-40 min |

Two product names used below:

- **Idira** is the Palo Alto Networks brand for the former CyberArk platform ([Idira](https://www.paloaltonetworks.com/idira)).
- **Secrets Manager, SaaS** is the former Conjur Cloud. **Secrets Manager, Self-Hosted** is the former Conjur Enterprise. Conjur Open Source keeps its name ([What's new](https://docs.cyberark.com/secrets-manager-saas/latest/en/content/conjurcloud/whatsnew.htm)).

---

## 1. Research A: remove the static API key ("secret zero")

### 1.1 The 5-minute spoken summary

1. **The problem.** `SecretsClientConfig` requires an `apiKey`. Every app must store that key in an env var, a file or a Kubernetes Secret. The key is long-lived and works from anywhere. This first stored secret is "secret zero".
2. **The idea.** Let the platform vouch for the workload. HashiCorp calls this "platform integration". It is one of three answers to the "secure introduction" problem ([HashiCorp](https://developer.hashicorp.com/vault/tutorials/app-integration/secure-introduction)).
3. **The flow.** The workload sends a short-lived, signed proof of its identity. The secrets manager verifies the proof and maps the identity to a policy. Then it issues its own short-lived access token, as `TokenProvider` gets one today.
4. **Kubernetes.** The kubelet mounts a projected service-account token. It is a JWT with an audience that we choose. Its lifetime is at least 10 minutes and 1 hour by default ([Projected volumes](https://kubernetes.io/docs/concepts/storage/projected-volumes/)).
5. **Token rotation.** The kubelet replaces the token when it is older than 80% of its lifetime. So the SDK must read the file again at every login ([Service accounts](https://kubernetes.io/docs/tasks/configure-pod-container/configure-service-account/)).
6. **Two ways to verify a Kubernetes token.** Offline: check the signature with the cluster's JWKS, then check `iss`, `aud` and `exp`. CyberArk `authn-jwt` and the Vault JWT method do this. Online: call the Kubernetes TokenReview API. The Vault Kubernetes method does this.
7. **The trade-off.** Offline verification scales better. Only online verification rejects the token of a deleted pod ([Managing service accounts](https://kubernetes.io/docs/reference/access-authn-authz/service-accounts-admin/)).
8. **AWS.** The workload signs an `sts:GetCallerIdentity` request with its IAM role credentials (SigV4). It sends the signed request, never the credentials. The server forwards it to STS, and STS returns the role ARN of the caller.
9. **AWS in the products.** CyberArk `authn-iam` and the Vault `iam` method work this way. GetCallerIdentity needs no IAM permission ([STS](https://docs.aws.amazon.com/STS/latest/APIReference/API_GetCallerIdentity.html)).
10. **Main risks.** A JWT is a bearer token, so the audience and the lifetime are the controls that matter. A signed STS request stays usable for minutes. Vault binds it to one server with a signed server-ID header ([Vault AWS auth](https://developer.hashicorp.com/vault/docs/auth/aws)).
11. **SDK change.** `TokenProvider` keeps its token cache and its single-flight refresh. It hands the login to a pluggable `Authenticator`: API key, Kubernetes JWT or AWS IAM.
12. **What I ship first.** Kubernetes JWT with offline verification and a dedicated audience. Keep the API key as a fallback. Ship AWS IAM next, as an optional module, so the core SDK gets no AWS dependency.

### 1.2 Sequence sketches

Kubernetes (projected service-account JWT, offline verification):

```
App + SDK (pod)                   Acme Secrets Manager              Cluster OIDC issuer
     |                                     |                                 |
     | 1. read /var/run/secrets/acme/token (JWT, aud=acme-secrets, 10 min)   |
     | 2. POST /v1/auth/k8s {"jwt": "..."} |                                 |
     |------------------------------------>|                                 |
     |                                     | 3. JWKS from cache, else        |
     |                                     |    GET /openid/v1/jwks -------->|
     |                                     | 4. verify signature, iss, aud,  |
     |                                     |    exp, nbf                     |
     |                                     | 5. map sub=system:serviceaccount:payments:api
     |                                     |    to a workload identity       |
     | 6. 200 {access_token, expires_in}   |                                 |
     |<------------------------------------|                                 |
     | 7. GET /v1/secrets/{id}  Authorization: Bearer <access token>         |
     |------------------------------------>| 8. policy check, then 200       |
```

AWS (signed GetCallerIdentity):

```
App + SDK (EC2, ECS or EKS)            Acme Secrets Manager                 AWS STS
     |                                          |                               |
     | 1. role credentials from the default chain (IMDS, Pod Identity, IRSA)    |
     | 2. SigV4-sign POST sts:GetCallerIdentity, with the signed header         |
     |    X-Acme-Server-ID: secrets.acme.example                                |
     | 3. POST /v1/auth/aws {method, url, headers, body}                        |
     |----------------------------------------->|                               |
     |                                          | 4. check the server-ID header |
     |                                          | 5. forward the signed request>|
     |                                          |<-- 6. Arn=arn:aws:sts::123456789012:assumed-role/payments-api/i-0abc
     |                                          | 7. map the role ARN to a      |
     |                                          |    workload identity          |
     | 8. 200 {access_token, expires_in}        |                               |
     |<-----------------------------------------|                               |
```

`X-Acme-Server-ID` and the Acme endpoints are fictional. They copy the Vault header `X-Vault-AWS-IAM-Server-ID` and the shape of the Conjur authenticator endpoints.

### 1.3 How CyberArk and HashiCorp implement each platform

| Platform | CyberArk Conjur / Secrets Manager | HashiCorp Vault |
|---|---|---|
| Kubernetes, main method | `authn-jwt`: `POST /authn-jwt/<service-id>/<account>/authenticate` with the form field `jwt=<token>`. Verifies offline with `jwks-uri` or static `public-keys`. Maps one claim (`token-app-property`, usually `sub`) to a host. Accepts only RS256, RS384 and RS512 ([authn-jwt](https://docs.cyberark.com/conjur-open-source/latest/en/content/operations/services/cjr-authn-jwt.htm), [source](https://github.com/cyberark/conjur/blob/master/app/domain/authentication/authn_jwt/authn_jwt.rb)). | Kubernetes auth: `POST /v1/auth/kubernetes/login {"jwt", "role"}`. Vault calls TokenReview. A role binds service-account names and namespaces ([Vault Kubernetes auth](https://developer.hashicorp.com/vault/docs/auth/kubernetes)). |
| Kubernetes, other method | `authn-k8s`: a sidecar sends a CSR, Conjur injects a client certificate into the pod through the exec API, then the client uses mTLS. It needs a ClusterRole with `pods/exec` ([authn-k8s client](https://github.com/cyberark/conjur-authn-k8s-client), [ClusterRole](https://github.com/cyberark/conjur-authn-k8s-client/blob/master/helm/conjur-config-cluster-prep/templates/clusterrole.yaml)). | JWT/OIDC auth with Kubernetes as the issuer. Vault then does not call TokenReview, so revoked tokens stay valid until they expire ([Vault with K8s OIDC](https://developer.hashicorp.com/vault/docs/auth/jwt/oidc-providers/kubernetes)). |
| AWS | `authn-iam`: the client SigV4-signs GetCallerIdentity and posts the signed headers. Conjur replays them to STS. The host ID must end in `<account>/<role>` ([authn-iam](https://github.com/cyberark/conjur/blob/master/app/domain/authentication/authn_iam/Readme.md)). | AWS auth, `iam` method: the client sends the signed method, URL, body and headers. Vault replays them to STS and matches `bound_iam_principal_arn`. The optional header `X-Vault-AWS-IAM-Server-ID` stops cross-server replay ([Vault AWS auth](https://developer.hashicorp.com/vault/docs/auth/aws)). |
| Token it returns | An access token (JWT, RSA-2048) valid for 8 minutes ([Conjur authentication](https://docs.cyberark.com/conjur-open-source/latest/en/content/operations/services/authentication-new.htm)). SaaS: 8 minutes for workloads, 60 minutes for users ([SaaS authentication](https://docs.cyberark.com/secrets-manager-saas/latest/en/content/conjurcloud/ccl-authentication.htm)). | A Vault token with policies and a lease ([Vault Kubernetes auth](https://developer.hashicorp.com/vault/docs/auth/kubernetes)). |
| Direction in 2026 | The SaaS Kubernetes overview names only the JWT authenticator ([SaaS on K8s](https://docs.cyberark.com/secrets-manager-saas/latest/en/content/integrations/k8s-ocp/k8s_lp.htm)). Secure Workload Access issues SPIFFE SVIDs, GA since 2026-05-28 ([What's new](https://docs.cyberark.com/secrets-manager-saas/latest/en/content/conjurcloud/whatsnew.htm)). | No change found for this comparison. |

### 1.4 Key facts with sources

| Topic | Fact | Source |
|---|---|---|
| Token lifetime | `expirationSeconds` is 1 hour by default and at least 600 s. The audience defaults to the API server. | [Projected volumes](https://kubernetes.io/docs/concepts/storage/projected-volumes/) |
| Rotation | The kubelet refreshes the token when it is older than 80% of its lifetime or older than 24 h. The app must reload the file. Reading it every 5 minutes is usually enough. | [Service accounts](https://kubernetes.io/docs/tasks/configure-pod-container/configure-service-account/) |
| Default token | `--service-account-extend-token-expiration` defaults to true. It stretches admission-injected tokens up to 1 year. | [kube-apiserver](https://kubernetes.io/docs/reference/command-line-tools-reference/kube-apiserver/) |
| Revocation | There is no revocation API. The API server rejects a bound token 60 s after the pod's `deletionTimestamp`. Offline verifiers do not see the deletion, so revocation needs TokenReview. | [Managing service accounts](https://kubernetes.io/docs/reference/access-authn-authz/service-accounts-admin/) |
| Key discovery | The API server publishes `/.well-known/openid-configuration` and the JWKS at `/openid/v1/jwks`. | [Service accounts](https://kubernetes.io/docs/tasks/configure-pod-container/configure-service-account/) |
| CyberArk guidance | Always set an audience. Never share a signing key pair across clusters. Use `sub` as the host ID. Never map on the service-account name alone, because it is not unique across namespaces. | [K8s JWT authn](https://docs.cyberark.com/conjur-enterprise/latest/en/content/integrations/k8s-ocp/k8s-jwt-authn.htm) |
| CyberArk JWKS handling | Conjur re-fetches the JWKS when a signature fails, at most 10 times per 300 s. | [authn_jwt.rb](https://github.com/cyberark/conjur/blob/master/app/domain/authentication/authn_jwt/authn_jwt.rb) |
| JWT vs certificate | Self-Hosted docs: JWT makes fewer Kubernetes API calls, performance is "usually better", and it "reduces the need for special privileges". | [K8s authentication options](https://docs.cyberark.com/conjur-enterprise/latest/en/content/integrations/k8s-ocp/k8s-admin-lp.htm) |
| AWS permission | GetCallerIdentity needs no permission. An explicit deny does not block it. | [STS](https://docs.aws.amazon.com/STS/latest/APIReference/API_GetCallerIdentity.html) |
| Replay window | A SigV4 request must reach AWS within 5 minutes "in most cases". S3 docs say 15 minutes. Assume 15 minutes for replay analysis. | [SigV4](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_sigv.html), [S3 SigV4](https://docs.aws.amazon.com/AmazonS3/latest/developerguide/sig-v4-authenticating-requests.html) |
| EKS | AWS recommends EKS Pod Identity "whenever possible". The EKS OIDC signing key rotates every 7 days. | [EKS service accounts](https://docs.aws.amazon.com/eks/latest/userguide/service-accounts.html), [IRSA](https://docs.aws.amazon.com/eks/latest/userguide/iam-roles-for-service-accounts.html) |
| Real Java SDK | `conjur-api-java` 3.3.0 added AWS IAM, Azure, GCP and certificate authenticators. In v3.4.0 the AWS SDK dependencies are `<optional>true</optional>`, "not pulled into consumers who don't use AWS auth". | [Releases](https://github.com/cyberark/conjur-api-java/releases), [pom.xml](https://github.com/cyberark/conjur-api-java/blob/v3.4.0/pom.xml) |
| Real Java SDK, AWS | Its `AWSIAMAuthenticator` signs GetCallerIdentity with the AWS SDK v2 `Aws4Signer` and `DefaultCredentialsProvider`, then posts the signed headers as JSON. | [AWSIAMAuthenticator.java](https://github.com/cyberark/conjur-api-java/blob/v3.4.0/src/main/java/com/cyberark/conjur/api/clients/AWSIAMAuthenticator.java) |
| Real Java SDK, Kubernetes | It can read an access token from a file (`CONJUR_AUTHN_TOKEN_FILE`). On Kubernetes, the `authn-k8s` sidecar writes such a file. I found no `authn-jwt` class in its v3.4.0 source tree. | [README](https://github.com/cyberark/conjur-api-java/blob/v3.4.0/README.md), [authn-k8s client](https://github.com/cyberark/conjur-authn-k8s-client) |

### 1.5 What `TokenProvider` would need

Today `TokenProvider` does two jobs. It logs in with `X-Api-Key`, and it caches the token with double-checked locking. Split the jobs. Keep the cache in `TokenProvider` and move the login into an `Authenticator`.

```java
// Outline, not compiled. Package com.acme.secrets.client.auth.
/** Proves the workload's identity once and returns a new access token. No caching here. */
public interface Authenticator {
    AccessToken authenticate();
}

/** Today's behaviour: POST {baseUri}/v1/auth/token with the X-Api-Key header. */
final class ApiKeyAuthenticator implements Authenticator { /* ... */ }

/** Reads the projected token file on every call, because the kubelet rotates it. */
final class KubernetesJwtAuthenticator implements Authenticator {
    KubernetesJwtAuthenticator(Path tokenFile, URI loginUri, HttpClient http, Clock clock) { /* ... */ }

    @Override
    public AccessToken authenticate() {
        String jwt = readTokenFile();                 // Files.readString(tokenFile).strip()
        return exchange(jwt);                         // POST /v1/auth/k8s {"jwt": "..."}
    }
}

/** Lives in an optional module (secrets-client-aws), so the core SDK has no AWS dependency. */
final class AwsIamAuthenticator implements Authenticator { /* signs sts:GetCallerIdentity */ }
```

Changes to `TokenProvider`:

- The constructor takes an `Authenticator` instead of building the API-key request.
- Keep the `volatile` field, the double-checked locking and the refresh within `tokenRefreshSkew`. SDK-106 shows what happens without them.
- Keep `invalidate()` after a 401 on a secret read, and re-authenticate once. SDK-105 shows why the new request must carry the new token.
- Treat a rejected identity (401 or 403 from the login endpoint) as final. Back off with jitter only on 429, 5xx and I/O errors.
- Never log the JWT, the signed AWS headers or the access token. Log the identity (`sub` or role ARN) and the expiry only.

Changes to `SecretsClientConfig`:

- Add `authenticator(Authenticator)`. `build()` requires exactly one of `apiKey` or `authenticator`.
- Keep `apiKey(String)`. It maps to `ApiKeyAuthenticator`, so every 2.3 caller still compiles.

Test plan: give `FakeSecretsServer` a `/v1/auth/k8s` handler that trusts a test RSA key. A test signs a JWT, writes it to a temp file, replaces the file, and uses `MutableClock` to pass `exp`.

### 1.6 Top security risks

| Risk | What goes wrong | Mitigation |
|---|---|---|
| Audience confusion | A token minted for one service works at another. The default pod token has the API server as audience, so any service that receives it can call Kubernetes as the pod ([Vault Kubernetes auth](https://developer.hashicorp.com/vault/docs/auth/kubernetes)). | Mint a token per audience (`acme-secrets`). Reject a JWT whose `aud` does not name us ([RFC 7519](https://www.rfc-editor.org/rfc/rfc7519)). |
| Token replay | A stolen JWT or signed STS request works until it expires. | Short lifetimes (10-minute JWT). Bind AWS requests with a signed server-ID header. Prefer mTLS or X.509-SVIDs where possible ([SPIFFE](https://spiffe.io/docs/latest/spiffe-about/spiffe-concepts/)). |
| Long-lived tokens | Legacy Secret-based tokens never expire. Admission-injected tokens can last 1 year. | Use a projected token with an explicit `expirationSeconds` and audience. Set `automountServiceAccountToken: false` where the app does not call Kubernetes ([Service accounts](https://kubernetes.io/docs/tasks/configure-pod-container/configure-service-account/)). |
| Key rotation | A cached JWKS misses a new `kid`, or a re-fetch on every request becomes a DoS lever. | Cache the JWKS, re-fetch on an unknown `kid`, rate-limit re-fetches ([OIDC key rotation](https://openid.net/specs/openid-connect-core-1_0.html)). |
| Clock skew | Valid tokens fail `exp` or `nbf` checks, or a large leeway accepts expired ones. | NTP everywhere. Small leeway: Vault defaults to 60 s for clock skew ([Vault JWT API](https://developer.hashicorp.com/vault/api-docs/auth/jwt)). |
| Over-broad mapping | Wildcards let any service account or role in. | No `bound_service_account_names="*"`, no `arn:aws:iam::<acct>:*` ([Vault AWS auth](https://developer.hashicorp.com/vault/docs/auth/aws)). Map `sub` 1:1 and enforce extra claims ([authn-jwt guidelines](https://docs.cyberark.com/conjur-open-source/latest/en/content/operations/services/cjr-authn-jwt-guidelines.htm)). |
| Shared signing keys | Two clusters with one key pair produce tokens that nobody can tell apart. | One issuer and one key pair per cluster, so 40 clusters means 40 authenticator configs ([K8s JWT authn](https://docs.cyberark.com/conjur-enterprise/latest/en/content/integrations/k8s-ocp/k8s-jwt-authn.htm)). |

### 1.7 Recommendation and its risks

**Ship first: Kubernetes JWT with offline verification.**

- Use a dedicated audience (`acme-secrets`), a 10-minute token lifetime, and `sub` as the workload identity.
- Reason 1: the scale customer in the design prompt runs 20,000 pods on Kubernetes.
- Reason 2: offline verification adds no call per login to 40 API servers.
- Reason 3: it matches the vendor's direction. The SaaS Kubernetes overview names only JWT, and the CSI provider "supports only JWT-based authentication" ([CSI provider](https://docs.cyberark.com/conjur-enterprise/latest/en/content/integrations/k8s-ocp/k8s-jwt-secrets-store-csi-driver.htm)).

**Ship second:** AWS IAM for ECS, Lambda and EC2, in an optional module. **Later:** a TokenReview mode for customers who need instant revocation, and SPIFFE JWT-SVIDs, which the JWT path mostly covers already (inference).

| Risk of this plan | Mitigation |
|---|---|
| A deleted pod's token works until `exp`. | Keep the lifetime at 10 minutes. Offer TokenReview mode later. |
| Private clusters: our SaaS cannot reach the JWKS. | Accept static public keys, as Conjur `public-keys` does. Alert on an unknown `kid`, because rotation then needs a manual update. |
| 40 clusters mean 40 issuers to configure. | Automate onboarding: read `issuer` and `jwks_uri` from `/.well-known/openid-configuration`. |
| Migration from API keys. | Run both methods side by side, then disable the API key per workload. |
| A compromised pod still reads its own secrets. | State the limit. Workload identity shrinks the damage, but it does not stop code execution in the pod. |

### 1.8 Common mistakes

- Using the automounted default token. Its audience is the API server, and it can last 1 year.
- Reading the token file once at startup. The kubelet replaces it.
- No dedicated audience, or a server that accepts any audience.
- Mapping on the service-account name alone. The name is not unique across namespaces.
- Believing an offline JWT check notices a deleted pod.
- Logging the JWT, the signed AWS headers or the access token.
- Wildcard bindings in roles or trust policies.
- Saying "now there are no secrets". The access token is still a bearer secret for 8-15 minutes.
- Listing every platform (Azure, GCP, SPIFFE) without a shipping order.

### 1.9 Follow-up questions and good short answers

1. **"Why not mount the API key from a Kubernetes Secret?"** The key stays static and long-lived. Kubernetes stores Secrets unencrypted in etcd by default ([K8s Secrets](https://kubernetes.io/docs/concepts/configuration/secret/)). Anyone who can read Secrets in the namespace gets the key.
2. **"Offline JWT check or TokenReview?"** Offline needs no call per login and works without a route to the cluster API. TokenReview catches deleted pods, but it needs network access and RBAC (`system:auth-delegator`) in every cluster. I start offline with 10-minute tokens.
3. **"EKS rotates its signing key every 7 days. What breaks?"** Nothing, if the server caches the JWKS and re-fetches it on an unknown `kid`. Static keys break at the next rotation, so they need an alert.
4. **"An attacker gets code execution in the pod. Now what?"** The attacker can read the token file and act as that workload. Workload identity limits the damage: tokens expire in minutes, work only for our audience, and nothing long-lived leaves the pod.
5. **"How do you test this without a cluster?"** Sign a JWT with a test key, write it to a temp file and point the fake server at the test JWKS. Use `MutableClock` for expiry. For AWS, a fake STS endpoint returns a fixed ARN.
6. **"How does Conjur map a Kubernetes identity to a host?"** `token-app-property` names the claim that holds the host ID, usually `sub`. `identity-path` adds the policy prefix. `enforced-claims` makes hosts declare more claims, such as the namespace ([authn-jwt guidelines](https://docs.cyberark.com/conjur-open-source/latest/en/content/operations/services/cjr-authn-jwt-guidelines.htm)).

### 1.10 What separates senior from mid

- **Senior signal:** She explains the offline-versus-TokenReview trade-off and ties it to revocation of deleted pods.
- **Senior signal:** She says the SDK must re-read the token file. She also says the access token is still a bearer secret, so the masking and caching rules stay.
- **Senior signal:** She keeps the AWS SDK out of the core SDK (optional dependency or module) and cites a real example (`conjur-api-java` 3.4.0).
- **Senior signal:** She plans the migration: the API key keeps working, both methods run side by side, then API keys are switched off per workload.
- **Senior signal:** She binds AWS signed requests to one server and pins one audience per relying party.

---

## 2. Research B: should `Secret.value()` be a String?

### 2.1 The 5-minute spoken summary

1. **What the JDK says.** The JCA Reference Guide says Strings are "unsuitable for storing security sensitive information such as user passwords". It says to collect and store such data in a char array ([JCA guide](https://docs.oracle.com/en/java/javase/21/security/java-cryptography-architecture-jca-reference-guide.html)).
2. **The threat.** Memory disclosure: heap dumps, core dumps and swap. A String stays readable until the GC reclaims it, and no API can wipe it ([CERT MSC59-J](https://wiki.sei.cmu.edu/confluence/display/java/MSC59-J.+Limit+the+lifetime+of+sensitive+data), [CWE-316](https://cwe.mitre.org/data/definitions/316.html)).
3. **The catch.** A `char[]` API alone protects little. Our HTTP client decodes the body into a String. Jackson builds a String for the JSON field. The cache keeps a copy for the whole TTL.
4. **The sinks.** Most consumers need a String anyway: JDBC `getConnection(url, user, password)`, HTTP headers, Kafka properties.
5. **What real SDKs do.** AWS returns a String plus `SdkBytes`. Azure returns a String. GCP returns an immutable `ByteString`. CyberArk's own `conjur-api-java` added `byte[] retrieveSecretBytes` in v3.4.0 (2026-10-01) and deprecated the String method.
6. **My recommendation.** Add a wipeable `SecretValue` type in 2.4 and keep `String value()`, deprecated. Make the whole path cooperate: bytes from HTTP, a `char[]` in the cache, zeroing on eviction. Switch the default in 3.0.
7. **The honest limit.** Zeroing shortens the exposure window. It does not guarantee erasure.
8. **The bigger wins.** Mask `toString()` (that is SDK-101), never log values, keep cache TTLs short, and protect heap dumps.

### 2.2 Key facts with sources

| Topic | Fact | Source |
|---|---|---|
| JCA guide | Strings are immutable, so they are "unsuitable" for passwords. "You should always collect and store security sensitive information in a char array instead." | [JCA guide](https://docs.oracle.com/en/java/javase/21/security/java-cryptography-architecture-jca-reference-guide.html) |
| Interning | String literals and constant expressions are interned. Runtime strings are interned only if code calls `intern()`. | [String](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/String.html) |
| Internals | Since JDK 9 a String stores a private `byte[]` plus an encoding flag. | [JEP 254](https://openjdk.org/jeps/254) |
| Reflection wipe | Since JDK 17 strong encapsulation blocks reflection into `java.lang`, unless the JVM starts with `--add-opens java.base/java.lang=ALL-UNNAMED`. | [JEP 403](https://openjdk.org/jeps/403) |
| OpenJDK position | A 2003 request for a way to clear passwords stored as Strings is closed as Won't Fix. | [JDK-4952520](https://bugs.openjdk.org/browse/JDK-4952520) |
| Limits | Full protection needs OS and JVM support. Swap, hibernation, buffer copies, logs and dumps all keep data. | [CERT MSC59-J](https://wiki.sei.cmu.edu/confluence/display/java/MSC59-J.+Limit+the+lifetime+of+sensitive+data) |
| Heap dumps | `jcmd <pid> GC.heap_dump` and `-XX:+HeapDumpOnOutOfMemoryError` write the heap to a file. | [Diagnostic tools](https://docs.oracle.com/en/java/javase/21/troubleshoot/diagnostic-tools.html) |
| JDK password APIs | `PBEKeySpec` clones the input, and `clearPassword()` wipes it. `KeyStore.PasswordProtection` clones the input, `getPassword()` returns a reference, and `destroy()` clears it. `JPasswordField.getText()` is deprecated "for security reasons". | [PBEKeySpec](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/spec/PBEKeySpec.html), [PasswordProtection](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/security/KeyStore.PasswordProtection.html), [JPasswordField](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/javax/swing/JPasswordField.html) |
| HTTP body | `BodyHandlers.ofString()` decodes into a String. `ofByteArray()` and `ofInputStream()` do not. | [BodyHandlers](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpResponse.BodyHandlers.html) |
| JSON | Jackson `getTextCharacters()` exposes the parser's internal `char[]`, which the caller must not modify. The parser buffer still holds the data. | [JsonParser](https://fasterxml.github.io/jackson-core/javadoc/2.14/com/fasterxml/jackson/core/JsonParser.html) |
| Records | The implicit `equals` compares a reference component with `Objects.equals`, so arrays compare by identity. The implicit accessor returns the field itself. | [Record](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Record.html) |

### 2.3 What SDKs actually do

| SDK | Value type | Notes | Source |
|---|---|---|---|
| AWS SDK for Java v2, `GetSecretValueResponse` | `String secretString()`, `SdkBytes secretBinary()` | `toString()` redacts. `SdkBytes.asByteArrayUnsafe()` avoids a copy. | [GetSecretValueResponse](https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/secretsmanager/model/GetSecretValueResponse.html), [SdkBytes](https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/core/SdkBytes.html) |
| Azure Key Vault Secrets 4.11.0, `KeyVaultSecret` | `String getValue()` | | [KeyVaultSecret](https://learn.microsoft.com/en-us/java/api/com.azure.security.keyvault.secrets.models.keyvaultsecret?view=azure-java-stable) |
| Google Cloud Secret Manager 2.99.0, `SecretPayload` | `ByteString getData()` | Immutable, so it cannot be wiped either. At most 64 KiB. | [SecretPayload](https://docs.cloud.google.com/java/docs/reference/google-cloud-secretmanager/latest/com.google.cloud.secretmanager.v1.SecretPayload) |
| CyberArk `conjur-api-java` 3.4.0 (2026-10-01) | `byte[] retrieveSecretBytes(String)` | Deprecates `String retrieveSecret` "to avoid keeping secrets as immutable Strings in the JVM heap". Batch retrieval still returns `Map<String,String>`. | [Releases](https://github.com/cyberark/conjur-api-java/releases), [Variables.java](https://github.com/cyberark/conjur-api-java/blob/v3.4.0/src/main/java/com/cyberark/conjur/api/Variables.java) |
| Why Conjur can do it | `GET /secrets/{account}/variable/{id}` returns the raw value as `application/octet-stream`, not JSON. | | [Retrieve a secret](https://docs.cyberark.com/conjur-open-source/latest/en/content/developer/conjur_api_retrieve_secret.htm) |

### 2.4 Recommended API

```java
// Outline, not compiled. A final class, not a record: a record would expose the array
// through its accessor and compare it by identity.
public final class SecretValue implements AutoCloseable {
    private final char[] chars;                    // the SDK's own copy
    private boolean closed;

    /** Gives the reader a temporary copy and zeroes the copy when the reader returns. */
    public synchronized <T> T apply(Function<char[], T> reader) { /* copy, call, Arrays.fill */ }

    /** Returns a copy that the caller owns and must zero. */
    public synchronized char[] copyChars() { /* throws IllegalStateException after close */ }

    /** Explicit escape hatch for String-only sinks such as JDBC. The String cannot be wiped. */
    public synchronized String asString() { /* ... */ }

    @Override
    public synchronized void close() { Arrays.fill(chars, '\0'); closed = true; }

    @Override
    public String toString() { return "****"; }    // never the value; not Serializable
}
```

| Version | Change | Breaks callers? |
|---|---|---|
| 2.4 | Add `SecretValue` and `SecretsClient.getSecretValue(String id)`, which returns a copy the caller closes. Read bodies with `ofByteArray()`, zero the body after parsing. The cache holds a `char[]` and zeroes it on eviction, `invalidate()` and `close()`. Mark `Secret.value()` `@Deprecated` with the reason. | No |
| 3.0 | `Secret` becomes a final class with `SecretValue value()`. Add a server endpoint that returns the raw value as `application/octet-stream`, as Conjur does. | Yes (major version) |

**Recommendation:** ship the 2.4 row now. Document the limits in the Javadoc: the JSON parser, TLS buffers and String-only sinks still make copies.

| Risk | Mitigation |
|---|---|
| Users forget to close the value. | Nothing breaks. The exposure window is only longer. |
| Use after close, for example a connection pool that reads the password later. | Throw `IllegalStateException` with a clear message. Document that pools need `asString()` or their own copy. |
| False confidence. | Say "shortens exposure" in the docs, never "erases". |
| More API surface to maintain. | Keep it to one class and one client method. |

### 2.5 Common mistakes

- Saying `char[]` "guarantees" the secret leaves memory.
- Changing `value()` to `char[]` in a 2.x release. Every caller breaks, and a minor release must not break callers.
- Forgetting that the HTTP body, the JSON parser and the cache hold copies.
- Returning the internal array, so callers can change it or keep it.
- Using a record for the wipeable holder.
- Ignoring the real leak paths: `toString()` and logs (SDK-101), exception messages, heap dumps uploaded to support tickets.
- Believing that every String is interned. Only literals and constant expressions are.

### 2.6 Follow-up questions and good short answers

1. **"Can the GC leave copies of a char[] when it moves the array?"** It can depend on the collector. No vendor doc confirms or rules it out (UNVERIFIED). That is why zeroing is best effort.
2. **"char[] or byte[]?"** `byte[]` matches the wire and skips a decode step. `char[]` matches the JDK password APIs such as `PBEKeySpec` and `KeyStore`. Offer both copies (inference).
3. **"How do you migrate customers without breaking them?"** Add the new API in 2.4, deprecate the old method, publish a migration guide, and switch in 3.0. Conjur did the same in 3.4.0.
4. **"How do you test the zeroing?"** Assert that `close()` leaves the internal array all zeros, through a package-private accessor. Assert that `apply()` after `close()` throws. Assert that cache eviction closes the cached value.
5. **"Would you change the server?"** Yes, in 3.0: an endpoint that returns the raw value as bytes. Then the client reads into a buffer that it owns and zeroes.

### 2.7 What separates senior from mid

- **Senior signal:** She says zeroing "shortens the exposure window" and names the layers that still copy: HTTP, JSON, cache, JDBC.
- **Senior signal:** She proposes an additive path (2.4 adds and deprecates, 3.0 switches), not a breaking change in a minor release.
- **Senior signal:** She notices that a record is the wrong type for a wipeable value.
- **Senior signal:** She uses the team's own product as evidence: `conjur-api-java` 3.4.0 made this exact change this month.
- **Senior signal:** She ranks the real leak paths first (logs, `toString()`, heap dumps) and links them to SDK-101.

---

## 3. Research B2: pick a cache library

### 3.1 The 5-minute spoken summary

1. **The goals.** Keep serving secrets during short outages (stale-if-error). Refresh hot secrets before they expire (refresh-ahead).
2. **What both libraries do.** `refreshAfterWrite` starts a refresh on the first read after the refresh time. The read returns the old value while the refresh runs. A failed refresh keeps the old value and logs and swallows the error.
3. **What I verified with a spike.** After a failed refresh, every later read starts another reload, with no backoff. Returning the old value from `reload` resets the expiry timer, so the stale window never ends.
4. **Difference 1: threads.** Caffeine runs reloads on an executor, `ForkJoinPool.commonPool()` by default. Guava runs the default `reload` on the reading thread.
5. **Difference 2: deletes.** In Caffeine, `reload` can return null to remove the entry, so a deleted secret stops working. Guava rejects null and keeps the old value until it expires.
6. **Difference 3: features.** Caffeine has per-entry expiry, Window TinyLFU eviction and an async cache. Guava has fixed expiry, LRU per segment and wraps loader exceptions.
7. **Guava's own docs.** The `CacheBuilder` Javadoc starts with "Prefer Caffeine over Guava's caching API".
8. **Footprint.** Guava is a 3.06 MB jar for the whole library. Caffeine is 1.09 MB plus two annotation jars. Caffeine 3.x needs Java 11 or later, and we are on 17.
9. **The gap.** Neither library knows that 404 must fail closed and 503 may serve stale. We write that logic in every option.
10. **Recommendation.** Keep our own `SecretCache` now and add stale-if-error to it. Move the storage to Caffeine, shaded, when we need background refresh or a smart size bound. Do not pick Guava.

### 3.2 What the spike showed

Setup for both libraries: `refreshAfterWrite` 10 s, `expireAfterWrite` 30 s, a fake ticker and same-thread refresh. JDK 17.0.20.1, Caffeine 3.3.1, Guava 33.7.2-jre. The code is `b2-spike/RefreshSpike.java`, next to this file.

| # | Scenario | Caffeine 3.3.1 | Guava 33.7.2-jre | What it means for us |
|---|---|---|---|---|
| 1 | `reload` throws (a 503) at t=11 s | The read returns the old value. A WARN "Exception thrown during refresh" goes to `System.Logger`. | The read returns the old value. A WARNING goes to `java.util.logging`. | Both serve stale on a refresh failure, silently: no counter, no error type. |
| 2 | Read again at t=12 s and t=29 s, backend still down | Each read starts a new reload. | Each read starts a new reload. | During an outage, each read of a hot key sends one more request. We add our own backoff. |
| 3 | Read at t=31 s (past `expireAfterWrite`), load fails | The entry is gone. `get` throws the loader's `IllegalStateException` as is. | `get` throws `UncheckedExecutionException` that wraps it. | `expireAfterWrite` works as a hard stale bound. Guava forces us to unwrap to keep `SecretNotFoundException`. |
| 4 | `reload` returns null ("secret deleted") | The entry is removed. The read that started the reload still got the old value. | Null is rejected (`InvalidCacheLoadException` logged). The old value stays until expiry. | Only Caffeine can drop a deleted secret during a refresh. |
| 5 | `reload` returns the old value (a common fallback trick) | The timer resets: the entry is still there at t=31 s with age 20 s, and is gone at t=42 s. | The same: present at t=31 s, gone at t=42 s. | Never return the old value from `reload`. It turns a bounded stale window into an unbounded one. |

The docs agree. Caffeine: "If refreshing returned null, the mapping will be removed" ([CacheLoader](https://www.javadoc.io/doc/com.github.ben-manes.caffeine/caffeine/latest/com.github.benmanes.caffeine/com/github/benmanes/caffeine/cache/CacheLoader.html)). Guava: `reload` "must not be null, must not return null" ([CacheLoader](https://guava.dev/releases/33.7.2-jre/api/docs/com/google/common/cache/CacheLoader.html)). The Caffeine migration page states the null difference too ([Caffeine wiki: Guava](https://github.com/ben-manes/caffeine/wiki/Guava)).

To run the spike: put `caffeine-3.3.1.jar`, `guava-33.7.2-jre.jar`, `failureaccess-1.0.3.jar`, `jspecify-1.0.1.jar` and `error_prone_annotations-2.50.0.jar` from Maven Central on the classpath, then `javac` and `java RefreshSpike`.

### 3.3 Comparison table

Versions and dates come from Maven Central metadata and the GitHub releases, read on 2026-10-21.

| Criterion | A: Caffeine | B: Guava Cache | C: keep our own `SecretCache` |
|---|---|---|---|
| Latest version | 3.3.1, released 2026-10-13. Before it: 3.3.0 (2026-09-21), 3.2.4 (2026-05-03) ([metadata](https://repo1.maven.org/maven2/com/github/ben-manes/caffeine/caffeine/maven-metadata.xml), [3.3.1](https://github.com/ben-manes/caffeine/releases/tag/v3.3.1)) | 33.7.2-jre, released 2026-09-29. Before it: 33.7.1 (2026-08-18), 33.6.0 (2026-04-14) ([metadata](https://repo1.maven.org/maven2/com/google/guava/guava/maven-metadata.xml), [33.7.2](https://github.com/google/guava/releases/tag/v33.7.2)) | In our repo |
| Refresh-ahead (`refreshAfterWrite`) | The first read after the refresh time starts an async reload on `executor`. The read gets the old value unless the reload already finished ([Caffeine](https://www.javadoc.io/doc/com.github.ben-manes.caffeine/caffeine/latest/com.github.benmanes.caffeine/com/github/benmanes/caffeine/cache/Caffeine.html), [Refresh](https://github.com/ben-manes/caffeine/wiki/Refresh)). | Same trigger. The default `reload` is synchronous, so the reading thread pays for it, unless you wrap the loader with `CacheLoader.asyncReloading(loader, executor)` ([CacheBuilder](https://guava.dev/releases/33.7.2-jre/api/docs/com/google/common/cache/CacheBuilder.html)). | Not built. We would write background refresh and one-in-flight-per-key ourselves. |
| Old value when a refresh fails | Kept. The error is logged through `System.Logger` and swallowed ([LoadingCache](https://www.javadoc.io/doc/com.github.ben-manes.caffeine/caffeine/latest/com.github.benmanes.caffeine/com/github/benmanes/caffeine/cache/LoadingCache.html)). The next read retries. | Kept. The error is logged through `java.util.logging` and swallowed ([LoadingCache](https://guava.dev/releases/33.7.2-jre/api/docs/com/google/common/cache/LoadingCache.html)). The next read retries. | Our choice: stale only on transient errors, fail closed on 401, 403 and 404. |
| Deleted secret during a refresh | `reload` returns null and the entry goes. | Cannot remove it from `reload`. The old value stays until expiry. | Evict on 404. |
| Expiry options | `expireAfterWrite`, `expireAfterAccess`, per-entry `expireAfter(Expiry)`, `refreshAfterWrite`, and a `scheduler` for prompt removal. | `expireAfterWrite`, `expireAfterAccess`, `refreshAfterWrite`. No per-entry expiry. | One fixed TTL today. |
| Size-based eviction | `maximumSize` or `maximumWeight` with a weigher. Window TinyLFU policy. | `maximumSize` or `maximumWeight`. LRU inside each segment. | None. The map can grow without limit. |
| Async loading | `AsyncCache` and `AsyncLoadingCache` with `CompletableFuture`. | `ListenableFuture` from `reload`. No async cache type. | `CompletableFuture` by hand. |
| Testability | `ticker(Ticker)` for fake time and `executor(Runnable::run)` for same-thread work ([Testing](https://github.com/ben-manes/caffeine/wiki/Testing)). | `ticker(Ticker)`. `FakeTicker` is in guava-testlib. | Already uses `Clock` and `MutableClock`. |
| Java version | 3.x needs Java 11 or later ("For Java 11 or above, use 3.x otherwise use 2.x") ([README](https://github.com/ben-manes/caffeine)). The 3.3.1 class files are version 55, checked with `javap`. | The JRE flavor needs JDK 1.8 or later ([README](https://github.com/google/guava)). The class files are version 52, checked with `javap`. | Java 17, our baseline. |
| License | Apache-2.0 ([POM](https://repo1.maven.org/maven2/com/github/ben-manes/caffeine/caffeine/3.3.1/caffeine-3.3.1.pom)) | Apache-2.0 | Ours |
| Maintenance | 6 releases since 2025-06. 1 open issue or PR. Last push 2026-10-20. 3.3.0 fixed an "over-aggressive refresh discard" bug ([3.3.0](https://github.com/ben-manes/caffeine/releases/tag/v3.3.0)). | Regular releases for the whole library. 756 open issues and PRs. Last push 2026-10-21. The `CacheBuilder` Javadoc says "Prefer Caffeine over Guava's caching API". | We maintain it. |
| Dependency footprint | `caffeine-3.3.1.jar` 1.09 MB, plus `jspecify` (3 KB) and `error_prone_annotations` (20 KB). | `guava-33.7.2-jre.jar` 3.06 MB, plus `failureaccess` (11 KB), the empty `listenablefuture` (2 KB), `jspecify`, `error_prone_annotations` and `j2objc-annotations` (17 KB). The cache package is 116 of the jar's 2,003 entries. | Zero |
| Security advisories | None for the Maven package in the GitHub Advisory Database. | CVE-2018-10237, CVE-2020-8908, CVE-2023-2976 and CVE-2026-102554. The last one was published 2026-10-09 and is fixed in 33.7.2 ([GHSA-xxph-c9ww-hj94](https://github.com/google/guava/security/advisories/GHSA-xxph-c9ww-hj94), [CVE record](https://www.cve.org/CVERecord?id=CVE-2026-102554)). None is in the cache package (inference from the advisory titles). | None |

One peer data point: AWS's own Java caching client, `aws-secretsmanager-caching-java` 2.2.0, keeps its own `LRUCache` built on `LinkedHashMap` and depends on no cache library ([LRUCache.java](https://github.com/aws/aws-secretsmanager-caching-java/blob/v2/src/main/java/com/amazonaws/secretsmanager/caching/cache/LRUCache.java)). It also shows the risk of option C. Its public `putAll(map)` locks the parameter `map`, not the cache's own map, because the parameter hides the field. The client itself never calls `putAll`.

### 3.4 Recommendation and its risks

**Recommendation: C now, A later, never B.**

1. **Now:** keep `SecretCache` and add stale-if-error to it. The rules we need (stale only on 5xx, 429 and I/O, fail closed on 401, 403 and 404, a hard `maxStale`, a counter) are custom in every option. The change is small, needs no new dependency, and keeps the `Clock`-based tests.
2. **Add a size cap** to `SecretCache`, so a client that reads many dynamic ids cannot grow without limit.
3. **Later:** move the storage to Caffeine 3.3.x, behind the same `SecretCache` API. Do it when we need background refresh-ahead, more than a few thousand keys per client, or an async `getSecretAsync` API.
4. **If we adopt Caffeine:** shade and relocate it, and pass our own executor. Never return the old value from `reload`. Return null for deleted or revoked secrets.
5. **Not Guava:** its own docs point to Caffeine. It refreshes on the caller's thread by default. It cannot drop a deleted entry during a refresh. It adds 3 MB of library, with its own CVE stream, to every customer app.

| Risk | Mitigation |
|---|---|
| C: our own concurrency bugs. SDK-106 shows how easy they are. | Keep the code small. Add a concurrency test like `TokenProviderConcurrencyTest` for any refresh logic. |
| C: no smart eviction. | A simple cap is enough at 30 secrets per client. Switch to Caffeine when the trigger in point 3 happens. |
| A later: a second Caffeine version on the customer classpath (for example 2.x) wins dependency resolution and causes `NoSuchMethodError`. | Shade and relocate Caffeine into our package. The cost: a bigger jar, and we must release to pick up Caffeine fixes. |
| A later: blocking HTTP reloads on `ForkJoinPool.commonPool()`. | Pass the SDK's own `secrets-client` pool to `executor(...)`. |
| A later: silent stale serves. | Count stale reads in our read path. Log in `reload`. |

What would change my mind: a requirement for an async API or for thousands of keys per client. Then I would adopt Caffeine now.

### 3.5 Common mistakes

- Comparing from blog posts or AI output without checking the Javadoc or running a spike.
- Assuming a failed refresh surfaces an error. Both libraries swallow it.
- Returning `oldValue` from `reload` as a fallback. It resets the expiry timer.
- Missing that a Guava refresh cannot remove a deleted secret.
- Leaving Caffeine's default `commonPool()` executor for blocking HTTP calls.
- Ignoring that an SDK's dependencies land in every customer application.
- Picking a library and forgetting that the fail-closed rules still need our own code.

### 3.6 Follow-up questions and good short answers

1. **"Why not use Caffeine's LoadingCache with `refreshAfterWrite` as is?"** Set `expireAfterWrite` to TTL + `maxStale` to allow stale values. Then take a key that nobody read between the refresh time and the TTL. Its first read after the TTL gets the cached value, while the server is healthy. That breaks "stale only on errors". Our read path must decide fresh or stale itself.
2. **"How do you test Caffeine timing?"** `ticker(...)` with a fake time source, `executor(Runnable::run)` for same-thread work, and `cleanUp()` before asserting on eviction ([Testing](https://github.com/ben-manes/caffeine/wiki/Testing)).
3. **"What is shading, and what does it cost?"** The build copies Caffeine's classes into our jar under our package name. Customers cannot get a version conflict. We must release a new SDK to pick up a Caffeine fix.
4. **"Is `Clock` the right time source for TTLs?"** Wall-clock time can jump after an NTP step or a VM resume. Caffeine's default ticker is monotonic `System.nanoTime`. For durations, a monotonic source is safer (inference).
5. **"Is a spike worth it in a 20-minute slot?"** Yes, if it stays small: one library, one scenario, about 25 lines. It answers what the docs leave open, such as the reload on every read and the timer reset.

### 3.7 What separates senior from mid

- **Senior signal:** She verifies the refresh-failure semantics with a small spike, not with blog posts or AI output.
- **Senior signal:** She notices that the fail-closed rules for 401, 403 and 404 are ours to write in every option.
- **Senior signal:** She weighs the dependency cost for an SDK that ships inside customer apps, and mentions shading.
- **Senior signal:** She quotes the Guava Javadoc that recommends Caffeine.
- **Senior signal:** She gives a recommendation with triggers that would change it.

---

## 4. Design (Flow A): rotation-aware secret delivery at scale

### 4.1 Clarifying questions and stated assumptions

| Question | Assumption I state (fictional numbers for the exercise) |
|---|---|
| What exactly breaks today? | The API answers 429 during rollouts. DB logins fail after a rotation until the 5-minute cache TTL ends. |
| What is the rate limit? | 5,000 req/s per tenant. |
| Do apps re-read secrets after startup? | Yes, when the cache TTL ends, for example to open new DB connections. |
| What can we change? | We own the SDK and the Secrets Manager SaaS. Customers will install one Helm chart per cluster. |
| Who uses the SDK? | 80% of services use Java SDK 2.3. 20% are Go or Python services that call the REST API. |
| How fresh must a rotated secret be? | Every pod sees a new version within 1 minute (p99). |
| What if Secrets Manager is down? | Running pods keep working for at least 1 hour. New pods can start during a 15-minute outage. |
| How fast must revocation work? | Revoked access stops within 5 minutes while Secrets Manager is reachable. |
| Audit? | Every read is audited: which workload read which secret version, and when. |
| Secret size? | 1 KB on average, at most 64 KB. |

### 4.2 Back-of-envelope numbers

More assumptions:

- 500 pods per cluster (20,000 / 40) and about 30 pods per node.
- 200 services and 6,000 distinct secrets. Each cluster uses 1,500 of them.
- 10% of the secrets rotate daily.

| Quantity | Arithmetic | Result |
|---|---|---|
| Reads per full rollout | 20,000 pods × 30 secrets | 600,000 |
| Average rate, rollout in 5 minutes | 600,000 / 300 s | 2,000 req/s |
| Burst: 200 deployments of 100 pods roll at once, `maxSurge` 25% | 200 × 25 pods = 5,000 pods start within about 10 s, × 30 / 10 s | 15,000 req/s |
| Logins in that burst | 5,000 pods / 10 s | 500 logins/s |
| Steady load today (5-minute TTL, apps re-read) | 600,000 / 300 s | 2,000 req/s, all day |
| With a batch call (1 call per pod) | burst 5,000 / 10 s, steady 20,000 / 300 s | 500 calls/s burst, 67 calls/s steady |
| Upstream with a cache per cluster and change events | 600 rotations/day × up to 40 caches | 24,000 fetches/day, about 0.3/s |
| Cold start of one cluster cache | 1,500 secrets / 250 per batch | 6 calls |
| Pods polling the in-cluster cache every 60 s with versions | 20,000 / 60 s, then / 40 clusters | 333 calls/s in all, about 8/s per cluster, mostly "not modified" |
| Memory | 6,000 × 1 KB | 6 MB in all, 1.5 MB per cluster |
| Audit rows during a rollout, aggregated per (workload, secret, version, minute) | 200 services × 30 secrets, × 5 minutes | At most 6,000 rows per minute, so at most 30,000 rows instead of 600,000 events |

The default `maxSurge` of 25% comes from the [Deployment docs](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/). The 250-per-call limit copies the Secrets Manager SaaS batch API ([What's new](https://docs.cyberark.com/secrets-manager-saas/latest/en/content/conjurcloud/whatsnew.htm)).

What the numbers say:

- The 15,000 req/s burst is 3 times the 5,000 req/s tenant limit. That burst is the throttling.
- The fleet does not need a bigger server. It needs fewer requests.
- TTL polling costs 2,000 req/s all day, the same as a permanent rollout.
- A shorter TTL doubles that load and still leaves an error window after each rotation.
- Storage is not a problem: 6 MB fits everywhere.

### 4.3 API

**SDK (Java), additive in 2.4:**

```java
SecretsClientConfig config = SecretsClientConfig.builder()
        .endpoints(List.of(EDGE_URI, CENTRAL_URI))          // new: in-cluster cache first, central API as fallback
        .authenticator(Authenticators.kubernetesJwt(TOKEN_FILE)) // Research A
        .cacheTtl(Duration.ofSeconds(60))                    // cheap now, because the edge is local
        .maxStale(Duration.ofMinutes(15))                    // B2: stale-if-error
        .build();

Map<String, Secret> secrets = client.getSecrets(ids);        // one batch call; falls back to single GETs
Secret fresh = client.refresh(id);                           // new: bypass the cache once, rate-limited per id
AutoCloseable sub = client.onChange(id, secret -> pool.rotate(secret)); // new, optional, driven by polling
```

`baseUri(URI)` stays and means "one endpoint". The `endpoints` name is illustrative.

**HTTP API (central and edge serve the same contract):**

| Endpoint | Purpose | Details |
|---|---|---|
| `POST /v1/auth/k8s` | Pod login with its projected JWT | Returns `{access_token, expires_in}`. |
| `GET /v1/secrets/{id}` | Existing single read | Adds `ETag: "<version>"`, honours `If-None-Match` with `304`, and sends `Cache-Control: max-age=60, stale-if-error=900`. |
| `POST /v1/secrets:batchGet` | Up to 250 ids per call | Body `{"ids": [...], "known": {"<id>": <version>}}`. Returns one result per id: `{id, status, version, value}`. Unchanged ids come back with status 304 and no value. |
| `GET /v1/changes?cursor=<c>&wait=30s` | Long-poll change feed, for edges | Returns `{cursor, events: [{id, version, type}]}` with type `UPDATED`, `DELETED` or `ACCESS_CHANGED`. Events never carry values. |
| `GET /v1/edge/status` | Edge health | `{lastSyncAt, lagSeconds, cursor}` |

Design notes:

- The batch call is a POST because 250 URL-encoded ids do not fit in a URL. The SDK may retry it, because the server documents it as a read without side effects.
- The batch path is not under `/v1/secrets/`, so no secret id can collide with it.
- The batch returns a status per id. Conjur's `GET /secrets?variable_ids=` answers 404 for the whole batch when one variable is missing ([Batch retrieval](https://docs.cyberark.com/conjur-open-source/latest/en/content/developer/conjur_api_batch_retrieve.htm)). One missing id must not fail 29 good ones.
- The ETag, `If-None-Match` and 304 semantics come from [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110) (sections 8.8.3, 13.1.2, 15.4.5). `max-age` comes from [RFC 9111](https://www.rfc-editor.org/rfc/rfc9111) (section 5.2.2.1) and `stale-if-error` from [RFC 5861](https://www.rfc-editor.org/rfc/rfc5861).

The server sends the cache policy per secret, so security admins, not app developers, own the staleness bound. The client applies the stricter of its own setting and the server's.

### 4.4 Architecture and main flows

```
                    Acme Secrets Manager (SaaS, central)
        +---------------------------------------------------------+
        | REST API v1 + batchGet       policy (RBAC)    audit log |
        | change feed (cursor)         rotation engine            |
        +-----------------------------+---------------------------+
             ^ batchGet, resync       |  events {id, version, type}
             | (mTLS, edge identity)  |  never values
             |                        v
 - - - - - - | - - - - - - - - - - - -|- - - - - - - - - - - -  x 40 clusters
        +----+------------------------+---------------------------+
        | Acme Edge (Deployment, 3 replicas, ClusterIP Service)    |
        |  in-memory store {id -> value, version}                  |
        |  replicated policy for this cluster's workloads          |
        |  verifies pod JWTs with the cluster's JWKS               |
        |  buffers and forwards audit events                       |
        +----+--------------------+--------------------+-----------+
             ^ TLS: login,        ^                    ^
             | batchGet, 60 s     |                    |
             | revalidation       |                    |
        +----+------+        +----+------+        +----+------+
        | pod       |        | pod       |  ...   | pod       |   about 500 per cluster
        | app + SDK |        | app + SDK |        | app + SDK |
        +-----------+        +-----------+        +-----------+
        SDK: one batch call at start, revalidates by version every 60 s,
             serves stale for up to 15 min, falls back to the central API
```

The edge mirrors what CyberArk ships. Self-Hosted uses read-only Followers that serve authentication and reads at scale, and a Follower can hold only a subset of secrets ([Architecture](https://docs.cyberark.com/secrets-manager-sh/latest/en/content/deployment/cjr-architecture.htm), [Followers](https://docs.cyberark.com/secrets-manager-sh/latest/en/content/deployment/dap/dap-followers.htm)). SaaS uses Secrets Manager Edge, which serves secrets locally and keeps working offline ([Core concepts](https://docs.cyberark.com/secrets-manager-saas/latest/en/content/conjurcloud/cl_conjurcloudoverview.htm)).

**Pod startup:**

1. The kubelet mounts the projected token (audience `acme-secrets`, 10 minutes).
2. The SDK logs in to the edge. The edge verifies the JWT with the cluster's JWKS and returns a 10-minute edge token.
3. The SDK sends one `batchGet` with its 30 ids.
4. The edge checks the policy for each id and answers from memory. A miss starts one upstream fetch per id, shared by all waiting callers.
5. The edge records the read for audit, aggregated per minute, and forwards it.

**Rotation (overlapping credentials):**

1. The rotation engine sets a new password on the inactive DB user B and stores it as version N+1. The value holds the user name and the password together.
2. The engine marks N+1 as current. The change feed emits `{id, N+1, UPDATED}`.
3. Each edge that holds the secret fetches N+1 and acknowledges the version.
4. Pods see N+1 at their next 60-second revalidation. New connections use user B. Open connections on user A keep working.
5. The engine retires user A's password only after every edge has acknowledged N+1 and the grace period has passed.
6. If the DB still rejects a login, the app calls `client.refresh(id)` once and reconnects.

### 4.5 Deep dive 1: the startup storm

| Change | Effect at the 15,000 req/s burst | Source or reason |
|---|---|---|
| Batch call (30 ids in one request) | 500 calls/s | 30× fewer requests. This is the cheapest fix, so it ships first. |
| Edge per cluster | The central API sees about 40 clients, not 20,000. | Fan-in of 500 pods per edge. |
| One upstream fetch per missing id at the edge | A burst of identical misses becomes one request. | Same idea as the single-flight token refresh (SDK-106). |
| Edge prefetch before it reports ready | Pods never hit a cold edge. | 6 batch calls per edge at start. |
| Jitter on revalidation and full-jitter backoff on retries | Pods do not retry in lockstep. | [Exponential backoff and jitter](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/) |
| Honour `Retry-After` on 429, retry in one layer only | No retry multiplication across layers. | [RFC 9110 section 10.2.3](https://www.rfc-editor.org/rfc/rfc9110), [Timeouts, retries and backoff](https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/) |
| Token bucket per tenant at the central API | One tenant's rollout cannot throttle others. | Inference |

### 4.6 Deep dive 2: rotation without auth errors

- **TTL tuning cannot fix this.** A shorter TTL raises the load and still leaves a window in which pods hold the old password.
- **Fix it at the root with overlapping credentials.** AWS's alternating-users strategy keeps two users valid, so an app that fetches mid-rotation still logs in ([Rotation strategies](https://docs.aws.amazon.com/secretsmanager/latest/userguide/rotation-strategy.html)). CyberArk dual accounts swap an active and an inactive account, then change the inactive one after `GracePeriod` ([Dual accounts](https://docs.cyberark.com/secrets-manager-sh/latest/en/content/cp%20and%20ascp/cv_automatic_dual_account.htm)).
- **Size the grace period.** Grace ≥ edge propagation + SDK revalidation interval + SDK `maxStale` + margin. With our numbers: seconds + 1 min + 15 min + 4 min = about 20 minutes.
- **Watch the vendor default.** CyberArk's default `GracePeriod` is 6 minutes. That is shorter than our 15-minute `maxStale`. Raise the grace period or lower `maxStale`.
- **Gate the retirement.** The engine does not retire the old credential until every edge acknowledges the new version. During a partition, the old credential stays valid and an alert fires.
- **Keep pairs atomic.** Store the user name and the password in one secret. Two secrets can be read at two different versions.
- **Keep versions monotonic.** The SDK and the edge never replace version N+1 with N, even if a lagging replica answers.
- **Keep a safety net.** `client.refresh(id)` after a DB auth failure, limited to once per 30 seconds per id, so a broken app cannot loop.
- **Know what each tool does.** Overlapping credentials remove the error window. Push, ETags and TTLs only shorten the delay.

### 4.7 Deep dive 3: edge security and the stale policy

- **Authentication.** Pods log in with their projected JWT. The edge verifies it with the cluster's JWKS, which it can reach inside the cluster even when our SaaS cannot. Kubernetes binds the discovery role to all service accounts by default ([Service accounts](https://kubernetes.io/docs/tasks/configure-pod-container/configure-service-account/)).
- **Authorization.** The edge enforces the same policy as the central API. It replicates the policy for its cluster's workloads. It denies when the policy is unknown or too old. A cache that any pod can read is a privilege-escalation path.
- **Scope.** The edge holds only secrets that workloads in its cluster may read, like a Follower replication set.
- **Storage.** Memory only by default. Encrypted persistence is an option for customers who need an edge restart to work offline. CyberArk's Edge 1.7 added local persistence for the same need ([What's new feed](https://docs.cyberark.com/whats-new/latest/en/)).
- **Audit.** Cache hits are reads too. The edge aggregates and buffers audit events and alerts before the buffer can drop one.

| Layer | Fresh for | Serves stale on a transient error | Hard bound | Never serves stale |
|---|---|---|---|---|
| SDK in the pod | 60 s, then revalidates by version | Yes | `maxStale` 15 min | After 401, 403 or 404 |
| Edge | Until a change event, with a full resync every 5 min | Yes, while it cannot reach the central API | 1 h by default (per tenant, 15 min to 24 h) | After a `DELETED` or `ACCESS_CHANGED` event |

Worst case for a running pod during a central outage: 1 hour (edge) + 15 minutes (SDK). That meets the 1-hour requirement. The edge bound is a business choice between an outage and a revoked credential still in use. So the tenant sets it, within limits.

### 4.8 Failure modes

| Component | What breaks | Mitigation |
|---|---|---|
| Central API down (minutes to hours) | No new versions reach the edges. Rotation cannot propagate. | Edges serve the last state for up to 1 h. SDKs serve stale for up to 15 min. The engine does not retire old credentials. Alert. |
| Central API throttles (429) | Edges refresh slowly. | `Retry-After` and jitter. One fetch per id. Batch calls. |
| One edge replica crashes | Requests on that replica fail. | 3 replicas behind a Service, a PodDisruptionBudget, readiness only after sync. The SDK retries another replica and keeps its own cache. |
| All edge replicas in a cluster down | New pods cannot start from the edge. | The SDK falls back to the central API with a client-side rate limit. Running pods use their caches. Page. |
| Change event lost or watch disconnected | The edge serves an old version. | Resume from the cursor. Full resync every 5 minutes. Version checks. |
| Partition between a cluster and the central API during a rotation | Pods hold the old credential. | Retirement waits for every edge's acknowledgement. Alert when the wait exceeds the grace period. |
| Edge cold start in a new cluster | A burst to the central API. | Paged prefetch with a rate limit. Ready only when warm. |
| Bad edge release | Every pod in a cluster is affected. | Canary one cluster. SDK fallback to the central API. Fast rollback. |
| Clock skew on nodes | JWT `exp` and `nbf` checks fail. | NTP. Leeway of about 60 s. |
| Login stampede after mass token expiry | 429 on login, as in SDK-106. | Single-flight token refresh. Refresh at 75% of the lifetime, with jitter. |
| DB rejects the password (rotation race) | App errors. | Overlapping credentials. `refresh(id)` once per 30 s. |
| Oversized secret | Memory pressure at the edge. | Reject values over 64 KB at write time. |

### 4.9 Security

- Workload identity everywhere. Pods use projected JWTs with the audience `acme-secrets`. Edges use their own workload identity to the central API. No API keys in pods.
- TLS from pod to edge with a cluster CA. mTLS from edge to the central API.
- The edge is not a proxy. It answers only its own API, requires `Authorization` on every call, and a NetworkPolicy admits only pods of that cluster. AWS's local agent and Vault Agent require an extra request header against SSRF for the same reason ([AWS agent](https://docs.aws.amazon.com/secretsmanager/latest/userguide/secrets-manager-agent.html), [Vault Agent](https://developer.hashicorp.com/vault/docs/agent-and-proxy/agent)).
- Least privilege for the edge: only the secrets of its cluster's workloads.
- No secret values in logs, metrics, traces, events or error messages. Change events carry ids and versions only.
- Heap dumps off on the edge, or written only to an encrypted volume. Run as non-root with a read-only root filesystem.
- One token audience for the edge and the central API, so the fallback path works. Trade-off: a compromised edge could replay a pod token to the central API for up to 10 minutes. The edge already holds that pod's secrets, so the extra exposure is small.
- Revocation: `DELETED` and `ACCESS_CHANGED` events evict at once. The SDK fails closed on 401, 403 and 404.

### 4.10 Observability

| Metric | Type | Labels | Why |
|---|---|---|---|
| `edge_requests_total` | counter | tenant, status, source (memory or upstream) | Load and hit ratio |
| `edge_request_duration_seconds` | histogram | tenant, route | Latency SLO |
| `edge_sync_lag_seconds` | gauge | tenant | Time since the last successful sync |
| `edge_watch_connected` | gauge (0 or 1) | tenant | Push channel health |
| `edge_stale_serves_total` | counter | tenant, reason | Outage exposure |
| `edge_policy_denials_total` | counter | tenant | Misconfiguration or attack |
| `edge_audit_buffer_ratio` | gauge | | Audit-loss risk |
| `rotation_propagation_seconds` | histogram | tenant | New version to last edge acknowledgement |
| `rotation_retirement_blocked_total` | counter | tenant | Gated rotations |
| `api_throttled_total` | counter | tenant | 429s at the central API |
| `secrets_client_stale_serves_total` | counter | | Exposure in the SDK |
| `secrets_client_token_requests_total` | counter | | Login stampedes |

| Alert | Condition | Action |
|---|---|---|
| Edge out of sync | `edge_sync_lag_seconds` > 300 for 5 min | Ticket. Page above 1,800 s. |
| Stale serving | Any stale serves for 10 min | Page: the central API is unreachable from a cluster. |
| Slow rotation | p99 `rotation_propagation_seconds` > half the grace period | Page before the old credential is retired. |
| Audit at risk | `edge_audit_buffer_ratio` > 0.8 | Page: compliance risk. |
| Throttling | 429s > 1% of central API calls for 5 min | Ticket. Check batch use and the tenant bucket. |
| Read latency SLO | p99 edge read > 50 ms for 10 min | Ticket. |

### 4.11 Rollout and backward compatibility

| Phase | What ships | Compatibility |
|---|---|---|
| 1. SDK 2.4 | Batch call with fallback, jitter, `Retry-After`, single-flight token, stale-if-error (off by default). | Additive. New exceptions extend `SecretsClientException`. |
| 2. Edge canary | One cluster. Existing 2.3 clients change only `baseUri`, because the edge speaks the v1 API. | No code change for 2.3 users. Rollback: point `baseUri` back. |
| 3. Change feed and ETags | Edges subscribe. SDK 2.4 revalidates by version. | Old clients ignore the new headers. |
| 4. Gated dual-credential rotation | Opt-in per secret. | Rotation without the flag works as today. |
| 5. Fleet | 10%, 50%, then 100% of clusters. | Success: upstream calls drop by over 95%, 429s reach zero, zero DB auth errors after rotations. |

The edge is a cache, not a source of truth. Deleting it loses nothing. Because the edge speaks the existing v1 API, 2.3 clients and the Go and Python services benefit without a code change.

### 4.12 Trade-offs chosen

| Decision | Chosen | Rejected | Why | Cost we accept |
|---|---|---|---|---|
| Where the shared cache lives | One edge per cluster | Node agent (DaemonSet), sidecar per pod | 40 × 3 edge pods instead of about 670 agents or 20,000 sidecars. Largest fan-in. CyberArk ships the same shape (Follower, Edge). | Bigger blast radius per edge than a node agent. Mitigated by scope, policy checks and hardening. |
| Freshness | Push to edges, version polling from pods | Push to every pod | Push where the fan-out is small (40 edges). Cheap local polling where it is large (20,000 pods). | Up to 60 s of delay in the pod. |
| Rotation | Overlapping credentials with gated retirement | A shorter TTL | Removes the error window instead of shrinking it. | The target needs two users. Rotation takes longer. |
| Outage behaviour | Serve stale with bounds per layer | Fail closed at once | Apps keep working through outages. | A revoked credential can live up to the bound while the central API is down. |
| Edge storage | Memory only by default | Disk persistence by default | Nothing to steal at rest. | An edge restart during a central outage cannot serve. Persistence is opt-in. |
| Batch semantics | Status per id | All-or-nothing | One missing id must not fail 29 good ones. | A bigger response format. |

### 4.13 Curveballs and how to adapt

1. **"Revocation must take effect within 60 seconds, even during an outage."** Set `maxStale` and the edge bound to 60 s for revocation-sensitive secrets. The edge fails closed for them after 60 s without the change feed. Say the cost out loud: those secrets now fail during outages.
2. **"The customer also runs 5,000 VMs and Lambda functions."** Run the same edge as a regional service on VMs. For Lambda, use an extension on localhost with a session-token header, as AWS's Parameters and Secrets extension does ([Lambda extension](https://docs.aws.amazon.com/lambda/latest/dg/with-secrets-manager.html)). Identity comes from AWS IAM (Research A).
3. **"Now it is 10×: 200,000 pods."** Edges scale per cluster. Upstream load grows with the number of clusters and changes, not pods. The new limits are the change-feed fan-out and the audit volume. Partition the feed per tenant and aggregate audit per workload and minute.

### 4.14 Common mistakes

- Drawing boxes before asking a question or doing the arithmetic.
- "Raise the rate limit" or "lower the TTL" as the main fix.
- A shared cache that does not check authorization per caller.
- No audit for cache hits.
- Secrets on disk without encryption, or values inside change events.
- No answer to "Secrets Manager is down, for how long do we serve stale?"
- Naming Kafka or Redis without saying which property is needed.
- A new protocol for pods that the Go and Python services cannot use.

### 4.15 What separates senior from mid

- **Senior signal:** She does the arithmetic first and concludes "fewer requests, not a bigger server".
- **Senior signal:** She fixes rotation at the root with overlapping credentials and a gated retirement.
- **Senior signal:** The edge enforces the same authorization as the central API, and audit stays complete for cache hits.
- **Senior signal:** She gives stale bounds per layer and ties `maxStale` to the rotation grace period.
- **Senior signal:** She keeps 2.3 clients working by making the edge speak the existing API.

---

## 5. Design + implement B2: stale-if-error

### 5.1 Behaviour table

"Transient" means an I/O error, HTTP 429, or HTTP 5xx after the retries. "Expired" means `now ≥ expiresAt`. The stale window is `expiresAt ≤ now < expiresAt + maxStale`.

| # | Situation | Behaviour |
|---|---|---|
| 1 | Cached and fresh | Return the cached value. No request. |
| 2 | Expired, and the fetch succeeds | Return the new value and replace the entry. |
| 3 | Expired, transient failure, inside the stale window | Return the stale value. Log a WARN with the id, the version and the cause. Add 1 to the stale-serve counter. |
| 4 | Expired, transient failure, past the stale window | Throw `SecretsUnavailableException`. Remove the entry. |
| 5 | Never cached, transient failure | Throw `SecretsUnavailableException`. There is nothing to serve. |
| 6 | Expired, 404 | Throw `SecretNotFoundException`. Remove the entry. |
| 7 | Expired, 403, or 401 after the one token refresh | Throw `AuthenticationException`. Remove the entry. |
| 8 | Token endpoint answers 401 ("API key rejected") | Throw `AuthenticationException`. Remove the entry. Never stale. |
| 9 | Token endpoint answers 429 or 5xx, or has an I/O error | Treat it as transient: rows 3 to 5 apply. |
| 10 | Any other status (400, 409, 422), a parse error, or an interrupt | Throw as today. No stale: an unknown failure fails closed. Restore the interrupt flag. |
| 11 | `maxStale` is zero (the default) | Exactly the 2.3 behaviour. No stale ever. |
| 12 | `invalidate(id)` | The entry is gone, so a later failure cannot serve it. |
| 13 | `getSecrets(ids)` with some transient failures | Stale values count as successes. Only ids without a usable value count in "Failed to fetch N of M secrets". |
| 14 | `now` is exactly `expiresAt + maxStale` | Not stale-eligible. The bound is exclusive, so `maxStale` = 0 means off. |

The name and the idea come from [RFC 5861](https://www.rfc-editor.org/rfc/rfc5861). It counts only 500, 502, 503 and 504 as errors. This feature adds 429 and I/O errors on purpose: serving stale during throttling also lowers the load.

### 5.2 Config

| Name | Type | Default | Validation | Why |
|---|---|---|---|---|
| `maxStale` | `Duration` | `Duration.ZERO` (off) | Not null, not negative, at most 24 h | Opt-in in a minor release. The cap stops a typo from making staleness unlimited. |

The list of transient errors stays in code, not in config. A config mistake must never allow stale values after a 404.

### 5.3 Where the code goes

| Class | Change |
|---|---|
| `SecretsClientConfig` | Add `maxStale`, the builder method and the check in `build()`. |
| `SecretsUnavailableException` (new) | Extends `SecretsClientException`. Means "Secrets Manager could not answer": I/O, 429 or 5xx after retries. |
| `ApiClient` | `errorFor(status, secretId)` returns `SecretsUnavailableException` for -1, 429 and 5xx. 404, 401 and 403 keep their types. |
| `TokenProvider` | The token fetch throws `SecretsUnavailableException` for 429, 5xx and I/O. 401 stays `AuthenticationException("API key rejected")`. |
| `SecretCache` | New constructor with `maxStale`. `get(id)` keeps expired entries inside the stale window. New `getStale(id)`. |
| `SecretsClient` | `getSecret` catches the three failure types. New `LongAdder` counter and `staleServeCount()`. |
| `SecretCacheTest`, `SecretsClientTest` | New tests for the stale and the fail-closed paths. |

Callers that catch `SecretsClientException` still catch everything, because the new exception is a subclass.

### 5.4 Implementation outline

This is an outline against the class names in the spec. It is not compiled. Check the real signatures in the repo before you type it.

```java
// SecretsClientConfig gets: private final Duration maxStale; public Duration maxStale() { ... }
static final Duration MAX_STALE_LIMIT = Duration.ofHours(24);

// SecretsClientConfig.Builder
private Duration maxStale = Duration.ZERO;                      // default: stale-if-error off

public Builder maxStale(Duration maxStale) {
    this.maxStale = maxStale;
    return this;
}

// in Builder.build():
Objects.requireNonNull(maxStale, "maxStale");
if (maxStale.isNegative() || maxStale.compareTo(MAX_STALE_LIMIT) > 0) {
    throw new IllegalArgumentException("maxStale must be between 0 and " + MAX_STALE_LIMIT + ": " + maxStale);
}
```

```java
/** Secrets Manager could not answer: I/O error, HTTP 429 or HTTP 5xx after retries. */
public class SecretsUnavailableException extends SecretsClientException {
    public SecretsUnavailableException(String message) {
        super(message);
    }
}
```

```java
// ApiClient: an explicit allow-list. It does not reuse RetryPolicy.isRetryable, so a
// regression like SDK-102 (status >= 400) cannot turn a 4xx into "serve stale".
private SecretsClientException errorFor(int status, String secretId) {
    if (status == 404) {
        return new SecretNotFoundException(secretId);
    }
    if (status == 401 || status == 403) {
        return new AuthenticationException("Access denied to secret " + secretId + ": HTTP " + status);
    }
    if (status == -1) {
        return new SecretsUnavailableException("I/O error while fetching secret " + secretId);
    }
    if (status == 429 || status >= 500) {
        return new SecretsUnavailableException("GET secret " + secretId + " failed: HTTP " + status);
    }
    return new SecretsClientException("GET secret " + secretId + " failed: HTTP " + status);
}
```

```java
// TokenProvider, in the token fetch
if (status == 401) {
    throw new AuthenticationException("API key rejected");
}
if (status == 429 || status >= 500) {
    throw new SecretsUnavailableException("Token request failed: HTTP " + status);
}
// an IOException from send() becomes new SecretsUnavailableException("Token request failed: I/O error")
```

```java
// SecretCache
public SecretCache(Duration ttl, Clock clock) {
    this(ttl, Duration.ZERO, clock);                        // old constructor keeps old behaviour
}

public SecretCache(Duration ttl, Duration maxStale, Clock clock) {
    this.ttl = Objects.requireNonNull(ttl, "ttl");
    this.maxStale = Objects.requireNonNull(maxStale, "maxStale");
    this.clock = Objects.requireNonNull(clock, "clock");
}

/** Returns the secret while it is fresh. Keeps an expired entry while it can still serve stale. */
public Optional<Secret> get(String id) {
    Entry entry = entries.get(id);
    if (entry == null) {
        return Optional.empty();
    }
    Instant now = clock.instant();
    if (now.isBefore(entry.expiresAt())) {
        return Optional.of(entry.secret());
    }
    removeIfPastStaleWindow(id, entry, now);
    return Optional.empty();
}

/** Returns the secret if it expired less than maxStale ago. Call it only after a transient failure. */
public Optional<Secret> getStale(String id) {
    Entry entry = entries.get(id);
    if (entry == null) {
        return Optional.empty();
    }
    Instant now = clock.instant();
    if (now.isBefore(entry.expiresAt().plus(maxStale))) {
        return Optional.of(entry.secret());
    }
    entries.remove(id, entry);
    return Optional.empty();
}

private void removeIfPastStaleWindow(String id, Entry entry, Instant now) {
    if (!now.isBefore(entry.expiresAt().plus(maxStale))) {
        entries.remove(id, entry);                          // conditional: keeps a newer entry from another thread
    }
}
```

```java
// SecretsClient
private final LongAdder staleServes = new LongAdder();

// constructor: cache = new SecretCache(config.cacheTtl(), config.maxStale(), config.clock());

public Secret getSecret(String secretId) {
    requireValidId(secretId);                               // unchanged: null or blank -> IllegalArgumentException
    Optional<Secret> cached = cache.get(secretId);
    if (cached.isPresent()) {
        return cached.get();
    }
    try {
        Secret secret = apiClient.fetchSecret(secretId);
        cache.put(secret);
        log.info("Fetched secret {}", secret);              // unchanged: Secret.toString() masks the value
        return secret;
    } catch (SecretNotFoundException | AuthenticationException e) {
        cache.invalidate(secretId);                         // deleted or revoked: a later outage must not revive it
        throw e;
    } catch (SecretsUnavailableException e) {
        return cache.getStale(secretId)
                .map(stale -> serveStale(stale, e))
                .orElseThrow(() -> e);
    }
}

private Secret serveStale(Secret stale, SecretsUnavailableException cause) {
    staleServes.increment();
    log.warn("Serving stale secret {} version {} because Secrets Manager is unavailable: {}",
            stale.id(), stale.version(), cause.getMessage());
    return stale;
}

/** Number of reads that returned an expired value since this client was created. */
public long staleServeCount() {
    return staleServes.sum();
}
```

### 5.5 Tests to write

Shared setup: `FakeSecretsServer` and `SecretsClient` share one `MutableClock`. `RecordingSleeper` means retries do not sleep. With the default `maxRetries` of 3, a call makes 4 attempts, so a test must inject 4 failures to exhaust it. A 401 test must inject 2 failures, because the first 401 triggers one token refresh.

| # | Test name | Arrange, then act and assert |
|---|---|---|
| 1 | `getSecret_transient503WithinMaxStale_returnsStaleValue` | Read once. Advance the clock to TTL + 1 min (`maxStale` 15 min). Inject 4 × 503. Read again: the old value comes back. |
| 2 | `getSecret_transient503BeyondMaxStale_throwsSecretsUnavailable` | Read once. Advance to TTL + 15 min + 1 s. Inject 4 × 503. Read again: `SecretsUnavailableException`. |
| 3 | `getSecret_notFoundAfterTtl_throwsNotFoundInsteadOfStale` | Read once. Advance past TTL. Inject 1 × 404. Read again: `SecretNotFoundException`. |
| 4 | `getSecret_forbiddenAfterTtl_throwsAuthenticationInsteadOfStale` | Read once. Advance past TTL. Inject 1 × 403. Read again: `AuthenticationException`. |
| 5 | `getSecret_notFoundThenTransientError_doesNotServeDeletedSecret` | Read once. Advance past TTL. Inject 1 × 404 and read through a helper that ignores the exception. Inject 4 × 503. Read again: `SecretsUnavailableException`, not the old value. |
| 6 | `getSecret_staleValueServed_incrementsStaleServeCount` | Same setup as test 1. Assert `staleServeCount()` is 1. |

If time allows: `getSecret_maxStaleNotSet_throwsOnTransientError` (backward compatibility) and `SecretCacheTest.getStale_exactlyAtStaleBoundary_returnsEmpty`.

A model test in the repo's style:

```java
@Test
void getSecret_transient503WithinMaxStale_returnsStaleValue() {
    server.putSecret(SECRET_ID, SECRET_VALUE);
    SecretsClient client = newClient(MAX_STALE);
    client.getSecret(SECRET_ID);
    clock.advance(CACHE_TTL.plus(WITHIN_STALE_WINDOW));
    server.failNextSecretRequests(ALL_ATTEMPTS, HTTP_SERVICE_UNAVAILABLE);

    Secret secret = client.getSecret(SECRET_ID);

    assertThat(secret.value()).isEqualTo(SECRET_VALUE);
}
```

Two traps for these tests:

- The server's token lifetime runs on the same clock (15 minutes by default). Test 2 advances past it, so the client fetches a new token first. That is fine and expected.
- On the buggy `main` branch, SDK-103 stores the TTL as 1,000 times too long, and SDK-102 retries a 404. Fix both before you write these tests, or the tests fail for the wrong reason.

### 5.6 The security trade-off

- **What we trade.** Availability against revocation delay. While Secrets Manager is unreachable, a client can use a value up to `maxStale` after the value expired.
- **Fail closed on explicit decisions.** 401, 403 and 404 are decisions by the server. They are never masked. Stale values cover only "the server could not answer".
- **The attack to name.** An attacker who can block or break traffic to Secrets Manager can extend the use of a stale value by up to `maxStale`. So the bound is hard, short and visible: WARN logs plus a counter that an alert can watch.
- **What stale does not extend.** It does not extend the life of the credential at the target. If the secret rotated because it leaked, the database must reject the old password anyway. Stale-if-error only changes what the client tries.
- **Rotation interplay.** If a password rotates during an outage, the stale value is the old password. It works only while the old credential is still valid. So set `maxStale` at or below the rotation grace period minus the TTL.
- **Who owns the bound.** The application developer sets `maxStale` today. Later, the server should send a per-secret ceiling (`Cache-Control: stale-if-error=<seconds>`), and the client should use the stricter value.
- **The industry contrast.** AWS's caching client serves the cached value after any refresh failure, with no time bound and no check of the error type ([SecretCacheObject.java](https://github.com/aws/aws-secretsmanager-caching-java/blob/v2/src/main/java/com/amazonaws/secretsmanager/caching/cache/SecretCacheObject.java)). CyberArk's Credential Provider can serve a cached password after its permission is revoked, until the next background refresh ([CP caching](https://docs.cyberark.com/credential-providers/latest/en/content/cp%20and%20ascp/configuring-caching.htm)). Our design bounds both the time and the error types.

### 5.7 Common mistakes

- Treating every `SecretsClientException` as transient. Today a 404 and a 503 can share that type, so a catch-all serves deleted secrets.
- Reusing `RetryPolicy.isRetryable` to decide "stale". With SDK-102 (`status >= 400`), stale-if-error serves deleted secrets.
- Keeping the current `get` that removes every expired entry. Then nothing is left to serve.
- Not evicting on 404, 401 or 403. A later 503 then serves the deleted secret.
- Serving stale silently, with no WARN and no counter.
- Logging the `Secret` value, or a cause message that contains it, in the WARN.
- An inclusive bound, so `maxStale` = 0 still serves stale at the exact expiry instant.
- `entries.remove(id)` instead of `entries.remove(id, entry)`, which can delete a fresh entry another thread just stored.
- Injecting one 503, which the retry loop absorbs. The test then passes without reaching the stale path.

### 5.8 Follow-up questions and good short answers

1. **"How slow is a stale serve during an outage?"** Each call pays the full retry budget first: 200 + 400 + 800 ms of backoff with the defaults, plus four attempts. If every attempt hits the 10-second request timeout, one call takes about 41 s. Add a short per-id backoff window that serves stale at once. AWS's client uses a jittered per-item backoff for the same reason ([SecretCacheObject.java](https://github.com/aws/aws-secretsmanager-caching-java/blob/v2/src/main/java/com/amazonaws/secretsmanager/caching/cache/SecretCacheObject.java)).
2. **"Why not serve stale after a 401?"** A 401 after the token refresh means the server rejected our identity. Serving stale would keep a revoked client working.
3. **"The WARN floods the log during a 30-minute outage. Fix it."** Log the first stale serve per id, then once a minute with a count. The counter keeps the full number.
4. **"How would security cap this per secret?"** The server sends `Cache-Control` with `max-age` and `stale-if-error` per secret, and `stale-if-error=0` for secrets that must never be stale. The client uses the stricter of its config and the server's value.
5. **"What happens in `getSecrets` with 30 ids during an outage?"** Each id follows the table on its own. Stale values count as successes, so the call succeeds if every id has a usable value.

### 5.9 What separates senior from mid

- **Senior signal:** She classifies failures by type with an explicit allow-list, and anything unknown fails closed.
- **Senior signal:** She evicts on 404, 401 and 403, so a later outage cannot bring a deleted or revoked secret back.
- **Senior signal:** She sees the outage latency (every call pays the retry budget) and proposes a per-id backoff window.
- **Senior signal:** She ties `maxStale` to the rotation grace period and wants the server to cap it per secret.
- **Senior signal:** She keeps 2.3 behaviour by default: `maxStale` is off, and the new exception is a subclass.
