/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.devops.stream.trigger.v2

import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.common.api.exception.ParamBlankException
import com.tencent.devops.common.ci.v2.ScriptBuildYaml
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.kafka.KafkaClient
import com.tencent.devops.common.kafka.KafkaTopic.STREAM_BUILD_INFO_TOPIC
import com.tencent.devops.common.pipeline.Model
import com.tencent.devops.common.pipeline.enums.ChannelCode
import com.tencent.devops.stream.config.StreamGitConfig
import com.tencent.devops.stream.dao.GitPipelineResourceDao
import com.tencent.devops.stream.dao.GitRequestEventBuildDao
import com.tencent.devops.stream.pojo.GitProjectPipeline
import com.tencent.devops.stream.pojo.GitRequestEvent
import com.tencent.devops.stream.pojo.enums.GitCICommitCheckState
import com.tencent.devops.stream.pojo.enums.TriggerReason
import com.tencent.devops.stream.pojo.v2.GitCIBasicSetting
import com.tencent.devops.stream.pojo.v2.StreamBuildInfo
import com.tencent.devops.stream.trigger.GitCIEventService
import com.tencent.devops.stream.trigger.GitCheckService
import com.tencent.devops.stream.utils.GitCIPipelineUtils
import com.tencent.devops.stream.utils.GitCommonUtils
import com.tencent.devops.stream.v2.service.StreamWebsocketService
import com.tencent.devops.process.api.service.ServiceBuildResource
import com.tencent.devops.process.api.service.ServicePipelineResource
import com.tencent.devops.process.pojo.BuildId
import com.tencent.devops.store.api.atom.ServiceMarketAtomResource
import com.tencent.devops.store.pojo.atom.InstallAtomReq
import com.tencent.devops.common.ci.v2.enums.gitEventKind.TGitObjectKind
import com.tencent.devops.stream.pojo.isFork
import com.tencent.devops.process.utils.PIPELINE_NAME
import com.tencent.devops.stream.utils.CommitCheckUtils
import com.tencent.devops.stream.utils.StreamTriggerMessageUtils
import com.tencent.devops.stream.v2.service.StreamPipelineBranchService
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Service
abstract class StreamYamlBaseBuild<T> @Autowired constructor(
    private val client: Client,
    private val kafkaClient: KafkaClient,
    private val dslContext: DSLContext,
    private val gitPipelineResourceDao: GitPipelineResourceDao,
    private val gitRequestEventBuildDao: GitRequestEventBuildDao,
    private val gitCIEventSaveService: GitCIEventService,
    private val websocketService: StreamWebsocketService,
    private val streamPipelineBranchService: StreamPipelineBranchService,
    private val gitCheckService: GitCheckService,
    private val streamGitConfig: StreamGitConfig,
    private val triggerMessageUtil: StreamTriggerMessageUtils
) {
    companion object {
        private val logger = LoggerFactory.getLogger(StreamYamlBaseBuild::class.java)
        private const val ymlVersion = "v2.0"
    }

    private val channelCode = ChannelCode.GIT

    private val buildRunningDesc = "Your pipeline「%s」is running."

    abstract fun gitStartBuild(
        pipeline: GitProjectPipeline,
        event: GitRequestEvent,
        yaml: ScriptBuildYaml,
        originYaml: String,
        parsedYaml: String?,
        normalizedYaml: String,
        gitBuildId: Long?
    ): BuildId?

    fun savePipeline(
        pipeline: GitProjectPipeline,
        event: GitRequestEvent,
        gitCIBasicSetting: GitCIBasicSetting,
        model: Model
    ) {
        val processClient = client.get(ServicePipelineResource::class)
        if (pipeline.pipelineId.isBlank()) {
            // 直接新建
            logger.info("create newpipeline: $pipeline")

            pipeline.pipelineId =
                processClient.create(event.userId, gitCIBasicSetting.projectCode!!, model, channelCode).data!!.id
            gitPipelineResourceDao.createPipeline(
                dslContext = dslContext,
                gitProjectId = gitCIBasicSetting.gitProjectId,
                pipeline = pipeline,
                version = ymlVersion
            )
            websocketService.pushPipelineWebSocket(
                projectId = "git_${gitCIBasicSetting.gitProjectId}",
                pipelineId = pipeline.pipelineId,
                userId = event.userId
            )
        } else if (needReCreate(processClient, event, gitCIBasicSetting, pipeline)) {
            val oldPipelineId = pipeline.pipelineId
            // 先删除已有数据
            logger.info("recreate pipeline: $pipeline")
            try {
                gitPipelineResourceDao.deleteByPipelineId(dslContext, oldPipelineId)
                processClient.delete(event.userId, gitCIBasicSetting.projectCode!!, oldPipelineId, channelCode)
            } catch (e: Exception) {
                logger.error("failed to delete pipeline resource, pipeline: $pipeline", e)
            }
            // 再次新建
            pipeline.pipelineId =
                processClient.create(event.userId, gitCIBasicSetting.projectCode!!, model, channelCode).data!!.id
            gitPipelineResourceDao.createPipeline(
                dslContext = dslContext,
                gitProjectId = gitCIBasicSetting.gitProjectId,
                pipeline = pipeline,
                version = ymlVersion
            )
            // 对于需要删了重建的，删除旧的流水线-分支记录
            streamPipelineBranchService.deleteBranch(
                gitProjectId = gitCIBasicSetting.gitProjectId,
                pipelineId = oldPipelineId,
                branch = null
            )
            websocketService.pushPipelineWebSocket(
                projectId = "git_${gitCIBasicSetting.gitProjectId}",
                pipelineId = pipeline.pipelineId,
                userId = event.userId
            )
        } else if (pipeline.pipelineId.isNotBlank()) {
            // 编辑流水线model
            processClient.edit(event.userId, gitCIBasicSetting.projectCode!!, pipeline.pipelineId, model, channelCode)
            // 已有的流水线需要更新下Stream这里的状态
            logger.info("update gitPipeline pipeline: $pipeline")
            gitPipelineResourceDao.updatePipeline(
                dslContext = dslContext,
                gitProjectId = gitCIBasicSetting.gitProjectId,
                pipelineId = pipeline.pipelineId,
                displayName = pipeline.displayName,
                version = ymlVersion
            )
        }
    }

    fun startBuild(
        pipeline: GitProjectPipeline,
        event: GitRequestEvent,
        gitCIBasicSetting: GitCIBasicSetting,
        model: Model,
        gitBuildId: Long
    ): BuildId? {
        val processClient = client.get(ServicePipelineResource::class)
        // 修改流水线并启动构建，需要加锁保证事务性
        var buildId = ""
        try {
            logger.info("GitCI Build start, gitProjectId[${gitCIBasicSetting.gitProjectId}], " +
                "pipelineId[${pipeline.pipelineId}], gitBuildId[$gitBuildId]")
            buildId =
                startupPipelineBuild(processClient, model, event, gitCIBasicSetting, pipeline.pipelineId,
                    pipeline.displayName)
            logger.info("GitCI Build success, gitProjectId[${gitCIBasicSetting.gitProjectId}], " +
                "pipelineId[${pipeline.pipelineId}], gitBuildId[$gitBuildId], buildId[$buildId]")
            gitPipelineResourceDao.updatePipelineBuildInfo(dslContext, pipeline, buildId, ymlVersion)
            gitRequestEventBuildDao.update(dslContext, gitBuildId, pipeline.pipelineId, buildId, ymlVersion)
            // 成功构建的添加 流水线-分支 记录
            if (!event.isFork() &&
                (event.objectKind == TGitObjectKind.PUSH.value ||
                event.objectKind == TGitObjectKind.MERGE_REQUEST.value)
            ) {
                streamPipelineBranchService.saveOrUpdate(
                    gitProjectId = gitCIBasicSetting.gitProjectId,
                    pipelineId = pipeline.pipelineId,
                    branch = event.branch
                )
            }
            // 推送启动构建消息,当人工触发时不推送构建消息
            if (CommitCheckUtils.needSendCheck(event)) {
                gitCheckService.pushCommitCheck(
                    commitId = event.commitId,
                    description = triggerMessageUtil.getCommitCheckDesc(
                        event,
                        buildRunningDesc.format(pipeline.displayName)
                    ),
                    mergeRequestId = event.mergeRequestId ?: 0L,
                    buildId = buildId,
                    userId = event.userId,
                    status = GitCICommitCheckState.PENDING,
                    context = "${pipeline.filePath}@${event.objectKind.toUpperCase()}",
                    gitCIBasicSetting = gitCIBasicSetting,
                    targetUrl = GitCIPipelineUtils.genGitCIV2BuildUrl(
                        homePage = streamGitConfig.tGitUrl ?: throw ParamBlankException("启动配置缺少 rtx.v2GitUrl"),
                        projectName = GitCommonUtils.getRepoName(gitCIBasicSetting.gitHttpUrl, gitCIBasicSetting.name),
                        pipelineId = pipeline.pipelineId,
                        buildId = buildId
                    ),
                    block = (event.objectKind == TGitObjectKind.MERGE_REQUEST.value && gitCIBasicSetting.enableMrBlock),
                    pipelineId = pipeline.pipelineId
                )
            }
            return BuildId(buildId)
        } catch (e: Throwable) {
            logger.error(
                "GitCI Build failed, gitProjectId[${gitCIBasicSetting.gitProjectId}], " +
                    "pipelineId[${pipeline.pipelineId}], gitBuildId[$gitBuildId]",
                e
            )
            val build = gitRequestEventBuildDao.getByGitBuildId(dslContext, gitBuildId)
            gitCIEventSaveService.saveRunNotBuildEvent(
                userId = event.userId,
                eventId = event.id!!,
                pipelineId = pipeline.pipelineId,
                pipelineName = pipeline.displayName,
                filePath = pipeline.filePath,
                originYaml = build?.originYaml,
                normalizedYaml = build?.normalizedYaml,
                reason = TriggerReason.PIPELINE_RUN_ERROR.name,
                reasonDetail = e.message ?: TriggerReason.PIPELINE_RUN_ERROR.detail,
                gitProjectId = event.gitProjectId,
                sendCommitCheck = true,
                commitCheckBlock = (event.objectKind == TGitObjectKind.MERGE_REQUEST.value),
                version = ymlVersion,
                branch = event.branch
            )
            if (build != null) gitRequestEventBuildDao.removeBuild(dslContext, gitBuildId)
        } finally {
            if (buildId.isNotEmpty()) {
                kafkaClient.send(STREAM_BUILD_INFO_TOPIC, JsonUtil.toJson(StreamBuildInfo(
                    buildId = buildId,
                    streamYamlUrl = "${gitCIBasicSetting.homepage}/blob/${event.commitId}/${pipeline.filePath}",
                    washTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                )))
            }
        }

        return null
    }

    private fun startupPipelineBuild(
        processClient: ServicePipelineResource,
        model: Model,
        event: GitRequestEvent,
        gitCIBasicSetting: GitCIBasicSetting,
        pipelineId: String,
        pipelineName: String
    ): String {
        processClient.edit(event.userId, gitCIBasicSetting.projectCode!!, pipelineId, model, channelCode)
        return client.get(ServiceBuildResource::class).manualStartup(
            userId = event.userId,
            projectId = gitCIBasicSetting.projectCode!!,
            pipelineId = pipelineId,
            values = mapOf(PIPELINE_NAME to pipelineName),
            channelCode = channelCode
        ).data!!.id
    }

    private fun needReCreate(
        processClient: ServicePipelineResource,
        event: GitRequestEvent,
        gitCIBasicSetting: GitCIBasicSetting,
        pipeline: GitProjectPipeline
    ): Boolean {
        try {
            val response =
                processClient.get(event.userId, gitCIBasicSetting.projectCode!!, pipeline.pipelineId, channelCode)
            if (response.isNotOk()) {
                logger.error("get pipeline failed, msg: ${response.message}")
                return true
            }
        } catch (e: Exception) {
            logger.error("get pipeline failed, pipelineId: ${pipeline.pipelineId}, " +
                "projectCode: ${gitCIBasicSetting.projectCode}, error msg: ${e.message}")
            return true
        }
        return false
    }

    fun installMarketAtom(gitCIBasicSetting: GitCIBasicSetting, userId: String, atomCode: String) {
        val projectCodes = ArrayList<String>()
        projectCodes.add(gitCIBasicSetting.projectCode!!)
        try {
            client.get(ServiceMarketAtomResource::class).installAtom(
                userId = userId,
                channelCode = channelCode,
                installAtomReq = InstallAtomReq(projectCodes, atomCode)
            )
        } catch (e: Throwable) {
            logger.error("install atom($atomCode) failed, exception:", e)
            // 可能之前安装过，继续执行不退出
        }
    }
}
