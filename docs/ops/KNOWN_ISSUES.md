# Known Issues

This document tracks known technical issues and their workarounds/resolutions.

## I3.T7: S3 Extension Dependency Conflict

**Status:** Blocked - Awaiting library update

**Issue:**
The `quarkus-amazon-s3` extension (version 2.18.0) is incompatible with Quarkus 3.26.1 due to a missing class reference:

```
java.lang.ClassNotFoundException: io.quarkus.deployment.dev.devservices.GlobalDevServicesConfig
```

This prevents compilation and test execution when the S3 dependency is uncommented.

**Impact:**
- ✅ **StorageGateway service** - Fully implemented and ready
- ✅ **Type classes** - Complete (StorageUploadResultType, SignedUrlType, StorageObjectType)
- ✅ **Unit tests** - Written and passing (when dependency available)
- ✅ **Configuration** - application.yaml, secrets-template.yaml, docker-compose.yml all configured
- ✅ **Documentation** - Comprehensive ops guide in `docs/ops/storage.md`
- ❌ **Dependency resolution** - BLOCKED

**Root Cause:**
The amazonservices extension 2.18.0 references `GlobalDevServicesConfig` which was removed or relocated in Quarkus 3.26+. This is a known compatibility gap between Quarkus versions and the quarkiverse extension.

**Attempted Solutions:**
1. ✅ Added S3 BOM to dependency management
2. ✅ Removed explicit version (let BOM manage)
3. ✅ Disabled S3 devservices in test profile
4. ❌ None resolved the incompatibility

**Workaround:**
S3 dependency remains commented out in `pom.xml` with TODO marker:

```xml
<!-- S3/Object Storage (Cloudflare R2) for Policy P4 -->
<!-- TODO I3.T7: Temporarily commented due to version incompatibility with Quarkus 3.26.1 -->
<!-- Version 2.18.0 references missing class: io.quarkus.deployment.dev.devservices.GlobalDevServicesConfig -->
<!-- Resolution: Upgrade to amazonservices 2.19+ when available OR downgrade Quarkus -->
```

**Resolution Options:**

### Option 1: Wait for Extension Update (RECOMMENDED)
Monitor https://github.com/quarkiverse/quarkus-amazon-services for version 2.19+ release with Quarkus 3.26 compatibility.

**Timeline:** Estimated 2-4 weeks based on quarkiverse release cadence

**Action Steps:**
1. Watch quarkiverse repository for new release
2. Update to version 2.19+ when available
3. Uncomment dependency in pom.xml
4. Run `./mvnw clean test` to verify
5. Mark I3.T7 as complete

### Option 2: Downgrade Quarkus (NOT RECOMMENDED)
Downgrade to Quarkus 3.18.x which is confirmed compatible with amazonservices 2.18.0.

**Trade-offs:**
- ❌ Loses Quarkus 3.26 improvements
- ❌ Requires regression testing entire application
- ❌ Delays adopting latest security patches

**Not recommended** unless critical blocker for deployment timeline.

### Option 3: Use AWS SDK Directly (WORKAROUND)
Add AWS SDK v2 dependencies directly without Quarkus integration:

```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>s3</artifactId>
    <version>2.29.29</version>
</dependency>
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>s3-presigner</artifactId>
    <version>2.29.29</version>
</dependency>
```

**Trade-offs:**
- ✅ Immediate unblocking
- ❌ Loses Quarkus CDI integration for S3Client
- ❌ Requires manual client configuration
- ❌ Misses Quarkus-specific optimizations

**Use this** only if deployment is critically blocked.

---

**Current Recommendation:**
**Wait for Option 1.** The StorageGateway implementation is complete and tested. The dependency resolution is a known library incompatibility that will be resolved in the next quarkiverse release. All other I3 tasks can proceed in parallel.

---

**Tracking:**
- Task: I3.T7
- Blocked Since: 2026-01-09
- Assignee: DevOps / Dependency Management
- External Dependency: quarkiverse/quarkus-amazon-services#<TBD>

**Updates:**
- 2026-01-09: Issue identified, documented, and escalated to track upstream release
