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
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy,
 * modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.devops.dispatch.docker.service

import com.tencent.devops.dispatch.docker.common.ErrorCodeEnum
import com.tencent.devops.dispatch.docker.dao.PipelineDockerIPInfoDao
import com.tencent.devops.dispatch.docker.exception.DockerServiceException
import okhttp3.Request
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.net.URLEncoder

@Service
class DockerHostProxyServiceImpl @Autowired constructor(
    private val pipelineDockerIPInfoDao: PipelineDockerIPInfoDao,
    private val dslContext: DSLContext
) : DockerHostProxyService {

    private val logger = LoggerFactory.getLogger(DockerHostProxyServiceImpl::class.java)

    @Value("\${devopsGateway.idcProxy}")
    val idcProxy: String? = null

    override fun getDockerHostProxyRequest(
        dockerHostUri: String,
        dockerHostIp: String,
        dockerHostPort: Int
    ): Request.Builder {
        val url = if (dockerHostPort == 0) {
            val dockerIpInfo = pipelineDockerIPInfoDao.getDockerIpInfo(dslContext, dockerHostIp) ?: throw DockerServiceException(
                ErrorCodeEnum.DOCKER_IP_NOT_AVAILABLE.errorType, ErrorCodeEnum.DOCKER_IP_NOT_AVAILABLE.errorCode, "Docker IP: $dockerHostIp is not available.")
            "http://$dockerHostIp:${dockerIpInfo.dockerHostPort}$dockerHostUri"
        } else {
            "http://$dockerHostIp:$dockerHostPort$dockerHostUri"
        }

        val proxyUrl = idcProxy + "/proxy-devnet?url=" + urlEncode(url)

        return Request.Builder().url(proxyUrl)
            .addHeader("Accept", "application/json; charset=utf-8")
            .addHeader("Content-Type", "application/json; charset=utf-8")
    }

    private fun urlEncode(s: String) = URLEncoder.encode(s, "UTF-8")
}
