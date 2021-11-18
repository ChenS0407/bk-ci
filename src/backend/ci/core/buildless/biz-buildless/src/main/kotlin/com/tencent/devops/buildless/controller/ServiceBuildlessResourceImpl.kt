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

package com.tencent.devops.buildless.controller

import com.tencent.devops.buildless.api.service.ServiceBuildlessResource
import com.tencent.devops.buildless.exception.DockerServiceException
import com.tencent.devops.buildless.pojo.BuildlessBuildInfo
import com.tencent.devops.buildless.service.BuildlessService
import com.tencent.devops.common.api.pojo.Result
import com.tencent.devops.common.web.RestResource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired

@RestResource@Suppress("ALL")
class ServiceBuildlessResourceImpl @Autowired constructor(
    private val buildlessService: BuildlessService
) : ServiceBuildlessResource {
    override fun startBuild(dockerHostBuildInfo: BuildlessBuildInfo): Result<String> {
        return try {
            logger.warn("Create agentLess container, dockerHostBuildInfo: $dockerHostBuildInfo")
            Result(buildlessService.createContainer(dockerHostBuildInfo))
        } catch (e: DockerServiceException) {
            logger.error("Create container failed, rollback build. buildId: ${dockerHostBuildInfo.buildId}," +
                    " vmSeqId: ${dockerHostBuildInfo.vmSeqId}")
            Result(e.errorCode, "构建环境启动失败: ${e.message}", "")
        }
    }

    override fun endBuild(dockerHostBuildInfo: BuildlessBuildInfo): Result<Boolean> {
        logger.warn("[${dockerHostBuildInfo.buildId}] | Stop the container, " +
                "containerId: ${dockerHostBuildInfo.containerId}")
        buildlessService.stopContainer(dockerHostBuildInfo)

        return Result(true)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ServiceBuildlessResourceImpl::class.java)
    }
}
