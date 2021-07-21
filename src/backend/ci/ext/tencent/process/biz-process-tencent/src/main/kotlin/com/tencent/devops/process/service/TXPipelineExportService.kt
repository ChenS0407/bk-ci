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

package com.tencent.devops.process.service

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.tencent.devops.common.api.exception.ErrorCodeException
import com.tencent.devops.common.api.pojo.ErrorCode
import com.tencent.devops.common.api.util.DateTimeUtil
import com.tencent.devops.common.auth.api.AuthPermission
import com.tencent.devops.common.ci.v2.Credentials
import com.tencent.devops.common.ci.v2.ExportPreScriptBuildYaml
import com.tencent.devops.common.ci.v2.JobRunsOnType
import com.tencent.devops.common.ci.v2.PreJob
import com.tencent.devops.common.ci.v2.PreStage
import com.tencent.devops.common.ci.v2.RunsOn
import com.tencent.devops.common.pipeline.Model
import com.tencent.devops.common.pipeline.container.Container
import com.tencent.devops.common.pipeline.container.NormalContainer
import com.tencent.devops.common.pipeline.container.TriggerContainer
import com.tencent.devops.common.pipeline.container.VMBuildContainer
import com.tencent.devops.common.pipeline.enums.DockerVersion
import com.tencent.devops.common.pipeline.enums.JobRunCondition
import com.tencent.devops.common.pipeline.enums.StageRunCondition
import com.tencent.devops.common.pipeline.pojo.element.RunCondition
import com.tencent.devops.common.pipeline.pojo.element.agent.LinuxScriptElement
import com.tencent.devops.common.pipeline.pojo.element.agent.ManualReviewUserTaskElement
import com.tencent.devops.common.pipeline.pojo.element.agent.WindowsScriptElement
import com.tencent.devops.common.pipeline.pojo.element.market.MarketBuildAtomElement
import com.tencent.devops.common.pipeline.pojo.element.market.MarketBuildLessAtomElement
import com.tencent.devops.common.pipeline.type.DispatchType
import com.tencent.devops.common.pipeline.type.StoreDispatchType
import com.tencent.devops.common.pipeline.type.agent.AgentType
import com.tencent.devops.common.pipeline.type.agent.ThirdPartyAgentEnvDispatchType
import com.tencent.devops.common.pipeline.type.agent.ThirdPartyAgentIDDispatchType
import com.tencent.devops.common.pipeline.type.devcloud.PublicDevCloudDispathcType
import com.tencent.devops.common.pipeline.type.docker.DockerDispatchType
import com.tencent.devops.common.pipeline.type.docker.ImageType
import com.tencent.devops.common.pipeline.type.exsi.ESXiDispatchType
import com.tencent.devops.common.pipeline.type.macos.MacOSDispatchType
import com.tencent.devops.process.engine.service.PipelineRepositoryService
import com.tencent.devops.process.engine.service.store.StoreImageHelper
import com.tencent.devops.process.permission.PipelinePermissionService
import com.tencent.devops.process.service.label.PipelineGroupService
import com.tencent.devops.common.ci.v2.Step as V2Step
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.net.URLEncoder
import java.time.LocalDateTime
import java.util.regex.Pattern
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response
import javax.ws.rs.core.StreamingOutput

@Suppress("ALL")
@Service("TXPipelineExportService")
class TXPipelineExportService @Autowired constructor(
    private val stageTagService: StageTagService,
    private val pipelineGroupService: PipelineGroupService,
    private val pipelinePermissionService: PipelinePermissionService,
    private val pipelineRepositoryService: PipelineRepositoryService,
    private val storeImageHelper: StoreImageHelper
) {

    companion object {
        private val logger = LoggerFactory.getLogger(TXPipelineExportService::class.java)
        private val yamlObjectMapper = ObjectMapper(YAMLFactory()).apply {
            registerModule(KotlinModule())
        }
    }

    // 导出工蜂CI-2.0的yml
    fun exportV2Yaml(userId: String, projectId: String, pipelineId: String, isGitCI: Boolean = false): Response {
        pipelinePermissionService.validPipelinePermission(
            userId = userId,
            projectId = projectId,
            pipelineId = pipelineId,
            permission = AuthPermission.EDIT,
            message = "用户($userId)无权限在工程($projectId)下导出流水线"
        )
        val model = pipelineRepositoryService.getModel(pipelineId) ?: throw ErrorCodeException(
            statusCode = Response.Status.BAD_REQUEST.statusCode,
            errorCode = ErrorCode.USER_RESOURCE_NOT_FOUND.toString(),
            defaultMessage = "流水线已不存在，请检查"
        )
        val yamlSb = getYamlStringBuilder(
            projectId = projectId,
            pipelineId = pipelineId,
            model = model,
            isGitCI = isGitCI
        )

        // 将所有插件ID按编排顺序刷新
        var stepCount = 1
        model.stages.forEach { s ->
            s.containers.forEach { c ->
                c.elements.forEach { e ->
                    e.id = "step_$stepCount"
                    stepCount++
                }
            }
        }

        val pipelineGroupsMap = mutableMapOf<String, String>()
        pipelineGroupService.getGroups(userId, projectId).forEach {
            it.labels.forEach { label ->
                pipelineGroupsMap[label.id] = label.name
            }
        }
        val stageTagsMap = stageTagService.getAllStageTag().data?.map {
            it.id to it.stageTagName
        }?.toMap() ?: emptyMap()

        val output2Element = mutableMapOf</*outputName*/String, MarketBuildAtomElement>()

        val yamlObj = try {
            ExportPreScriptBuildYaml(
                version = "v2.0",
                name = model.name,
                label = model.labels.map { pipelineGroupsMap[it] ?: "" },
                triggerOn = null,
                variables = getVariableFromModel(model),
                stages = getV2StageFromModel(
                    userId = userId,
                    projectId = projectId,
                    pipelineId = pipelineId,
                    model = model,
                    comment = yamlSb,
                    stageTagsMap = stageTagsMap,
                    output2Element = output2Element
                ),
                extends = null,
                resources = null,
                notices = null,
                finally = getV2FinalFromStage(
                    userId = userId,
                    projectId = projectId,
                    pipelineId = pipelineId,
                    stage = model.stages.last(),
                    comment = yamlSb,
                    output2Element = output2Element
                )
            )
        } catch (t: Throwable) {
            logger.error("Export v2 yaml with error, return blank yml", t)
            if (t is ErrorCodeException) throw t
            ExportPreScriptBuildYaml(
                version = "v2.0",
                name = model.name,
                label = if (model.labels.isNullOrEmpty()) null else model.labels,
                triggerOn = null,
                variables = null,
                stages = null,
                extends = null,
                resources = null,
                notices = null,
                finally = null
            )
        }
        val modelYaml = toYamlStr(yamlObj)
        yamlSb.append(modelYaml)
        return exportToFile(yamlSb.toString(), model.name)
    }

    private fun getV2StageFromModel(
        userId: String,
        projectId: String,
        pipelineId: String,
        model: Model,
        comment: StringBuilder,
        stageTagsMap: Map<String, String>,
        output2Element: MutableMap<String, MarketBuildAtomElement>
    ): List<PreStage> {
        val stages = mutableListOf<PreStage>()
        model.stages.drop(1).forEach { stage ->
            if (stage.finally) {
                return@forEach
            }
            val jobs = getV2JobFromStage(
                userId = userId,
                projectId = projectId,
                pipelineId = pipelineId,
                stage = stage,
                comment = comment,
                output2Element = output2Element
            )
            val tags = mutableListOf<String>()
            stage.tag?.forEach {
                val tagName = stageTagsMap[it]
                if (!tagName.isNullOrBlank()) tags.add(tagName)
            }
            stages.add(
                PreStage(
                    name = stage.name,
                    id = null,
                    label = tags,
                    ifField = if (stage.stageControlOption?.runCondition == StageRunCondition.CUSTOM_CONDITION_MATCH) {
                        stage.stageControlOption?.customCondition
                    } else {
                        null
                    },
                    fastKill = if (stage.fastKill == true) true else null,
                    jobs = jobs
                )
            )
        }
        return stages
    }

    private fun getV2FinalFromStage(
        userId: String,
        projectId: String,
        pipelineId: String,
        stage: com.tencent.devops.common.pipeline.container.Stage,
        comment: StringBuilder,
        output2Element: MutableMap<String, MarketBuildAtomElement>
    ): Map<String, PreJob>? {
        if (stage.finally) {
            return getV2JobFromStage(
                userId = userId,
                projectId = projectId,
                pipelineId = pipelineId,
                stage = stage,
                comment = comment,
                output2Element = output2Element
            )
        }
        return null
    }

    private fun getV2JobFromStage(
        userId: String,
        projectId: String,
        pipelineId: String,
        stage: com.tencent.devops.common.pipeline.container.Stage,
        comment: StringBuilder,
        output2Element: MutableMap<String, MarketBuildAtomElement>
    ): Map<String, PreJob>? {
        val jobs = mutableMapOf<String, PreJob>()
        stage.containers.forEach {
            val jobKey = if (!it.jobId.isNullOrBlank()) {
                it.jobId!!
            } else if (!it.id.isNullOrBlank()) {
                "job_${it.id!!}"
            } else {
                "unknown_job"
            }
            when (it.getClassType()) {
                NormalContainer.classType -> {
                    val job = it as NormalContainer
                    val timeoutMinutes = job.jobControlOption?.timeout ?: 480
                    jobs[jobKey] = PreJob(
                        name = job.name,
                        runsOn = RunsOn(
                            selfHosted = null,
                            poolName = JobRunsOnType.AGENT_LESS.type,
                            container = null
                        ),
                        container = null,
                        services = null,
                        ifField = if (job.jobControlOption?.runCondition ==
                            JobRunCondition.CUSTOM_CONDITION_MATCH) {
                            job.jobControlOption?.customCondition
                        } else {
                            null
                        },
                        steps = getV2StepFromJob(job, comment, output2Element),
                        timeoutMinutes = if (timeoutMinutes < 480) timeoutMinutes else null,
                        env = null,
                        continueOnError = if (job.jobControlOption?.continueWhenFailed == true) true else null,
                        strategy = null,
                        // 蓝盾这边是自定义Job ID
                        dependOn = if (!job.jobControlOption?.dependOnId.isNullOrEmpty()) {
                            job.jobControlOption?.dependOnId
                        } else null
                    )
                }
                VMBuildContainer.classType -> {
                    val job = it as VMBuildContainer
                    val timeoutMinutes = job.jobControlOption?.timeout ?: 480

                    // 编译环境的相关映射处理
                    val runsOn = when (val dispatchType = getDispatchType(job)) {
                        is ThirdPartyAgentEnvDispatchType -> {
                            RunsOn(
                                selfHosted = true,
                                poolName = "### 该环境不支持自动导出，请参考 https://iwiki.woa.com/x/2ebDKw 手动配置 ###",
                                container = null,
                                agentSelector = listOf(job.baseOS.name.toLowerCase())
                            )
                        }
                        is DockerDispatchType -> {
                            val (containerImage, credentials) = getImageNameAndCredentials(
                                userId = userId,
                                projectId = projectId,
                                pipelineId = pipelineId,
                                dispatchType = dispatchType
                            )
                            RunsOn(
                                selfHosted = null,
                                poolName = JobRunsOnType.DOCKER.type,
                                container = com.tencent.devops.common.ci.v2.Container(
                                    image = containerImage,
                                    credentials = credentials
                                ),
                                agentSelector = null
                            )
                        }
                        is PublicDevCloudDispathcType -> {
                            val (containerImage, credentials) = getImageNameAndCredentials(
                                userId = userId,
                                projectId = projectId,
                                pipelineId = pipelineId,
                                dispatchType = dispatchType
                            )
                            RunsOn(
                                selfHosted = null,
                                poolName = JobRunsOnType.DOCKER.type,
                                container = com.tencent.devops.common.ci.v2.Container(
                                    image = containerImage,
                                    credentials = credentials
                                ),
                                agentSelector = null
                            )
                        }
                        is MacOSDispatchType -> {
                            RunsOn(
                                selfHosted = null,
                                poolName = "### 可以通过 runs-on: macos-10.15 使用macOS公共构建集群。" +
                                    "注意默认的Xcode版本为12.2，若需自定义，请在JOB下自行执行 xcode-select 命令切换 ###",
                                container = null,
                                agentSelector = null
                            )
                        }
                        else -> {
                            RunsOn(
                                selfHosted = null,
                                poolName = "### 该环境不支持自动导出，请参考 https://iwiki.woa.com/x/2ebDKw 手动配置 ###",
                                container = null,
                                agentSelector = null
                            )
                        }
                    }

                    jobs[jobKey] = PreJob(
                        name = job.name,
                        runsOn = runsOn,
                        container = null,
                        services = null,
                        ifField = if (job.jobControlOption?.runCondition ==
                            JobRunCondition.CUSTOM_CONDITION_MATCH) {
                            job.jobControlOption?.customCondition
                        } else {
                            null
                        },
                        steps = getV2StepFromJob(job, comment, output2Element),
                        timeoutMinutes = if (timeoutMinutes < 480) timeoutMinutes else null,
                        env = null,
                        continueOnError = if (job.jobControlOption?.continueWhenFailed == true) true else null,
                        strategy = null,
                        dependOn = if (!job.jobControlOption?.dependOnId.isNullOrEmpty()) {
                            job.jobControlOption?.dependOnId
                        } else null
                    )
                }
                else -> {
                    logger.error("get jobs from stage failed, unknown classType:(${it.getClassType()})")
                }
            }
        }
        return if (jobs.isEmpty()) null else jobs
    }

    private fun getV2StepFromJob(
        job: Container,
        comment: StringBuilder,
        output2Element: MutableMap<String, MarketBuildAtomElement>
    ): List<V2Step> {
        val stepList = mutableListOf<V2Step>()
        job.elements.forEach { element ->
            val originRetryTimes = element.additionalOptions?.retryCount ?: 0
            val originTimeout = element.additionalOptions?.timeout?.toInt() ?: 480
            val retryTimes = if (originRetryTimes > 1) originRetryTimes else null
            val timeoutMinutes = if (originTimeout < 480) originTimeout else null
            val continueOnError = if (element.additionalOptions?.continueWhenFailed == true) true else null
            when (element.getClassType()) {
                // Bash脚本插件直接转为run
                LinuxScriptElement.classType -> {
                    val step = element as LinuxScriptElement
                    stepList.add(
                        V2Step(
                            name = step.name,
                            id = step.id,
                            ifFiled = if (step.additionalOptions?.runCondition ==
                                RunCondition.CUSTOM_CONDITION_MATCH) {
                                step.additionalOptions?.customCondition
                            } else {
                                null
                            },
                            uses = null,
                            with = null,
                            timeoutMinutes = timeoutMinutes,
                            continueOnError = continueOnError,
                            retryTimes = retryTimes,
                            env = null,
                            run = formatScriptOutput(step.script, output2Element),
                            checkout = null
                        )
                    )
                }
                WindowsScriptElement.classType -> {
                    val step = element as WindowsScriptElement
                    stepList.add(
                        V2Step(
                            name = step.name,
                            id = step.id,
                            ifFiled = if (step.additionalOptions?.runCondition ==
                                RunCondition.CUSTOM_CONDITION_MATCH) {
                                step.additionalOptions?.customCondition
                            } else {
                                null
                            },
                            uses = null,
                            with = null,
                            timeoutMinutes = timeoutMinutes,
                            continueOnError = continueOnError,
                            retryTimes = retryTimes,
                            env = null,
                            run = formatScriptOutput(step.script, output2Element),
                            checkout = null
                        )
                    )
                }
                MarketBuildAtomElement.classType -> {
                    val step = element as MarketBuildAtomElement
                    val input = element.data["input"]
                    val output = element.data["output"]
                    val namespace = element.data["namespace"] as String?
                    val inputMap = if (input != null && !(input as MutableMap<String, Any>).isNullOrEmpty()) {
                        input
                    } else null
                    if (output != null && !(output as MutableMap<String, Any>).isNullOrEmpty()) {
                        output.keys.forEach { key ->
                            val outputWithNamespace = if (namespace.isNullOrBlank()) key else "${namespace}_$key"
                            val conflictElement = output2Element[outputWithNamespace]
                            if (conflictElement != null) throw ErrorCodeException(
                                statusCode = Response.Status.BAD_REQUEST.statusCode,
                                errorCode = ErrorCode.USER_INPUT_INVAILD.toString(),
                                defaultMessage = "插件[${element.name}]与[${conflictElement.name}]存在相同输出变量[$outputWithNamespace]"
                            )
                            output2Element[outputWithNamespace] = element
                        }
                    }
                    stepList.add(
                        V2Step(
                            name = step.name,
                            id = step.id,
                            ifFiled = if (step.additionalOptions?.runCondition ==
                                RunCondition.CUSTOM_CONDITION_MATCH) {
                                step.additionalOptions?.customCondition
                            } else {
                                null
                            },
                            uses = "${step.getAtomCode()}@${step.version}",
                            with = replaceMapWithDoubleCurlyBraces(inputMap, output2Element),
                            timeoutMinutes = timeoutMinutes,
                            continueOnError = continueOnError,
                            retryTimes = retryTimes,
                            env = null,
                            run = null,
                            checkout = null
                        )
                    )
                }
                MarketBuildLessAtomElement.classType -> {
                    val step = element as MarketBuildLessAtomElement
                    val input = element.data["input"]
                    val inputMap = if (input != null && !(input as MutableMap<String, Any>).isNullOrEmpty()) {
                        input
                    } else null
                    stepList.add(
                        V2Step(
                            name = step.name,
                            id = step.id,
                            ifFiled = if (step.additionalOptions?.runCondition ==
                                RunCondition.CUSTOM_CONDITION_MATCH) {
                                step.additionalOptions?.customCondition
                            } else {
                                null
                            },
                            uses = "${step.getAtomCode()}@${step.version}",
                            with = replaceMapWithDoubleCurlyBraces(inputMap, output2Element),
                            timeoutMinutes = timeoutMinutes,
                            continueOnError = continueOnError,
                            retryTimes = retryTimes,
                            env = null,
                            run = null,
                            checkout = null
                        )
                    )
                }
                ManualReviewUserTaskElement.classType -> {
                    val step = element as ManualReviewUserTaskElement
                    stepList.add(
                        V2Step(
                            name = null,
                            id = step.id,
                            ifFiled = null,
                            uses = "### [${step.name}] 人工审核插件请改用Stage审核 ###",
                            with = null,
                            timeoutMinutes = null,
                            continueOnError = null,
                            retryTimes = null,
                            env = null,
                            run = null,
                            checkout = null
                        )
                    )
                }
                else -> {
                    logger.info("Not support plugin:${element.getClassType()}, skip...")
                    comment.append(
                        "# 注意：不支持插件【${element.name}(${element.getClassType()})】的导出，" +
                            "请在蓝盾研发商店查找推荐的替换插件！\n"
                    )
                    stepList.add(
                        V2Step(
                            name = null,
                            id = element.id,
                            ifFiled = null,
                            uses = "### [${element.name}] 内置老插件不支持导出，请使用市场插件 ###",
                            with = null,
                            timeoutMinutes = null,
                            continueOnError = null,
                            retryTimes = null,
                            env = null,
                            run = null,
                            checkout = null
                        )
                    )
                }
            }
        }
        return stepList
    }

    fun replaceMapWithDoubleCurlyBraces(
        inputMap: MutableMap<String, Any>?,
        output2Element: MutableMap<String, MarketBuildAtomElement>
    ): Map<String, Any?>? {
        if (inputMap.isNullOrEmpty()) {
            return null
        }
        val result = mutableMapOf<String, Any>()
        inputMap.forEach { (key, value) ->
            result[key] = replaceValueWithDoubleCurlyBraces(value, output2Element)
        }
        return result
    }

    private fun replaceValueWithDoubleCurlyBraces(
        value: Any,
        output2Element: MutableMap<String, MarketBuildAtomElement>
    ): Any {
        if (value is String) {
            return replaceStringWithDoubleCurlyBraces(value, output2Element)
        }
        if (value is List<*>) {
            val result = mutableListOf<Any?>()
            value.forEach {
                if (it is String) {
                    result.add(replaceStringWithDoubleCurlyBraces(it, output2Element))
                } else  {
                    result.add(it)
                }
            }
            return result
        }

        return value
    }

    private fun replaceStringWithDoubleCurlyBraces(
        value: String,
        output2Element: MutableMap<String, MarketBuildAtomElement>
    ): String {
        val pattern = Pattern.compile("\\\$\\{([^{}]+?)}")
        val matcher = pattern.matcher(value)
        var newValue = value as String
        while (matcher.find()) {
            val originKey = matcher.group(1).trim()
            // 假设匹配到了前序插件的output则优先引用，否则引用全局变量
            val existingOutputElement = output2Element[originKey]
            val realValue = if (existingOutputElement != null) {
                "\${{ steps.${existingOutputElement.id}.outputs.$originKey }}"
            } else {
                "\${{ variables.$originKey }}"
            }
            newValue = newValue.replace(matcher.group(), realValue)
        }
        return newValue
    }

    private fun getYamlStringBuilder(
        projectId: String,
        pipelineId: String,
        model: Model,
        isGitCI: Boolean
    ): StringBuilder {

        val yamlSb = StringBuilder()
        yamlSb.append("############################################################################" +
            "#########################################\n")
        yamlSb.append("# 项目ID: $projectId \n")
        yamlSb.append("# 流水线ID: $pipelineId \n")
        yamlSb.append("# 流水线名称: ${model.name} \n")
        yamlSb.append("# 导出时间: ${DateTimeUtil.toDateTime(LocalDateTime.now())} \n")
        yamlSb.append("# \n")
        yamlSb.append("# 注意：不支持系统凭证(用户名、密码)的导出，请检查系统凭证的完整性！ \n")
        yamlSb.append("# 注意：[插件]输入参数可能存在敏感信息，请仔细检查，谨慎分享！！！ \n")
        if (isGitCI) {
            yamlSb.append("# 注意：[插件]工蜂CI不支持蓝盾老版本的插件，请在研发商店搜索新插件替换 \n")
        }
        yamlSb.append("########################################################" +
            "#############################################################\n\n")
        return yamlSb
    }

    private fun exportToFile(yaml: String, pipelineName: String): Response {
        // 流式下载
        val fileStream = StreamingOutput { output ->
            val sb = StringBuilder()
            sb.append(yaml)
            output.write(sb.toString().toByteArray())
            output.flush()
        }
        val fileName = URLEncoder.encode("$pipelineName.yml", "UTF-8")
        return Response
            .ok(fileStream, MediaType.APPLICATION_OCTET_STREAM_TYPE)
            .header("content-disposition", "attachment; filename = $fileName")
            .header("Cache-Control", "no-cache")
            .build()
    }

    private fun getVariableFromModel(model: Model): Map<String, String>? {
        val result = mutableMapOf<String, String>()
        (model.stages[0].containers[0] as TriggerContainer).params.forEach {
            result[it.id] = it.defaultValue.toString()
        }
        return if (result.isEmpty()) {
            null
        } else {
            result
        }
    }

    private fun toYamlStr(bean: Any?): String {
        return bean?.let {
            yamlObjectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .writeValueAsString(it)!!
        } ?: ""
    }

    /**
     * 新版的构建环境直接传入指定的构建机方式
     */
    private fun getDispatchType(param: VMBuildContainer): DispatchType {
        if (param.dispatchType != null) {
            return param.dispatchType!!
        } else {
            // 第三方构建机ID
            val agentId = param.thirdPartyAgentId ?: ""
            // 构建环境ID
            val envId = param.thirdPartyAgentEnvId ?: ""
            val workspace = param.thirdPartyWorkspace ?: ""
            return if (agentId.isNotBlank()) {
                ThirdPartyAgentIDDispatchType(displayName = agentId, workspace = workspace, agentType = AgentType.ID)
            } else if (envId.isNotBlank()) {
                ThirdPartyAgentEnvDispatchType(envName = envId, workspace = workspace, agentType = AgentType.ID)
            } // docker建机指定版本(旧)
            else if (!param.dockerBuildVersion.isNullOrBlank()) {
                DockerDispatchType(param.dockerBuildVersion!!)
            } else {
                ESXiDispatchType()
            }
        }
    }

    private fun getImageNameAndCredentials(
        userId: String,
        projectId: String,
        pipelineId: String,
        dispatchType: StoreDispatchType
    ): Pair<String, Credentials?> {
        try {
            when (dispatchType.imageType) {
                ImageType.BKSTORE -> {
                    val imageRepoInfo = storeImageHelper.getImageRepoInfo(
                        userId = userId,
                        projectId = projectId,
                        pipelineId = pipelineId,
                        buildId = "",
                        imageCode = dispatchType.imageCode,
                        imageVersion = dispatchType.imageVersion,
                        defaultPrefix = null
                    )
                    val completeImageName = if (ImageType.BKDEVOPS == imageRepoInfo.sourceType) {
                        // 蓝盾项目源镜像
                        "${imageRepoInfo.repoUrl}/${imageRepoInfo.repoName}"
                    } else {
                        // 第三方源镜像
                        // dockerhub镜像名称不带斜杠前缀
                        if (imageRepoInfo.repoUrl.isBlank()) {
                            imageRepoInfo.repoName
                        } else {
                            "${imageRepoInfo.repoUrl}/${imageRepoInfo.repoName}"
                        }
                    } + ":" + imageRepoInfo.repoTag
                    return Pair(completeImageName, Credentials(
                        "### 重新配置凭据(${imageRepoInfo.ticketId})后填入 ###",
                        "### 重新配置凭据(${imageRepoInfo.ticketId})后填入 ###"
                    ))
                }
                ImageType.BKDEVOPS -> {
                    // 针对非商店的旧数据处理
                    return if (dispatchType.value != DockerVersion.TLINUX1_2.value && dispatchType.value != DockerVersion.TLINUX2_2.value) {
                        dispatchType.dockerBuildVersion = "bkdevops/" + dispatchType.value
                        Pair("bkdevops/" + dispatchType.value, null)
                    } else {
                        Pair("### 该镜像暂不支持自动导出，请参考 https://iwiki.woa.com/x/2ebDKw 手动配置 ###", null)
                    }
                }
                else -> {
                    return if (dispatchType.credentialId.isNullOrBlank()) {
                        Pair(dispatchType.value, null)
                    } else Pair(dispatchType.value, Credentials(
                        "### 重新配置凭据(${dispatchType.credentialId})后填入 ###",
                        "### 重新配置凭据(${dispatchType.credentialId})后填入 ###"
                    ))
                }
            }
        } catch (e: Exception) {
            return Pair("###请直接填入镜像(TLinux2.2公共镜像)的URL地址，若存在鉴权请增加 credentials 字段###", null)
        }
    }

    fun formatScriptOutput(
        script: String,
        output2Element: MutableMap<String, MarketBuildAtomElement>
    ): String {
        val regex = Regex("setEnv\\s+(.*[\\s]+.*)[\\s\\n]")
        val foundMatches = regex.findAll(script)
        var formatScript: String = script
        foundMatches.forEach { result ->
            val keyValueStr = if (result.groupValues.size >= 2) result.groupValues[1] else return@forEach
            val keyAndValue = keyValueStr.split(Regex("\\s+"))
            if (keyAndValue.size < 2) return@forEach
            val key = keyAndValue[0].removeSurrounding("\"")
            val value = keyAndValue[1].removeSurrounding("\"")
            formatScript =
                formatScript.replace(result.value, "echo \"::set-output name=$key::$value\"\n")
        }
        return replaceStringWithDoubleCurlyBraces(formatScript, output2Element)
    }
}
