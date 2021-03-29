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

package com.tencent.devops.dockerhost.service

import com.fasterxml.jackson.module.kotlin.readValue
import com.github.dockerjava.api.exception.NotFoundException
import com.github.dockerjava.api.model.AccessMode
import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.HostConfig
import com.github.dockerjava.api.model.Volume
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientBuilder
import com.github.dockerjava.core.command.PullImageResultCallback
import com.github.dockerjava.okhttp.OkDockerHttpClient
import com.github.dockerjava.transport.DockerHttpClient
import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.common.web.mq.alert.AlertLevel
import com.tencent.devops.dispatch.pojo.ContainerInfo
import com.tencent.devops.dockerhost.common.ErrorCodeEnum
import com.tencent.devops.dockerhost.config.DockerHostConfig
import com.tencent.devops.dockerhost.dispatch.AlertApi
import com.tencent.devops.dockerhost.dispatch.DockerHostDebugResourceApi
import com.tencent.devops.dockerhost.exception.ContainerException
import com.tencent.devops.dockerhost.exception.NoSuchImageException
import com.tencent.devops.dockerhost.services.LocalImageCache
import com.tencent.devops.dockerhost.utils.CommonUtils
import com.tencent.devops.dockerhost.utils.RandomUtil
import com.tencent.devops.store.pojo.app.BuildEnv
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File

@Component
class DockerHostDebugService(
    private val dockerHostConfig: DockerHostConfig,
    private val alertApi: AlertApi,
    private val dockerHostDebugResourceApi: DockerHostDebugResourceApi
) {

    private val ENVIRONMENT_LINUX_PATH_PREFIX = "/data/bkdevops/apps/"
    private val TURBO_PATH = "/data/bkdevops/apps/turbo/1.0"
    private val OS_PATH = "/usr/lib64/ccache:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/local/java/bin"

    private val envKeyProjectId = "devops_project_id"
    private val envKeyGateway = "devops_gateway"
    private val envDockerHostIP = "docker_host_ip"
    private val bkDistccLocalIp = "BK_DISTCC_LOCAL_IP"

    private val entryPointCmd = "/data/sleep.sh"

    private val logger = LoggerFactory.getLogger(DockerHostDebugService::class.java)

/*    private val dockerHostDebugApi: DockerHostDebugResourceApi = DockerHostDebugResourceApi(dockerHostConfig.grayEnv)
    private val alertApi: AlertApi =
        AlertApi(dockerHostConfig.grayEnv)*/

    private val config = DefaultDockerClientConfig.createDefaultConfigBuilder()
            .withDockerConfig(dockerHostConfig.dockerConfig)
            .withApiVersion(dockerHostConfig.apiVersion)
            .build()

    final var longHttpClient: DockerHttpClient = OkDockerHttpClient.Builder()
        .dockerHost(config.dockerHost)
        .sslConfig(config.sslConfig)
        .connectTimeout(5000)
        .readTimeout(300000)
        .build()

    private val dockerCli = DockerClientBuilder.getInstance(config).withDockerHttpClient(longHttpClient).build()

    fun startDebug(): ContainerInfo? {
        val result = dockerHostDebugResourceApi.startDebug(CommonUtils.getInnerIP())
        if (result != null) {
            if (result.isNotOk()) {
                return null
            }
        }
        return result!!.data!!
    }

    fun endDebug(): ContainerInfo? {
        val result = dockerHostDebugResourceApi.endDebug(CommonUtils.getInnerIP())
        if (result != null) {
            if (result.isNotOk()) {
                return null
            }
        }
        return result!!.data!!
    }

    fun reportDebugContainerId(pipelineId: String, vmSeqId: String, containerId: String): Boolean {
        val result = dockerHostDebugResourceApi.reportDebugContainerId(pipelineId, vmSeqId, containerId)
        if (result != null) {
            if (result.isNotOk()) {
                logger.info("reportDebugContainerId return msg: ${result.message}")
                return false
            }
        }
        return result!!.data!!
    }

    fun rollbackDebug(pipelineId: String, vmSeqId: String, shutdown: Boolean? = false, msg: String? = ""): Boolean {
        val result = dockerHostDebugResourceApi.rollbackDebug(pipelineId, vmSeqId, shutdown, msg)
        if (result != null) {
            if (result.isNotOk()) {
                logger.info("rollbackDebug return msg: ${result.message}")
                return false
            }
        }
        return result!!.data!!
    }

    fun createContainer(containerInfo: ContainerInfo): String {
        try {
            val authConfig = CommonUtils.getAuthConfig(
                imageType = containerInfo.imageType,
                dockerHostConfig = dockerHostConfig,
                imageName = containerInfo.imageName,
                registryUser = containerInfo.registryUser,
                registryPwd = containerInfo.registryPwd
            )
            val imageName = CommonUtils.normalizeImageName(containerInfo.imageName)
            // docker pull
            try {
                LocalImageCache.saveOrUpdate(imageName)
                dockerCli.pullImageCmd(imageName).withAuthConfig(authConfig).exec(PullImageResultCallback()).awaitCompletion()
            } catch (t: Throwable) {
                logger.error("Pull images failed， imageName:$imageName")
            }
            // docker run
            val volumeWs = Volume(dockerHostConfig.volumeWorkspace)
            val volumeProjectShare = Volume(dockerHostConfig.volumeProjectShare)
            val volumeMavenRepo = Volume(dockerHostConfig.volumeMavenRepo)
            val volumeNpmPrefix = Volume(dockerHostConfig.volumeNpmPrefix)
            val volumeNpmCache = Volume(dockerHostConfig.volumeNpmCache)
            val volumeCcache = Volume(dockerHostConfig.volumeCcache)
            val volumeApps = Volume(dockerHostConfig.volumeApps)
            val volumeSleep = Volume(dockerHostConfig.volumeSleep)
            val volumeGradleCache = Volume(dockerHostConfig.volumeGradleCache)

            val gateway = System.getProperty("soda.gateway", "gw.open.oa.com")
            logger.info("gateway is: $gateway")

            val envList = mutableListOf<String>()
            var PATH = ""
            if (!containerInfo.buildEnv.isNullOrBlank()) {
                val buildEnvs: List<BuildEnv> = JsonUtil.getObjectMapper().readValue(containerInfo.buildEnv!!)
                if (buildEnvs.isNotEmpty()) {
                    buildEnvs.forEach { buildEnv ->
                        val home = File(ENVIRONMENT_LINUX_PATH_PREFIX, "${buildEnv.name}/${buildEnv.version}/")
                        if (!home.exists()) {
                            logger.error("Env path:(${home.absolutePath}) not exists")
                        }
                        val envFile = File(home, buildEnv.binPath)
                        if (!envFile.exists()) {
                            return@forEach
                        }
                        // command.append("export $name=$path")
                        PATH = if (PATH.isEmpty()) {
                            envFile.absolutePath
                        } else {
                            "${envFile.absolutePath}:$PATH"
                        }
                        if (buildEnv.env.isNotEmpty()) {
                            buildEnv.env.forEach { name, path ->
                                val p = File(home, path)
                                envList.add("$name=${p.absolutePath}")
                            }
                        }
                    }
                }
            }
            logger.info("envList is: $envList; PATH is $PATH")

            val tailPath = getTailPath(containerInfo)
            val binds = mutableListOf(Bind("${dockerHostConfig.hostPathMavenRepo}/${containerInfo.pipelineId}/$tailPath/", volumeMavenRepo),
                    Bind("${dockerHostConfig.hostPathNpmPrefix}/${containerInfo.pipelineId}/$tailPath/", volumeNpmPrefix),
                    Bind("${dockerHostConfig.hostPathNpmCache}/${containerInfo.pipelineId}/$tailPath/", volumeNpmCache),
                    Bind("${dockerHostConfig.hostPathCcache}/${containerInfo.pipelineId}/$tailPath/", volumeCcache),
                    Bind(dockerHostConfig.hostPathApps, volumeApps, AccessMode.ro),
                    Bind(dockerHostConfig.hostPathSleep, volumeSleep, AccessMode.ro),
                    Bind("${dockerHostConfig.hostPathGradleCache}/${containerInfo.pipelineId}/$tailPath/", volumeGradleCache),
                    Bind(getWorkspace(containerInfo.pipelineId, tailPath), volumeWs))
            if (enableProjectShare(containerInfo.projectId)) {
                binds.add(Bind(getProjectShareDir(containerInfo.projectId), volumeProjectShare))
            }

            val containerName = "debug-${containerInfo.pipelineId}-${containerInfo.vmSeqId}-${RandomUtil.randomString()}"
            val container = dockerCli.createContainerCmd(imageName)
                .withName(containerName)
                .withCmd("/bin/sh", entryPointCmd)
                .withEnv(
                    envList.plus(
                        listOf(
                            "$envKeyProjectId=${containerInfo.projectId}",
                            "$envKeyGateway=$gateway",
                            "TERM=xterm-256color",
                            "pool_no=${containerInfo.poolNo}",
                            "landun_env=${dockerHostConfig.landunEnv ?: "prod"}",
                            "PATH=$PATH:$TURBO_PATH:$OS_PATH",
                            "$envDockerHostIP=${CommonUtils.getInnerIP()}",
                            "$bkDistccLocalIp=${CommonUtils.getInnerIP()}"
                        )
                    )
                )
                .withVolumes(volumeWs).withVolumes(volumeApps).withVolumes(volumeSleep)
                .withHostConfig(HostConfig().withBinds(binds).withNetworkMode("bridge"))
                .exec()

            logger.info("Created container $container")
            dockerCli.startContainerCmd(container.id).exec()

            return container.id
        } catch (er: Throwable) {
            logger.error(er.toString())
            logger.error(er.cause.toString())
            logger.error(er.message)
            if (er is NotFoundException) {
                throw NoSuchImageException("Create container failed: ${er.message}")
            } else {
                alertApi.alert(AlertLevel.HIGH.name, "Docker构建机创建容器失败", "Docker构建机创建容器失败, " +
                        "母机IP:${CommonUtils.getInnerIP()}， 失败信息：${er.message}")
                throw ContainerException(
                    errorCodeEnum = ErrorCodeEnum.CREATE_CONTAINER_ERROR,
                    message = "Create container failed"
                )
            }
        }
    }

    fun stopContainer(containerInfo: ContainerInfo) {
        try {
            // docker stop
            val inspectInfo = dockerCli.inspectContainerCmd(containerInfo.containerId).exec()
            if ("exited" != inspectInfo.state.status) {
                dockerCli.stopContainerCmd(containerInfo.containerId).withTimeout(1).exec()
            }
        } catch (e: Throwable) {
            logger.error("Stop the container failed, containerId: ${containerInfo.containerId}, error msg: $e")
        }

        try {
            // docker rm
            dockerCli.removeContainerCmd(containerInfo.containerId).exec()
        } catch (e: Throwable) {
            logger.error("Stop the container failed, containerId: ${containerInfo.containerId}, error msg: $e")
        }
    }

    fun getContainerNum(): Int {
        try {
            val dockerInfo = dockerCli.infoCmd().exec()
            return dockerInfo.containersRunning ?: 0
        } catch (e: Throwable) {
            logger.error("Get container num failed")
        }
        return 0
    }

    private fun getWorkspace(pipelineId: String, vmSeqId: String): String {
        return "${dockerHostConfig.hostPathWorkspace}/$pipelineId/$vmSeqId/"
    }

    private fun getProjectShareDir(projectCode: String): String {
        return "${dockerHostConfig.hostPathProjectShare}/$projectCode/"
    }

    private fun enableProjectShare(projectCode: String): Boolean {
        if (dockerHostConfig.shareProjectCodeWhiteList.isNullOrBlank()) {
            return false
        }
        val whiteList = dockerHostConfig.shareProjectCodeWhiteList!!.split(",").map { it.trim() }
        return whiteList.contains(projectCode)
    }

    private fun getTailPath(containerInfo: ContainerInfo): String {
        return if (containerInfo.poolNo > 1) {
            "${containerInfo.vmSeqId}_${containerInfo.poolNo}"
        } else {
            containerInfo.vmSeqId
        }
    }
}
