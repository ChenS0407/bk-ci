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

package com.tencent.devops.gitci.resources.user

import com.tencent.devops.common.api.exception.ParamBlankException
import com.tencent.devops.common.api.pojo.Result
import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.common.ci.CiYamlUtils
import com.tencent.devops.common.ci.v2.utils.ScriptYmlUtils
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.common.web.RestResource
import com.tencent.devops.gitci.api.user.UserGitCITriggerResource
import com.tencent.devops.gitci.permission.GitCIV2PermissionService
import com.tencent.devops.gitci.pojo.GitYamlString
import com.tencent.devops.gitci.pojo.TriggerBuildReq
import com.tencent.devops.gitci.pojo.V2TriggerBuildReq
import com.tencent.devops.gitci.pojo.v2.V2BuildYaml
import com.tencent.devops.gitci.service.GitCITriggerService
import com.tencent.devops.gitci.service.trigger.RequestTriggerFactory
import com.tencent.devops.gitci.utils.GitCommonUtils
import com.tencent.devops.gitci.v2.common.CommonConst
import com.tencent.devops.gitci.v2.service.GitCIV2PipelineService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired

@RestResource
class UserGitCITriggerResourceImpl @Autowired constructor(
    private val gitCITriggerService: GitCITriggerService,
    private val gitCIV2PipelineService: GitCIV2PipelineService,
    private val permissionService: GitCIV2PermissionService,
    private val requestTriggerFactory: RequestTriggerFactory,
    private val redisOperation: RedisOperation
) : UserGitCITriggerResource {
    companion object {
        private val logger = LoggerFactory.getLogger(UserGitCITriggerResourceImpl::class.java)
    }

    override fun triggerStartup(
        userId: String,
        pipelineId: String,
        triggerBuildReq: V2TriggerBuildReq
    ): Result<Boolean> {
        val gitProjectId = GitCommonUtils.getGitProjectId(triggerBuildReq.projectId)
        checkParam(userId)
        permissionService.checkGitCIAndOAuthAndEnable(userId, triggerBuildReq.projectId, gitProjectId)
        val new = with(triggerBuildReq) {
            TriggerBuildReq(
                gitProjectId = gitProjectId,
                name = null,
                url = null,
                homepage = null,
                gitHttpUrl = null,
                gitSshUrl = null,
                branch = branch,
                customCommitMsg = customCommitMsg,
                yaml = yaml,
                description = description,
                commitId = commitId
            )
        }
        return Result(gitCITriggerService.triggerBuild(userId, pipelineId, new))
    }

    override fun checkYaml(userId: String, yaml: GitYamlString): Result<String> {
        // 检查yml版本，根据yml版本选择不同的实现
        val ymlVersion = ScriptYmlUtils.parseVersion(yaml.yaml)
        if (ymlVersion == null || ymlVersion.version != "v2.0") {
            try {
                val yamlStr = CiYamlUtils.formatYaml(yaml.yaml)
                logger.debug("yaml str : $yamlStr")

                val (validate, message) = gitCITriggerService.validateCIBuildYaml(yamlStr)
                if (!validate) {
                    logger.error("Check yaml failed, error: $message")
                    return Result(1, "Invalid yaml: $message", message)
                }
                gitCITriggerService.createCIBuildYaml(yaml.yaml)

                return Result("OK")
            } catch (e: Throwable) {
                logger.error("Check yaml failed, error: ${e.message}, yaml: $yaml")
                return Result(1, "Invalid yaml", e.message)
            }
        } else {
            val triggerInterface = requestTriggerFactory.getGitCIRequestTrigger(ymlVersion)
            return triggerInterface.checkYamlSchema(userId, yaml.yaml)
        }
    }

    override fun saveYamlSchema(userId: String, yamlSchema: String): Result<String> {
        logger.info("User: $userId save yamlSchema: $yamlSchema")
        redisOperation.set(
            key = CommonConst.REDIS_GITCI_YAML_SCHEMA,
            value = JsonUtil.toJson(yamlSchema),
            expired = false
        )

        return Result("OK")
    }

    override fun getYamlSchema(userId: String): Result<String> {
        val schema = gitCITriggerService.getCIBuildYamlSchema()
        logger.info("ci build yaml schema: $schema")
        return Result(schema)
    }

    override fun getYamlByBuildId(userId: String, projectId: String, buildId: String): Result<V2BuildYaml?> {
        val gitProjectId = GitCommonUtils.getGitProjectId(projectId)
        checkParam(userId)
        return Result(gitCITriggerService.getYamlV2(gitProjectId, buildId))
    }

    override fun getYamlByPipeline(
        userId: String,
        projectId: String,
        pipelineId: String,
        branchName: String,
        commitId: String?
    ): Result<String?> {
        val gitProjectId = GitCommonUtils.getGitProjectId(projectId)
        checkParam(userId)
        val ref = if (commitId.isNullOrBlank()) {
            branchName
        } else {
            commitId
        }
        return Result(gitCIV2PipelineService.getYamlByPipeline(gitProjectId, pipelineId, ref))
    }

    private fun checkParam(userId: String) {
        if (userId.isBlank()) {
            throw ParamBlankException("Invalid userId")
        }
    }
}
