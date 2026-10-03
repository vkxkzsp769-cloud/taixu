package top.wkbin.taixu.iteration.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * TaiXuDevBuildCoordinator — 调度 GitHub Actions 云端构建与本地产物校验。
 */
object TaiXuDevBuildCoordinator {

    sealed interface BuildStatus {
        data object Idle : BuildStatus
        data class Dispathing(val branch: String) : BuildStatus
        data class Running(val runId: String, val statusText: String) : BuildStatus
        data class Downloading(val runId: String, val progress: Int) : BuildStatus
        data class Success(val apkPath: String, val sha256: String) : BuildStatus
        data class Failed(val error: String, val logs: String? = null) : BuildStatus
    }

    /**
     * 构建相关的核心 CLI 指令模板
     */
    object CliCommands {
        fun triggerWorkflow(workflowName: String = "taixudev-build.yml", branch: String = "main"): String =
            "gh workflow run $workflowName --ref $branch"

        fun watchWorkflow(runId: String): String =
            "gh run watch $runId --exit-status"

        fun downloadArtifact(runId: String, outputDir: String = "/storage/emulated/0/Download"): String =
            "gh run download $runId -n taixudev-apk -D $outputDir"

        fun checkFailedLog(runId: String): String =
            "gh run view $runId --log-failed"
    }

    /**
     * 产物门禁：APK 下载完成后、安装之前校验包名 / 应用名 / 版本后缀 / SHA-256。
     *
     * 任一 BLOCKING 项失败即视为不可安装，避免预览包误覆盖正式版数据。
     */
    fun verifyArtifact(
        metadata: TaiXuDevArtifactVerifier.ArtifactMetadata,
    ): TaiXuDevArtifactVerifier.VerificationReport = TaiXuDevArtifactVerifier.verify(metadata)
}
