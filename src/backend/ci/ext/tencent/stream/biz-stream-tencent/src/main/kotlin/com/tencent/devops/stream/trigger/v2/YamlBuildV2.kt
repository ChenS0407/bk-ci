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

import com.fasterxml.jackson.core.JsonProcessingException
import com.tencent.devops.common.api.exception.CustomException
import com.tencent.devops.common.api.exception.ErrorCodeException
import com.tencent.devops.common.api.exception.ParamBlankException
import com.tencent.devops.common.ci.v2.ScriptBuildYaml
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.kafka.KafkaClient
import com.tencent.devops.common.pipeline.Model
import com.tencent.devops.common.pipeline.container.Stage
import com.tencent.devops.common.pipeline.container.TriggerContainer
import com.tencent.devops.common.pipeline.pojo.element.trigger.ManualTriggerElement
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.stream.common.exception.CommitCheck
import com.tencent.devops.stream.common.exception.QualityRulesException
import com.tencent.devops.stream.common.exception.TriggerBaseException
import com.tencent.devops.stream.common.exception.TriggerException
import com.tencent.devops.stream.common.exception.Yamls
import com.tencent.devops.stream.config.StreamGitConfig
import com.tencent.devops.stream.dao.GitPipelineResourceDao
import com.tencent.devops.stream.dao.GitRequestEventBuildDao
import com.tencent.devops.stream.pojo.GitCITriggerLock
import com.tencent.devops.stream.pojo.GitProjectPipeline
import com.tencent.devops.stream.pojo.GitRequestEvent
import com.tencent.devops.stream.pojo.enums.GitCICommitCheckState
import com.tencent.devops.stream.pojo.enums.TriggerReason
import com.tencent.devops.stream.pojo.v2.GitCIBasicSetting
import com.tencent.devops.stream.utils.GitCIPipelineUtils
import com.tencent.devops.stream.v2.dao.GitCIBasicSettingDao
import com.tencent.devops.stream.trigger.GitCIEventService
import com.tencent.devops.stream.trigger.GitCheckService
import com.tencent.devops.stream.v2.service.GitCIV2WebsocketService
import com.tencent.devops.process.pojo.BuildId
import com.tencent.devops.common.ci.v2.enums.gitEventKind.TGitObjectKind
import com.tencent.devops.common.pipeline.enums.ChannelCode
import com.tencent.devops.stream.trigger.parsers.modelCreate.ModelCreate
import com.tencent.devops.stream.utils.StreamTriggerMessageUtils
import com.tencent.devops.stream.v2.service.StreamPipelineBranchService
import com.tencent.devops.stream.trigger.timer.pojo.StreamTimer
import com.tencent.devops.stream.trigger.timer.service.StreamTimerService
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Suppress("ALL")
@Service
class YamlBuildV2 @Autowired constructor(
    client: Client,
    kafkaClient: KafkaClient,
    gitPipelineResourceDao: GitPipelineResourceDao,
    gitRequestEventBuildDao: GitRequestEventBuildDao,
    gitCIEventSaveService: GitCIEventService,
    websocketService: GitCIV2WebsocketService,
    streamPipelineBranchService: StreamPipelineBranchService,
    gitCheckService: GitCheckService,
    streamGitConfig: StreamGitConfig,
    triggerMessageUtil: StreamTriggerMessageUtils,
    private val dslContext: DSLContext,
    private val gitCIBasicSettingDao: GitCIBasicSettingDao,
    private val redisOperation: RedisOperation,
    private val modelCreate: ModelCreate,
    private val streamTimerService: StreamTimerService
) : YamlBaseBuildV2<ScriptBuildYaml>(
    client, kafkaClient, dslContext, gitPipelineResourceDao,
    gitRequestEventBuildDao, gitCIEventSaveService, websocketService, streamPipelineBranchService,
    gitCheckService, streamGitConfig, triggerMessageUtil
) {

    companion object {
        private val logger = LoggerFactory.getLogger(YamlBuildV2::class.java)
        private const val ymlVersion = "v2.0"
        const val VARIABLE_PREFIX = "variables."
        private val channelCode = ChannelCode.GIT
    }

    @Throws(TriggerBaseException::class, ErrorCodeException::class)
    override fun gitStartBuild(
        pipeline: GitProjectPipeline,
        event: GitRequestEvent,
        yaml: ScriptBuildYaml,
        originYaml: String,
        parsedYaml: String?,
        normalizedYaml: String,
        gitBuildId: Long?,
        isTimeTrigger: Boolean
    ): BuildId? {
        val triggerLock = GitCITriggerLock(
            redisOperation = redisOperation,
            gitProjectId = event.gitProjectId,
            pipelineId = pipeline.pipelineId
        )
        try {
            triggerLock.lock()
            val gitBasicSetting = gitCIBasicSettingDao.getSetting(dslContext, event.gitProjectId)!!
            // 优先创建流水线为了绑定红线
            if (pipeline.pipelineId.isBlank()) {
                savePipeline(
                    pipeline = pipeline,
                    event = event,
                    gitCIBasicSetting = gitBasicSetting,
                    model = creatTriggerModel(gitBasicSetting)
                )
            }

            // 如果是定时触发需要注册事件
            if (isTimeTrigger) {
                streamTimerService.saveTimer(
                    StreamTimer(
                        projectId = GitCIPipelineUtils.genGitProjectCode(event.gitProjectId),
                        pipelineId = pipeline.pipelineId,
                        userId = event.userId,
                        crontabExpressions = listOf(yaml.triggerOn?.schedules?.cron.toString()),
                        gitProjectId = event.gitProjectId,
                        // 未填写则在每次触发拉默认分支
                        branchs = yaml.triggerOn?.schedules?.branches,
                        always = yaml.triggerOn?.schedules?.always ?: false,
                        channelCode = channelCode,
                        eventId = event.id!!,
                        originYaml = originYaml
                    )
                )
            }

            return if (gitBuildId != null) {
                startBuildPipeline(
                    pipeline = pipeline,
                    event = event,
                    yaml = yaml,
                    gitBuildId = gitBuildId,
                    gitBasicSetting = gitBasicSetting
                )
            } else {
                null
            }
        } catch (e: Throwable) {
            logger.warn("Fail to start the git ci build($event)", e)
            val (block, message, reason) = when (e) {
                is JsonProcessingException, is ParamBlankException, is CustomException -> {
                    Triple(
                        (event.objectKind == TGitObjectKind.MERGE_REQUEST.value),
                        e.message,
                        TriggerReason.PIPELINE_PREPARE_ERROR
                    )
                }
                is QualityRulesException -> {
                    Triple(
                        false,
                        e.message,
                        TriggerReason.CREATE_QUALITY_RULRS_ERROR
                    )
                }
                // 指定异常直接扔出在外面统一处理
                is TriggerBaseException, is ErrorCodeException -> {
                    throw e
                }
                else -> {
                    logger.error("event: ${event.id} unknow error: ${e.message}")
                    Triple(false, e.message, TriggerReason.UNKNOWN_ERROR)
                }
            }
            TriggerException.triggerError(
                request = event,
                pipeline = pipeline,
                reason = reason,
                reasonParams = listOf(message ?: ""),
                yamls = Yamls(originYaml, parsedYaml, normalizedYaml),
                version = ymlVersion,
                commitCheck = CommitCheck(
                    block = block,
                    state = GitCICommitCheckState.FAILURE
                )
            )
        } finally {
            triggerLock.unlock()
        }
    }

    private fun creatTriggerModel(gitBasicSetting: GitCIBasicSetting) = Model(
        name = GitCIPipelineUtils.genBKPipelineName(gitBasicSetting.gitProjectId),
        desc = "",
        stages = listOf(
            Stage(
                id = "stage-0",
                name = "Stage-0",
                containers = listOf(
                    TriggerContainer(
                        id = "0",
                        name = "构建触发",
                        elements = listOf(
                            ManualTriggerElement(
                                name = "手动触发",
                                id = "T-1-1-1"
                            )
                        )
                    )
                )
            )
        )
    )

    private fun startBuildPipeline(
        pipeline: GitProjectPipeline,
        event: GitRequestEvent,
        yaml: ScriptBuildYaml,
        gitBuildId: Long,
        gitBasicSetting: GitCIBasicSetting
    ): BuildId? {
        logger.info("Git request gitBuildId:$gitBuildId, pipeline:$pipeline, event: $event, yaml: $yaml")

        // create or refresh pipeline
        val model = modelCreate.createPipelineModel(event, gitBasicSetting, yaml, pipeline)
        logger.info("Git request gitBuildId:$gitBuildId, pipeline:$pipeline, model: $model")

        savePipeline(pipeline, event, gitBasicSetting, model)
        return startBuild(pipeline, event, gitBasicSetting, model, gitBuildId)
    }
}
