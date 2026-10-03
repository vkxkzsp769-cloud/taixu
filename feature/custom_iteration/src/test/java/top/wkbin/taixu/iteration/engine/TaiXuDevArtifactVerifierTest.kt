package top.wkbin.taixu.iteration.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wkbin.taixu.iteration.engine.TaiXuDevArtifactVerifier.ArtifactMetadata
import top.wkbin.taixu.iteration.engine.TaiXuDevArtifactVerifier.Check
import top.wkbin.taixu.iteration.engine.TaiXuDevArtifactVerifier.CheckKind
import top.wkbin.taixu.iteration.engine.TaiXuDevArtifactVerifier.Severity
import top.wkbin.taixu.iteration.engine.TaiXuDevArtifactVerifier.VerificationReport

private val VALID_SHA = "a".repeat(64)
private val OTHER_SHA = "b".repeat(64)

class TaiXuDevArtifactVerifierTest {

    private fun devArtifact(
        applicationId: String = TaiXuDevArtifactVerifier.DEV_APPLICATION_ID,
        applicationLabel: String = TaiXuDevArtifactVerifier.DEV_APPLICATION_LABEL,
        versionName: String = "0.6.3-beta2-dev",
        expectedSha256: String = VALID_SHA,
        actualSha256: String = VALID_SHA,
    ) = ArtifactMetadata(
        applicationId = applicationId,
        applicationLabel = applicationLabel,
        versionName = versionName,
        expectedSha256 = expectedSha256,
        actualSha256 = actualSha256,
    )

    private fun VerificationReport.checkOf(kind: CheckKind): Check =
        checks.first { it.kind == kind }

    @Test
    fun compliantDevArtifactPassesAllChecks() {
        val report = TaiXuDevArtifactVerifier.verify(devArtifact())

        assertTrue(report.passed)
        assertTrue(report.blockingFailures.isEmpty())
        assertTrue(report.warnings.isEmpty())
        assertEquals(4, report.checks.size)
        assertTrue(report.checkOf(CheckKind.APPLICATION_ID).passed)
        assertTrue(report.checkOf(CheckKind.APPLICATION_LABEL).passed)
        assertTrue(report.checkOf(CheckKind.VERSION_SUFFIX).passed)
        assertTrue(report.checkOf(CheckKind.SHA256).passed)
    }

    @Test
    fun releasePackageIdIsBlockingCoexistenceConflict() {
        val report = TaiXuDevArtifactVerifier.verify(devArtifact(applicationId = "top.wkbin.taixu"))

        assertFalse(report.passed)
        val check = report.checkOf(CheckKind.APPLICATION_ID)
        assertEquals(Severity.BLOCKING, check.severity)
        assertTrue(check.detail.contains("已被正式版/调试包占用"))
    }

    @Test
    fun debugPackageIdIsAlsoBlocking() {
        val report = TaiXuDevArtifactVerifier.verify(devArtifact(applicationId = "top.wkbin.taixu.debug"))

        assertFalse(report.passed)
        assertTrue(report.checkOf(CheckKind.APPLICATION_ID).detail.contains("已被正式版/调试包占用"))
    }

    @Test
    fun unexpectedPackageIdReportsExpectedAndActual() {
        val report = TaiXuDevArtifactVerifier.verify(devArtifact(applicationId = "com.example.app"))

        assertFalse(report.passed)
        val check = report.checkOf(CheckKind.APPLICATION_ID)
        assertTrue(check.detail.contains(TaiXuDevArtifactVerifier.DEV_APPLICATION_ID))
        assertTrue(check.detail.contains("com.example.app"))
    }

    @Test
    fun blankPackageIdMeansBrokenArtifact() {
        val report = TaiXuDevArtifactVerifier.verify(devArtifact(applicationId = "   "))

        assertFalse(report.passed)
        assertTrue(report.checkOf(CheckKind.APPLICATION_ID).detail.contains("APK 解析失败"))
    }

    @Test
    fun officialAppNameIsBlocking() {
        val report = TaiXuDevArtifactVerifier.verify(devArtifact(applicationLabel = "太墟"))

        assertFalse(report.passed)
        val check = report.checkOf(CheckKind.APPLICATION_LABEL)
        assertEquals(Severity.BLOCKING, check.severity)
        assertTrue(check.detail.contains("期望 TaiXuDev，实际 太墟"))
    }

    @Test
    fun appNameComparisonIgnoresSurroundingWhitespace() {
        val report = TaiXuDevArtifactVerifier.verify(devArtifact(applicationLabel = "  TaiXuDev  "))

        assertTrue(report.passed)
    }

    @Test
    fun missingDevVersionSuffixIsWarningOnly() {
        val report = TaiXuDevArtifactVerifier.verify(devArtifact(versionName = "0.6.3-beta2"))

        assertTrue("版本后缀缺失不应阻断共存安装", report.passed)
        assertEquals(1, report.warnings.size)
        val warning = report.warnings.first()
        assertEquals(CheckKind.VERSION_SUFFIX, warning.kind)
        assertEquals(Severity.WARNING, warning.severity)
        assertTrue(warning.detail.contains("-dev"))
    }

    @Test
    fun sha256MismatchIsBlocking() {
        val report = TaiXuDevArtifactVerifier.verify(devArtifact(expectedSha256 = VALID_SHA, actualSha256 = OTHER_SHA))

        assertFalse(report.passed)
        val check = report.checkOf(CheckKind.SHA256)
        assertEquals(Severity.BLOCKING, check.severity)
        assertTrue(check.detail.contains("截断或篡改"))
    }

    @Test
    fun sha256ComparisonIsCaseInsensitive() {
        val report = TaiXuDevArtifactVerifier.verify(
            devArtifact(expectedSha256 = VALID_SHA, actualSha256 = "A".repeat(64)),
        )

        assertTrue(report.passed)
    }

    @Test
    fun missingExpectedSha256IsBlocking() {
        val report = TaiXuDevArtifactVerifier.verify(devArtifact(expectedSha256 = ""))

        assertFalse(report.passed)
        assertTrue(report.checkOf(CheckKind.SHA256).detail.contains("期望摘要"))
    }

    @Test
    fun truncatedActualSha256IsBlocking() {
        val report = TaiXuDevArtifactVerifier.verify(devArtifact(expectedSha256 = VALID_SHA, actualSha256 = "abc123"))

        assertFalse(report.passed)
        assertTrue(report.checkOf(CheckKind.SHA256).detail.contains("64 位十六进制"))
    }

    @Test
    fun summaryMarksBlockingFailureWithCross() {
        val report = TaiXuDevArtifactVerifier.verify(devArtifact(applicationLabel = "TaiXu"))

        assertTrue(report.summary().startsWith("TaiXuDev 产物校验失败"))
        assertTrue(report.summary().contains("✗APPLICATION_LABEL"))
    }

    @Test
    fun summaryMarksWarningWithExclamation() {
        val report = TaiXuDevArtifactVerifier.verify(devArtifact(versionName = "1.0.0"))

        assertTrue(report.summary().startsWith("TaiXuDev 产物校验通过"))
        assertTrue(report.summary().contains("!VERSION_SUFFIX"))
    }

    @Test
    fun parseChecksumFileAcceptsSha256sumOutput() {
        val content = "$VALID_SHA  output/TaiXuDev-arm64-debug.apk\n"

        assertEquals(VALID_SHA, TaiXuDevArtifactVerifier.parseChecksumFile(content))
    }

    @Test
    fun parseChecksumFileNormalizesUppercaseHash() {
        val content = "${"F".repeat(64)}  TaiXuDev-arm64-debug.apk"

        assertEquals("f".repeat(64), TaiXuDevArtifactVerifier.parseChecksumFile(content))
    }

    @Test
    fun parseChecksumFileRejectsGarbage() {
        assertNull(TaiXuDevArtifactVerifier.parseChecksumFile(""))
        assertNull(TaiXuDevArtifactVerifier.parseChecksumFile("not-a-checksum"))
        assertNull(TaiXuDevArtifactVerifier.parseChecksumFile("abc123  broken.apk"))
    }

    @Test
    fun coordinatorExposesTheSameVerifier() {
        val report = TaiXuDevBuildCoordinator.verifyArtifact(devArtifact())

        assertTrue(report.passed)
        assertEquals(4, report.checks.size)
    }
}
