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

package com.tencent.devops.stream.trigger.parsers

import com.fasterxml.jackson.core.JsonProcessingException
import com.tencent.devops.common.api.exception.CustomException
import com.tencent.devops.common.api.exception.ErrorCodeException
import com.tencent.devops.common.api.util.YamlUtil
import com.tencent.devops.common.ci.v2.PreTemplateScriptBuildYaml
import com.tencent.devops.common.ci.v2.exception.YamlFormatException
import com.tencent.devops.common.ci.v2.utils.ScriptYmlUtils
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.stream.common.exception.CommitCheck
import com.tencent.devops.stream.common.exception.TriggerBaseException
import com.tencent.devops.stream.common.exception.TriggerException
import com.tencent.devops.stream.common.exception.Yamls
import com.tencent.devops.stream.pojo.GitRequestEvent
import com.tencent.devops.stream.pojo.enums.GitCICommitCheckState
import com.tencent.devops.stream.pojo.enums.TriggerReason
import com.tencent.devops.stream.trigger.v2.StreamYamlTrigger
import com.tencent.devops.stream.v2.common.CommonConst
import io.jsonwebtoken.io.IOException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class YamlCheck @Autowired constructor(
    private val redisOperation: RedisOperation
) {
    companion object {
        private val logger = LoggerFactory.getLogger(TriggerMatcher::class.java)
    }

    @Throws(TriggerBaseException::class, ErrorCodeException::class)
    fun formatAndCheckYaml(
        originYaml: String,
        gitRequestEvent: GitRequestEvent,
        filePath: String,
        isMr: Boolean
    ): PreTemplateScriptBuildYaml {
        return try {
            formatAndCheckYaml(originYaml)
        } catch (e: Throwable) {
            logger.info("gitRequestEvent ${gitRequestEvent.id} git ci yaml is invalid", e)
            val (block, message, reason) = when (e) {
                is YamlFormatException, is CustomException -> {
                    Triple(isMr, e.message, TriggerReason.CI_YAML_INVALID)
                }
                is IOException, is TypeCastException -> {
                    Triple(isMr, e.message, TriggerReason.CI_YAML_INVALID)
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
                filePath = filePath,
                reason = reason,
                reasonParams = listOf(message ?: ""),
                yamls = Yamls(originYaml, null, null),
                version = StreamYamlTrigger.ymlVersion,
                commitCheck = CommitCheck(
                    block = block,
                    state = GitCICommitCheckState.FAILURE
                )
            )
        }
    }

    @Throws(
        JsonProcessingException::class,
        RuntimeException::class,
        IOException::class,
        Exception::class,
        YamlFormatException::class
    )
    fun formatAndCheckYaml(originYaml: String): PreTemplateScriptBuildYaml {
        val formatYamlStr = ScriptYmlUtils.formatYaml(originYaml)
        val yamlJsonStr = ScriptYmlUtils.convertYamlToJson(formatYamlStr)

        val gitciYamlSchema = redisOperation.get(CommonConst.REDIS_STREAM_YAML_SCHEMA)
            ?: throw RuntimeException("Check Schema is null.")

        val (schemaPassed, errorMessage) = ScriptYmlUtils.validate(
            schema = gitciYamlSchema,
            yamlJson = yamlJsonStr
        )

        // 先做总体的schema校验
        if (!schemaPassed) {
            logger.warn("Check yaml schema failed. $errorMessage")
            throw YamlFormatException(errorMessage)
        }

        val preTemplateYamlObject =
            YamlUtil.getObjectMapper().readValue(formatYamlStr, PreTemplateScriptBuildYaml::class.java)
        // 检查Yaml语法的格式问题
        ScriptYmlUtils.checkYaml(preTemplateYamlObject, originYaml)

        return preTemplateYamlObject
    }
}
