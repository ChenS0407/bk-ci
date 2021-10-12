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

package com.tencent.devops.stream.trigger.parsers.yamlCheck

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.tencent.devops.common.api.exception.CustomException
import com.tencent.devops.common.api.exception.ErrorCodeException
import com.tencent.devops.common.ci.v2.exception.YamlFormatException
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.stream.common.exception.CommitCheck
import com.tencent.devops.stream.common.exception.TriggerBaseException
import com.tencent.devops.stream.common.exception.TriggerException
import com.tencent.devops.stream.common.exception.Yamls
import com.tencent.devops.stream.config.StreamGitConfig
import com.tencent.devops.stream.pojo.enums.GitCICommitCheckState
import com.tencent.devops.stream.pojo.enums.TriggerReason
import com.tencent.devops.stream.pojo.isMr
import com.tencent.devops.stream.trigger.StreamTriggerContext
import com.tencent.devops.stream.trigger.template.pojo.enums.TemplateType
import com.tencent.devops.stream.trigger.v2.StreamYamlTrigger
import com.tencent.devops.stream.v2.service.StreamGitTokenService
import com.tencent.devops.stream.v2.service.StreamScmService
import io.jsonwebtoken.io.IOException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class YamlSchemaCheck @Autowired constructor(
    private val redisOperation: RedisOperation,
    private val scmService: StreamScmService,
    private val streamGitTokenService: StreamGitTokenService,
    private val streamGitConfig: StreamGitConfig
) {

    companion object {
        private const val REDIS_STREAM_YAML_SCHEMA = "stream:yaml.schema.v2:json"
        private const val CI_SCHEMA = "ci"
        private const val TEMPLATE_EXTEND_SCHEMA = "template-extends"
        private const val TEMPLATE_STAGE_SCHEMA = "template-stages"
        private const val TEMPLATE_JOB_SCHEMA = "template-jobs"
        private const val TEMPLATE_STEP_SCHEMA = "template-steps"
        private const val TEMPLATE_VARIABLE_SCHEMA = "template-variables"
        private const val TEMPLATE_GATE_SCHEMA = "template-gates"
    }

    private val logger = LoggerFactory.getLogger(YamlSchemaCheck::class.java)

    private val yamlFactory = ObjectMapper(YAMLFactory())
    private val schemaFactory = JsonSchemaFactory.builder(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7))
        .objectMapper(yamlFactory)
        .build()

    private val schemaMap = mutableMapOf<String, JsonSchema>()

    // 给来自前端的接口用，直接扔出去就好
    fun check(originYaml: String, templateType: TemplateType?, isCiFile: Boolean) {
        checkYamlSchema(originYaml, templateType, isCiFile)
    }

    fun check(context: StreamTriggerContext, templateType: TemplateType?, isCiFile: Boolean) {
        schemaExceptionHandle(context) {
            checkYamlSchema(context.originYaml, templateType, isCiFile)
        }
    }

    private fun checkYamlSchema(originYaml: String, templateType: TemplateType? = null, isCiFile: Boolean) {
        val yamlJson = yamlFactory.readTree(originYaml)
        if (isCiFile) {
            getSchema(CI_SCHEMA).check(yamlJson)
            // 校验schema后有一些特殊的校验
            yamlJson.checkCiRequired()
            yamlJson.checkVariablesFormat()
        }
        if (templateType == null) {
            return
        }
        when (templateType) {
            TemplateType.EXTEND -> {
                getSchema(TEMPLATE_EXTEND_SCHEMA).check(yamlJson)
                yamlJson.checkExtendsRequired()
                yamlJson.checkVariablesFormat()
            }
            TemplateType.VARIABLE -> {
                getSchema(TEMPLATE_VARIABLE_SCHEMA).check(yamlJson)
                yamlJson.checkVariablesFormat()
            }
            TemplateType.STAGE -> getSchema(TEMPLATE_STAGE_SCHEMA).check(yamlJson)
            TemplateType.GATE -> getSchema(TEMPLATE_GATE_SCHEMA).check(yamlJson)
            TemplateType.JOB -> getSchema(TEMPLATE_JOB_SCHEMA).check(yamlJson)
            TemplateType.STEP -> getSchema(TEMPLATE_STEP_SCHEMA).check(yamlJson)
            else -> {
                return
            }
        }
    }

    private fun schemaExceptionHandle(
        context: StreamTriggerContext,
        action: () -> Unit
    ) {
        try {
            action()
        } catch (e: Throwable) {
            val gitRequestEvent = context.requestEvent
            logger.info("gitRequestEvent ${gitRequestEvent.id} git ci yaml is invalid", e)
            val (block, message, reason) = when (e) {
                is YamlFormatException, is CustomException -> {
                    Triple(gitRequestEvent.isMr(), e.message, TriggerReason.CI_YAML_INVALID)
                }
                is IOException, is TypeCastException -> {
                    Triple(gitRequestEvent.isMr(), e.message, TriggerReason.CI_YAML_INVALID)
                }
                // 指定异常直接扔出在外面统一处理
                is TriggerBaseException, is ErrorCodeException -> {
                    throw e
                }
                else -> {
                    logger.error("event: ${gitRequestEvent.id} unknow error: ${e.message}")
                    Triple(false, e.message, TriggerReason.UNKNOWN_ERROR)
                }
            }
            TriggerException.triggerError(
                request = gitRequestEvent,
                filePath = context.pipeline.filePath,
                reason = reason,
                reasonParams = listOf(message ?: ""),
                yamls = Yamls(context.originYaml, null, null),
                version = StreamYamlTrigger.ymlVersion,
                commitCheck = CommitCheck(
                    block = block,
                    state = GitCICommitCheckState.FAILURE
                )
            )
        }
    }

    private fun getSchema(file: String): JsonSchema {
        // TODO: 上线schema时先实验一段时间，使用redis做兜底，没有问题后下掉redis逻辑
        // 先去取下redis看看有没有变量，没有拿代码里初始化好的变量
        val str = redisOperation.get("$REDIS_STREAM_YAML_SCHEMA:$file") ?: return getSchemaFromGit(file)
        return schemaFactory.getSchema(str)
    }

    private fun getSchemaFromGit(file: String): JsonSchema {
        if (schemaMap[file] != null) {
            return schemaMap[file]!!
        }
        val schema = schemaFactory.getSchema(
            scmService.getYamlFromGit(
                token = streamGitTokenService.getToken(streamGitConfig.schemaGitProjectId!!),
                gitProjectId = streamGitConfig.schemaGitProjectId!!.toString(),
                fileName = "${streamGitConfig.schemaGitPath}/$file.json",
                ref = streamGitConfig.schemaGitRef!!,
                useAccessToken = true
            ).ifBlank {
                throw RuntimeException("init yaml schema for git error: yaml blank")
            }
        )
        schemaMap[file] = schema
        return schema
    }
}

private fun JsonSchema.check(yaml: JsonNode) {
    validate(yaml).let {
        if (!it.isNullOrEmpty()) {
            throw YamlFormatException(it.toString())
        }
    }
}

private fun JsonNode.checkVariablesFormat() {
    val vars = get("variables") ?: return
    val keyRegex = Regex("^[0-9a-zA-Z_]+$")
    vars.fields().forEach {
        if (!keyRegex.matches(it.key)) {
            throw YamlFormatException("变量名称必须是英文字母、数字或下划线(_)")
        }
    }
}

private fun JsonNode.checkCiRequired() {
    val requiredList = listOf("stages", "jobs", "steps", "extends")
    requiredList.forEach {
        if (get(it) != null) {
            return
        }
    }
    throw YamlFormatException("stages, jobs, steps, extends 必须存在一个")
}

private fun JsonNode.checkExtendsRequired() {
    if (get("stages") == null && get("jobs") == null && get("steps") == null) {
        throw YamlFormatException("stages, jobs, steps, extends 必须存在一个")
    }
}
