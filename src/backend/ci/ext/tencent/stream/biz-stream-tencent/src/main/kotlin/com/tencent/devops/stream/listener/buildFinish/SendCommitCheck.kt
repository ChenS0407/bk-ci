package com.tencent.devops.stream.listener.buildFinish

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.tencent.devops.common.api.enums.BuildReviewType
import com.tencent.devops.common.api.exception.ParamBlankException
import com.tencent.devops.common.ci.v2.enums.gitEventKind.TGitObjectKind
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.pipeline.enums.BuildStatus
import com.tencent.devops.common.pipeline.enums.ChannelCode
import com.tencent.devops.common.pipeline.enums.ManualReviewAction
import com.tencent.devops.process.api.service.ServiceBuildResource
import com.tencent.devops.stream.client.ScmClient
import com.tencent.devops.stream.config.StreamBuildFinishConfig
import com.tencent.devops.stream.listener.StreamBuildListenerContext
import com.tencent.devops.stream.listener.StreamFinishContextV1
import com.tencent.devops.stream.listener.StreamBuildListenerContextV2
import com.tencent.devops.stream.listener.StreamBuildStageListenerContextV2
import com.tencent.devops.stream.listener.getBuildStatus
import com.tencent.devops.stream.listener.getGitCommitCheckState
import com.tencent.devops.stream.listener.isSuccess
import com.tencent.devops.stream.pojo.GitRequestEvent
import com.tencent.devops.stream.pojo.enums.StreamMrEventAction
import com.tencent.devops.stream.pojo.git.GitEvent
import com.tencent.devops.stream.pojo.git.GitMergeRequestEvent
import com.tencent.devops.stream.pojo.isMr
import com.tencent.devops.stream.trigger.GitCheckService
import com.tencent.devops.stream.utils.GitCIPipelineUtils
import com.tencent.devops.stream.utils.StreamTriggerMessageUtils
import com.tencent.devops.stream.v2.service.StreamQualityService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Suppress("NestedBlockDepth")
@Component
class SendCommitCheck @Autowired constructor(
    private val objectMapper: ObjectMapper,
    private val client: Client,
    private val scmClient: ScmClient,
    private val config: StreamBuildFinishConfig,
    private val gitCheckService: GitCheckService,
    private val triggerMessageUtil: StreamTriggerMessageUtils,
    private val streamQualityService: StreamQualityService
) {
    companion object {
        private val logger = LoggerFactory.getLogger(SendCommitCheck::class.java)
        private const val BUILD_RUNNING_DESC = "Your pipeline「%s」is running."
        private const val BUILD_STAGE_SUCCESS_DESC =
            "Warning: your pipeline「%s」 is stage succeed. Rejected by %s, reason is %s."
        private const val BUILD_SUCCESS_DESC = "Your pipeline「%s」 is succeed."
        private const val BUILD_CANCEL_DESC = "Your pipeline「%s」 was cancelled."
        private const val BUILD_FAILED_DESC = "Your pipeline「%s」 is failed."
        private const val BUILD_GATE_REVIEW_DESC =
            "Pending: Gate access requirement is not met. Gatekeeper approval is needed."
        private const val BUILD_MANUAL_REVIEW_DESC =
            "Pending: Pipeline approval is needed."
    }

    fun sendCommitCheck(
        context: StreamBuildListenerContext
    ) {
        // 当人工触发时不推送CommitCheck消息
        if (!context.requestEvent.sendCommitCheck()) {
            return
        }

        try {
            when (context) {
                is StreamBuildListenerContextV2 -> {
                    sendCommitCheckV2(context)
                }
                is StreamFinishContextV1 -> {
                    sendCommitCheckV1(context)
                }
            }
        } catch (e: Throwable) {
            logger.error("sendCommitCheck error: ${context.requestEvent}")
        }
    }

    private fun sendCommitCheckV2(
        context: StreamBuildListenerContextV2
    ) {
        with(context) {
            // gitRequestEvent中存的为mriid不是mrid
            val gitEvent = try {
                objectMapper.readValue<GitEvent>(requestEvent.event)
            } catch (e: Throwable) {
                logger.error("push commit check get mergeId error ${e.message}")
                null
            }

            gitCheckService.pushCommitCheck(
                commitId = requestEvent.commitId,
                description = triggerMessageUtil.getCommitCheckDesc(
                    prefix = getDescByBuildStatus(this),
                    objectKind = requestEvent.objectKind,
                    action = if (gitEvent is GitMergeRequestEvent) {
                        StreamMrEventAction.getActionValue(gitEvent) ?: ""
                    } else {
                        ""
                    },
                    userId = buildEvent.userId
                ),
                mergeRequestId = if (gitEvent is GitMergeRequestEvent) {
                    gitEvent.object_attributes.id
                } else {
                    null
                },
                buildId = buildEvent.buildId,
                userId = buildEvent.userId,
                status = getGitCommitCheckState(),
                context = "${pipeline.filePath}@${requestEvent.objectKind.toUpperCase()}",
                gitCIBasicSetting = streamSetting,
                pipelineId = buildEvent.pipelineId,
                block = requestEvent.isMr() && !context.isSuccess() && streamSetting.enableMrBlock,
                reportData = streamQualityService.getQualityGitMrResult(
                    client = client,
                    gitProjectId = streamSetting.gitProjectId,
                    pipelineName = pipeline.displayName,
                    event = buildEvent
                ),
                targetUrl = GitCIPipelineUtils.genGitCIV2BuildUrl(
                    homePage = config.v2GitUrl ?: throw ParamBlankException("启动配置缺少 rtx.v2GitUrl"),
                    gitProjectId = streamSetting.gitProjectId,
                    pipelineId = pipeline.pipelineId,
                    buildId = buildEvent.buildId
                )
            )
        }
    }

    // 根据状态切换描述
    private fun getDescByBuildStatus(context: StreamBuildListenerContextV2): String {
        val pipelineName = context.pipeline.displayName
        return when (context.getBuildStatus()) {
            BuildStatus.REVIEWING -> {
                getStageReviewDesc(context, pipelineName)
            }
            BuildStatus.REVIEW_PROCESSED -> {
                BUILD_RUNNING_DESC.format(pipelineName)
            }
            else -> {
                getFinishDesc(context, pipelineName)
            }
        }
    }

    private fun getStageReviewDesc(
        context: StreamBuildListenerContextV2,
        pipelineName: String
    ): String {
        if (context !is StreamBuildStageListenerContextV2) {
            return BUILD_RUNNING_DESC.format(pipelineName)
        }
        return when (context.reviewType) {
            BuildReviewType.STAGE_REVIEW -> {
                BUILD_MANUAL_REVIEW_DESC
            }
            BuildReviewType.QUALITY_CHECK_IN, BuildReviewType.QUALITY_CHECK_OUT -> {
                BUILD_GATE_REVIEW_DESC
            }
            // 这里先这么写，未来如果这么枚举扩展代码编译时可以第一时间感知，防止漏过事件
            BuildReviewType.TASK_REVIEW -> {
                logger.warn("buildReviewListener event not match: ${context.reviewType}")
                BUILD_RUNNING_DESC.format(pipelineName)
            }
        }
    }

    private fun getFinishDesc(
        context: StreamBuildListenerContextV2,
        pipelineName: String
    ) = when {
        (context.isSuccess()) -> {
            if (context.getBuildStatus() == BuildStatus.STAGE_SUCCESS) {
                val (name, reason) = getReviewInfo(context)
                BUILD_STAGE_SUCCESS_DESC.format(pipelineName, name, reason)
            } else {
                BUILD_SUCCESS_DESC.format(pipelineName)
            }
        }
        context.getBuildStatus().isCancel() -> {
            BUILD_CANCEL_DESC.format(pipelineName)
        }
        else -> {
            BUILD_FAILED_DESC.format(pipelineName)
        }
    }

    private fun getReviewInfo(context: StreamBuildListenerContextV2): Pair<String, String> {
        val model = try {
            client.get(ServiceBuildResource::class).getBuildDetail(
                userId = context.buildEvent.userId,
                projectId = context.buildEvent.projectId,
                pipelineId = context.buildEvent.pipelineId,
                buildId = context.buildEvent.buildId,
                channelCode = ChannelCode.GIT
            ).data!!.model
        } catch (e: Exception) {
            logger.warn("get build finish model info error: ${e.message}")
            return Pair(" ", " ")
        }
        model.stages.forEach { stage ->
            if (stage.checkIn?.status == BuildStatus.REVIEW_ABORT.name) {
                stage.checkIn?.reviewGroups?.forEach { review ->
                    if (review.status == ManualReviewAction.ABORT.name) {
                        return Pair(review.operator ?: " ", review.suggest ?: " ")
                    }
                }
            }
        }
        return Pair(" ", " ")
    }

    private fun sendCommitCheckV1(
        context: StreamFinishContextV1
    ) {
        with(context) {
            scmClient.pushCommitCheck(
                commitId = requestEvent.commitId,
                description = requestEvent.commitMsg ?: "",
                mergeRequestId = requestEvent.mergeRequestId,
                pipelineId = pipeline.pipelineId,
                buildId = buildEvent.buildId,
                userId = buildEvent.userId,
                status = getGitCommitCheckState(),
                context = "${pipeline.displayName}(${pipeline.filePath})",
                gitProjectConf = streamSetting
            )
        }
    }
}

// 当人工触发时不推送CommitCheck消息
private fun GitRequestEvent.sendCommitCheck() = objectKind != TGitObjectKind.MANUAL.value
