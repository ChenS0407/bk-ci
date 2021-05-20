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

package com.tencent.devops.common.ci.v2.utils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.github.fge.jackson.JsonLoader
import com.github.fge.jsonschema.core.report.LogLevel
import com.github.fge.jsonschema.core.report.ProcessingMessage
import com.github.fge.jsonschema.main.JsonSchemaFactory
import com.tencent.devops.common.ci.v2.MrRule
import com.tencent.devops.common.ci.v2.PreScriptBuildYaml
import com.tencent.devops.common.ci.v2.PreTriggerOn
import com.tencent.devops.common.ci.v2.PushRule
import com.tencent.devops.common.ci.v2.ScriptBuildYaml
import com.tencent.devops.common.ci.v2.Stage
import com.tencent.devops.common.ci.v2.TagRule
import com.tencent.devops.common.ci.v2.TriggerOn
import com.tencent.devops.common.ci.v2.YmlVersion
import com.tencent.devops.common.api.exception.CustomException
import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.common.api.util.YamlUtil
import com.tencent.devops.common.ci.v2.Job
import com.tencent.devops.common.ci.v2.Notices
import com.tencent.devops.common.ci.v2.PreJob
import com.tencent.devops.common.ci.v2.PreStage
import com.tencent.devops.common.ci.v2.PreTemplateScriptBuildYaml
import com.tencent.devops.common.ci.v2.RunsOn
import com.tencent.devops.common.ci.v2.Service
import com.tencent.devops.common.ci.v2.StageLabel
import com.tencent.devops.common.ci.v2.Step
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.io.BufferedReader
import java.io.StringReader
import java.util.Random
import java.util.regex.Pattern
import javax.ws.rs.core.Response

object ScriptYmlUtils {

    private val logger = LoggerFactory.getLogger(ScriptYmlUtils::class.java)

    //    private const val dockerHubUrl = "https://index.docker.io/v1/"
    private const val dockerHubUrl = ""

    private const val secretSeed = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    private const val stageNamespace = "stage-"
    private const val jobNamespace = "job-"
    private const val stepNamespace = "step-"

    /**
     * 1、解决锚点
     * 2、yml string层面的格式化填充
     */
    fun formatYaml(yamlStr: String): String {
        // replace custom tag
        val yamlNormal =
            formatYamlCustom(yamlStr)
        // replace anchor tag
        val yaml = Yaml()
        val obj = yaml.load(yamlNormal) as Any
        return YamlUtil.toYaml(obj)
    }

    fun parseVersion(yamlStr: String?): YmlVersion? {
        if (yamlStr == null) {
            return null
        }

        val yaml = Yaml()
        val obj = YamlUtil.toYaml(yaml.load(yamlStr) as Any)
        return YamlUtil.getObjectMapper().readValue(obj, YmlVersion::class.java)
    }

    fun isV2Version(yamlStr: String?):Boolean{
        if (yamlStr == null) {
            return false
        }
        val yaml = Yaml()
        val obj = YamlUtil.toYaml(yaml.load(yamlStr) as Any)
        val version =  YamlUtil.getObjectMapper().readValue(obj, YmlVersion::class.java)
        return version == null || version.version == "v2.0"
    }

    fun parseVariableValue(value: String?, settingMap: Map<String, String?>): String? {
        if (value == null || value.isEmpty()) {
            return ""
        }

        var newValue = value
        val pattern = Pattern.compile("\\$\\{\\{([^{}]+?)}}")
        val matcher = pattern.matcher(value)
        while (matcher.find()) {
            val realValue = settingMap[matcher.group(1).trim()]
            newValue = newValue!!.replace(matcher.group(), realValue ?: "")
        }

        return newValue
    }

    fun parseImage(imageNameInput: String): Triple<String, String, String> {
        val imageNameStr = imageNameInput.removePrefix("http://").removePrefix("https://")
        val arry = imageNameStr.split(":")
        if (arry.size == 1) {
            val str = imageNameStr.split("/")
            return if (str.size == 1) {
                Triple(dockerHubUrl, imageNameStr, "latest")
            } else {
                Triple(str[0], imageNameStr.substringAfter(str[0] + "/"), "latest")
            }
        } else if (arry.size == 2) {
            val str = imageNameStr.split("/")
            when {
                str.size == 1 -> return Triple(dockerHubUrl, arry[0], arry[1])
                str.size >= 2 -> return if (str[0].contains(":")) {
                    Triple(str[0], imageNameStr.substringAfter(str[0] + "/"), "latest")
                } else {
                    if (str.last().contains(":")) {
                        val nameTag = str.last().split(":")
                        Triple(
                            str[0],
                            imageNameStr.substringAfter(str[0] + "/").substringBefore(":" + nameTag[1]),
                            nameTag[1]
                        )
                    } else {
                        Triple(str[0], str.last(), "latest")
                    }
                }
                else -> {
                    logger.error("image name invalid: $imageNameStr")
                    throw Exception("image name invalid.")
                }
            }
        } else if (arry.size == 3) {
            val str = imageNameStr.split("/")
            if (str.size >= 2) {
                val tail = imageNameStr.removePrefix(str[0] + "/")
                val nameAndTag = tail.split(":")
                if (nameAndTag.size != 2) {
                    logger.error("image name invalid: $imageNameStr")
                    throw Exception("image name invalid.")
                }
                return Triple(str[0], nameAndTag[0], nameAndTag[1])
            } else {
                logger.error("image name invalid: $imageNameStr")
                throw Exception("image name invalid.")
            }
        } else {
            logger.error("image name invalid: $imageNameStr")
            throw Exception("image name invalid.")
        }
    }

    private fun formatYamlCustom(yamlStr: String): String {
        val sb = StringBuilder()
        val br = BufferedReader(StringReader(yamlStr))
        var line: String? = br.readLine()
        while (line != null) {
            if (line == "on:") {
                sb.append("triggerOn:").append("\n")
            } else {
                sb.append(line).append("\n")
            }

            line = br.readLine()
        }
        return sb.toString()
    }

    fun checkYaml(preScriptBuildYaml: PreTemplateScriptBuildYaml) {
        checkVariable(preScriptBuildYaml)
        checkStage(preScriptBuildYaml)
        checkNotice(preScriptBuildYaml.notices)
    }

    private fun checkVariable(preScriptBuildYaml: PreTemplateScriptBuildYaml) {
        if (preScriptBuildYaml.variables == null) {
            return
        }

        preScriptBuildYaml.variables.forEach {
            val keyRegex = Regex("^[0-9a-zA-Z_]+$")
            if (!keyRegex.matches(it.key)) {
                throw CustomException(Response.Status.BAD_REQUEST, "变量名称必须是英文字母、数字或下划线(_)")
            }
        }
    }

    private fun checkStage(preScriptBuildYaml: PreTemplateScriptBuildYaml) {
        if ((preScriptBuildYaml.stages != null && preScriptBuildYaml.jobs != null) ||
            (preScriptBuildYaml.stages != null && preScriptBuildYaml.steps != null) ||
            (preScriptBuildYaml.jobs != null && preScriptBuildYaml.steps != null) ||
            (preScriptBuildYaml.extends != null && preScriptBuildYaml.stages != null) ||
            (preScriptBuildYaml.extends != null && preScriptBuildYaml.jobs != null) ||
            (preScriptBuildYaml.extends != null && preScriptBuildYaml.steps != null)
        ) {
            throw CustomException(Response.Status.BAD_REQUEST, "extend, stages, jobs, steps不能并列存在，只能存在其一!")
        }
    }

    private fun checkNotice(notices: List<Notices>?) {
        val types = setOf("email", "wework-message", "wework-chat")
        if (notices == null) {
            return
        }
        notices.forEach {
            if (it.type !in types) {
                throw CustomException(
                    Response.Status.BAD_REQUEST, "通知类型只能为 email, wework-message, wework-chat 中的一种"
                )
            }
        }
    }

    private fun formatStage(preScriptBuildYaml: PreScriptBuildYaml): List<Stage> {
        return when {
            preScriptBuildYaml.steps != null -> {
                listOf(
                    Stage(
                        name = "stage_1",
                        id = randomString(stageNamespace),
                        jobs = listOf(
                            Job(
                                id = randomString(jobNamespace),
                                name = "job1",
                                runsOn = RunsOn(),
                                steps = formatSteps(preScriptBuildYaml.steps)
                            )
                        )
                    )
                )
            }
            preScriptBuildYaml.jobs != null -> {
                listOf(
                    Stage(
                        name = "stage_1",
                        id = randomString(stageNamespace),
                        jobs = preJobs2Jobs(preScriptBuildYaml.jobs)
                    )
                )
            }
            else -> {
                preStages2Stages(preScriptBuildYaml.stages as List<PreStage>)
            }
        }
    }

    private fun preJobs2Jobs(preJobs: Map<String, PreJob>?): List<Job> {
        if (preJobs == null) {
            return emptyList()
        }

        val jobs = mutableListOf<Job>()
        preJobs.forEach { (t, u) ->
            val services = mutableListOf<Service>()
            u.services?.forEach { key, value ->
                services.add(
                    Service(
                        serviceId = key,
                        image = value.image,
                        with = value.with
                    )
                )
            }

            jobs.add(
                Job(
                    id = t,
                    name = u.name,
                    runsOn = formatRunsOn(u.runsOn),
                    services = services,
                    ifField = u.ifField,
                    steps = formatSteps(u.steps),
                    timeoutMinutes = u.timeoutMinutes,
                    env = u.env,
                    continueOnError = u.continueOnError,
                    strategy = u.strategy,
                    dependOn = u.dependOn
                )
            )
        }

        return jobs
    }

    private fun formatRunsOn(preRunsOn: Any?): RunsOn {
        if (preRunsOn == null) {
            return RunsOn()
        }

        return try {
            YamlUtil.getObjectMapper().readValue(preRunsOn.toString(), RunsOn::class.java)
        } catch (e: Exception) {
            RunsOn(
                poolName = preRunsOn.toString()
            )
        }
    }

    private fun formatSteps(oldSteps: List<Step>?): List<Step> {
        if (oldSteps == null) {
            return emptyList()
        }

        val stepList = mutableListOf<Step>()
        oldSteps.forEach {
            if (it.uses == null && it.run == null) {
                throw CustomException(Response.Status.BAD_REQUEST, "step必须包含uses或run!")
            }

            stepList.add(
                Step(
                    name = it.name,
                    id = it.id ?: randomString(stepNamespace),
                    ifFiled = it.ifFiled,
                    uses = it.uses,
                    with = it.with,
                    timeoutMinutes = it.timeoutMinutes,
                    continueOnError = it.continueOnError,
                    retryTimes = it.retryTimes,
                    env = it.env,
                    run = it.run,
                    checkout = it.checkout
                )
            )
        }

        return stepList
    }

    private fun preStages2Stages(preStageList: List<PreStage>?): List<Stage> {
        if (preStageList == null) {
            return emptyList()
        }

        val stageList = mutableListOf<Stage>()
        preStageList.forEach {
            stageList.add(
                Stage(
                    id = it.id ?: randomString(stageNamespace),
                    name = it.name,
                    label = formatStageLabel(it.label),
                    ifField = it.ifField,
                    fastKill = it.fastKill ?: false,
                    jobs = preJobs2Jobs(it.jobs as Map<String, PreJob>)
                )
            )
        }

        return stageList
    }

    private fun formatStageLabel(labels: List<String>?): List<String> {
        if (labels == null) {
            return emptyList()
        }

        val newLabels = mutableListOf<String>()
        labels.forEach {
            val stageLabel = getStageLabel(it)
            if (stageLabel != null) {
                newLabels.add(stageLabel.id)
            } else {
                throw CustomException(Response.Status.BAD_REQUEST, "请核对Stage标签是否正确")
            }
        }

        return newLabels
    }

    private fun getStageLabel(label: String): StageLabel? {
        StageLabel.values().forEach {
            if (it.value == label) {
                return it
            }
        }

        return null
    }

    /**
     * 预处理对象转化为合法对象
     */
    fun normalizeGitCiYaml(preScriptBuildYaml: PreScriptBuildYaml, filePath: String): ScriptBuildYaml {
        val stages = formatStage(
            preScriptBuildYaml
        )

        var thisTriggerOn = TriggerOn(
            push = PushRule(
                branches = listOf("*")
            ),
            tag = TagRule(
                tags = listOf("*")
            ),
            mr = MrRule(
                targetBranches = listOf("*")
            )
        )

        if (preScriptBuildYaml.triggerOn != null) {
            thisTriggerOn =
                formatTriggerOn(
                    preScriptBuildYaml.triggerOn!!
                )
        }

        return ScriptBuildYaml(
            name = if (!preScriptBuildYaml.name.isNullOrBlank()) {
                preScriptBuildYaml.name!!
            } else { filePath.removeSuffix(".yml") },
            version = preScriptBuildYaml.version,
            triggerOn = thisTriggerOn,
            variables = preScriptBuildYaml.variables,
            extends = preScriptBuildYaml.extends,
            resource = preScriptBuildYaml.resources,
            notices = preScriptBuildYaml.notices,
            stages = stages,
            finally = preStages2Stages(preScriptBuildYaml.finally),
            label = preScriptBuildYaml.label ?: emptyList()
        )
    }

    private fun formatTriggerOn(preTriggerOn: PreTriggerOn): TriggerOn {
        var pushRule = PushRule()
        var tagRule = TagRule()
        var mrRule = MrRule()

        if (preTriggerOn.push != null) {
            val push = preTriggerOn.push
            try {
                pushRule = YamlUtil.getObjectMapper().readValue(
                    JsonUtil.toJson(push),
                    PushRule::class.java
                )
            } catch (e: MismatchedInputException) {
                try {
                    val pushObj = YamlUtil.getObjectMapper().readValue(
                        JsonUtil.toJson(push),
                        List::class.java
                    ) as ArrayList<String>

                    pushRule = PushRule(
                        branches = pushObj,
                        branchesIgnore = null,
                        paths = null,
                        pathsIgnore = null,
                        users = null,
                        usersIgnore = null
                    )
                } catch (e: Exception) {
                    logger.error("Format triggerOn pushRule failed.", e)
                }
            }
        }

        if (preTriggerOn.tag != null) {
            val tag = preTriggerOn.tag
            try {
                tagRule = YamlUtil.getObjectMapper().readValue(
                    JsonUtil.toJson(tag),
                    TagRule::class.java
                )
            } catch (e: MismatchedInputException) {
                try {
                    val tagList = YamlUtil.getObjectMapper().readValue(
                        JsonUtil.toJson(tag),
                        List::class.java
                    ) as ArrayList<String>

                    tagRule = TagRule(
                        tags = tagList,
                        tagsIgnore = null,
                        fromBranches = null,
                        users = null,
                        usersIgnore = null
                    )
                } catch (e: Exception) {
                    logger.error("Format triggerOn tagRule failed.", e)
                }
            }
        }

        if (preTriggerOn.mr != null) {
            val mr = preTriggerOn.mr
            try {
                mrRule = YamlUtil.getObjectMapper().readValue(
                    JsonUtil.toJson(mr),
                    MrRule::class.java
                )
            } catch (e: MismatchedInputException) {
                try {
                    val mrList = YamlUtil.getObjectMapper().readValue(
                        JsonUtil.toJson(mr),
                        List::class.java
                    ) as ArrayList<String>

                    mrRule = MrRule(
                        targetBranches = mrList,
                        sourceBranchesIgnore = null,
                        paths = null,
                        pathsIgnore = null,
                        users = null,
                        usersIgnore = null
                    )
                } catch (e: Exception) {
                    logger.error("Format triggerOn mrRule failed.", e)
                }
            }
        }

        return TriggerOn(
            push = pushRule,
            tag = tagRule,
            mr = mrRule
        )
    }

/*    fun validateYaml(yamlStr: String): Pair<Boolean, String> {
        val yamlJsonStr = try {
            convertYamlToJson(yamlStr)
        } catch (e: Throwable) {
            logger.error("", e)
            throw CustomException(Response.Status.BAD_REQUEST, "${e.cause}")
        }

        try {
            val schema = getCIBuildYamlSchema()
            return validate(schema, yamlJsonStr)
        } catch (e: Throwable) {
            logger.error("", e)
            throw CustomException(Response.Status.BAD_REQUEST, "${e.message}")
        }
    }*/

    fun validate(schema: String, json: String): Pair<Boolean, String> {
        val schemaNode =
            jsonNodeFromString(schema)
        val jsonNode =
            jsonNodeFromString(json)
        val report = JsonSchemaFactory.byDefault().validator.validate(schemaNode, jsonNode)
        val itr = report.iterator()
        val sb = java.lang.StringBuilder()
        while (itr.hasNext()) {
            val message = itr.next() as ProcessingMessage
            if (message.logLevel == LogLevel.ERROR || message.logLevel == LogLevel.FATAL) {
                sb.append(message).append("\r\n")
            }
        }
        return Pair(report.isSuccess, sb.toString())
    }

    fun jsonNodeFromString(json: String): JsonNode = JsonLoader.fromString(json)

    fun validateSchema(schema: String): Boolean =
        validateJson(schema)

    fun validateJson(json: String): Boolean {
        try {
            jsonNodeFromString(json)
        } catch (e: Exception) {
            return false
        }
        return true
    }

    fun convertYamlToJson(yaml: String): String {
        val yamlReader = ObjectMapper(YAMLFactory())
        val obj = yamlReader.readValue(yaml, Any::class.java)

        val jsonWriter = ObjectMapper()
        return jsonWriter.writeValueAsString(obj)
    }

    fun parseServiceImage(image: String): Pair<String, String> {
        val list = image.split(":")
        if (list.size != 2) {
            throw CustomException(Response.Status.INTERNAL_SERVER_ERROR, "GITCI Service镜像格式非法")
        }
        return Pair(list[0], list[1])
    }

    private fun randomString(flag: String): String {
        val random = Random()
        val buf = StringBuffer(flag)
        for (i in 0 until 7) {
            val num = random.nextInt(secretSeed.length)
            buf.append(secretSeed[num])
        }
        return buf.toString()
    }
}
