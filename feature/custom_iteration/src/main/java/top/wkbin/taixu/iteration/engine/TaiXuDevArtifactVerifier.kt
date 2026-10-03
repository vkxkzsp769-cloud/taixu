package top.wkbin.taixu.iteration.engine

/**
 * TaiXuDevArtifactVerifier — TaiXuDev 预览包产物的元数据门禁校验器。
 *
 * 职责：在 GitHub Actions 产物下载之后、真机安装之前，校验「包名 / 应用名 / 版本后缀 / SHA-256」，
 * 确保 TaiXuDev（`top.wkbin.taixu.dev`）与正式版（`top.wkbin.taixu`）、本地调试包
 * （`top.wkbin.taixu.debug`）完全独立共存，绝不误覆盖用户数据。
 *
 * 设计约束：纯 Kotlin 实现（零 `android.*` / `androidx.*` 依赖），既可被 Compose 层直接调用，
 * 也可在 JVM 单元测试与 CI 门禁中复用。
 */
object TaiXuDevArtifactVerifier {

    /** TaiXuDev 预览包唯一合法包名（`TAIXU_DEV_BUILD=1` 时由 app 模块注入）。 */
    const val DEV_APPLICATION_ID = "top.wkbin.taixu.dev"

    /** TaiXuDev 预览包唯一合法应用名（launcher label）。 */
    const val DEV_APPLICATION_LABEL = "TaiXuDev"

    /** TaiXuDev 预览包版本名后缀。 */
    const val DEV_VERSION_SUFFIX = "-dev"

    /** 已被其它构建变体占用的标识：命中即判定为共存冲突。 */
    private val RESERVED_APPLICATION_IDS = setOf(
        "top.wkbin.taixu",
        "top.wkbin.taixu.debug",
    )

    private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
    private val WHITESPACE = Regex("\\s+")

    enum class CheckKind {
        APPLICATION_ID,
        APPLICATION_LABEL,
        VERSION_SUFFIX,
        SHA256,
    }

    enum class Severity {
        /** 失败即禁止安装。 */
        BLOCKING,

        /** 仅提示，不阻断共存安装。 */
        WARNING,
    }

    data class Check(
        val kind: CheckKind,
        val passed: Boolean,
        val severity: Severity,
        val detail: String,
    )

    data class ArtifactMetadata(
        val applicationId: String,
        val applicationLabel: String,
        val versionName: String,
        val expectedSha256: String,
        val actualSha256: String,
    )

    data class VerificationReport(
        val checks: List<Check>,
    ) {
        val blockingFailures: List<Check>
            get() = checks.filter { !it.passed && it.severity == Severity.BLOCKING }

        val warnings: List<Check>
            get() = checks.filter { !it.passed && it.severity == Severity.WARNING }

        val passed: Boolean
            get() = blockingFailures.isEmpty()

        /** 面向 UI 单行展示的紧凑摘要，符号：✓ 通过 / ✗ 阻断 / ! 告警。 */
        fun summary(): String {
            val verdict = if (passed) "TaiXuDev 产物校验通过" else "TaiXuDev 产物校验失败"
            val marks = checks.joinToString(" · ") { check ->
                val symbol = when {
                    check.passed -> "✓"
                    check.severity == Severity.BLOCKING -> "✗"
                    else -> "!"
                }
                "$symbol${check.kind.name}"
            }
            return "$verdict：$marks"
        }
    }

    fun verify(metadata: ArtifactMetadata): VerificationReport = VerificationReport(
        checks = listOf(
            checkApplicationId(metadata.applicationId),
            checkApplicationLabel(metadata.applicationLabel),
            checkVersionSuffix(metadata.versionName),
            checkSha256(metadata.expectedSha256, metadata.actualSha256),
        ),
    )

    /**
     * 解析 CI 产物 `sha256sum` 校验文件（形如 `<hash>  output/TaiXuDev-arm64-debug.apk`）。
     *
     * @return 小写十六进制摘要；文件为空或摘要非法时返回 null。
     */
    fun parseChecksumFile(content: String): String? {
        val firstToken = content
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.split(WHITESPACE)
            ?.firstOrNull()
            .orEmpty()
        return if (SHA256_PATTERN.matches(firstToken)) firstToken.lowercase() else null
    }

    private fun checkApplicationId(applicationId: String): Check {
        val actual = applicationId.trim()
        val passed = actual == DEV_APPLICATION_ID
        val detail = when {
            passed -> DEV_APPLICATION_ID
            actual.isBlank() -> "产物未声明包名（APK 解析失败或下载不完整）"
            RESERVED_APPLICATION_IDS.contains(actual) ->
                "包名 $actual 已被正式版/调试包占用，安装会覆盖同包数据"
            else -> "期望 $DEV_APPLICATION_ID，实际 $actual"
        }
        return Check(CheckKind.APPLICATION_ID, passed, Severity.BLOCKING, detail)
    }

    private fun checkApplicationLabel(applicationLabel: String): Check {
        val actual = applicationLabel.trim()
        val passed = actual == DEV_APPLICATION_LABEL
        val detail = when {
            passed -> DEV_APPLICATION_LABEL
            actual.isBlank() -> "产物未声明应用名（APK 解析失败）"
            else -> "期望 $DEV_APPLICATION_LABEL，实际 $actual"
        }
        return Check(CheckKind.APPLICATION_LABEL, passed, Severity.BLOCKING, detail)
    }

    private fun checkVersionSuffix(versionName: String): Check {
        val actual = versionName.trim()
        val passed = actual.endsWith(DEV_VERSION_SUFFIX)
        val detail = when {
            passed -> actual
            actual.isBlank() -> "产物未声明版本名"
            else -> "版本名缺少 $DEV_VERSION_SUFFIX 后缀：$actual"
        }
        return Check(CheckKind.VERSION_SUFFIX, passed, Severity.WARNING, detail)
    }

    private fun checkSha256(expectedSha256: String, actualSha256: String): Check {
        val expected = expectedSha256.trim().lowercase()
        val actual = actualSha256.trim().lowercase()
        val detail: String
        val passed: Boolean
        when {
            expected.isBlank() || !SHA256_PATTERN.matches(expected) -> {
                passed = false
                detail = "缺少合法的期望摘要（CI 未产出 .sha256 或内容损坏）"
            }
            actual.isBlank() || !SHA256_PATTERN.matches(actual) -> {
                passed = false
                detail = "本地摘要不是合法的 64 位十六进制字符串"
            }
            expected == actual -> {
                passed = true
                detail = "SHA-256 一致"
            }
            else -> {
                passed = false
                detail = "SHA-256 不一致，产物可能在弱网下载中被截断或篡改"
            }
        }
        return Check(CheckKind.SHA256, passed, Severity.BLOCKING, detail)
    }
}
