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

package com.tencent.devops.process.engine.atom

import com.tencent.devops.common.api.constant.CommonMessageCode
import com.tencent.devops.common.api.constant.KEY_CODE_EDITOR
import com.tencent.devops.common.api.constant.KEY_DEFAULT
import com.tencent.devops.common.api.constant.KEY_INPUT
import com.tencent.devops.common.api.constant.KEY_TEXTAREA
import com.tencent.devops.common.api.exception.ErrorCodeException
import com.tencent.devops.common.api.pojo.ErrorType
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.log.utils.BuildLogPrinter
import com.tencent.devops.common.pipeline.container.Container
import com.tencent.devops.common.pipeline.container.NormalContainer
import com.tencent.devops.common.pipeline.container.VMBuildContainer
import com.tencent.devops.common.pipeline.pojo.element.Element
import com.tencent.devops.common.pipeline.pojo.element.market.MarketBuildAtomElement
import com.tencent.devops.common.pipeline.pojo.element.market.MarketBuildLessAtomElement
import com.tencent.devops.common.service.utils.MessageCodeUtil
import com.tencent.devops.process.constant.ProcessMessageCode
import com.tencent.devops.process.engine.exception.BuildTaskException
import com.tencent.devops.process.engine.pojo.PipelineBuildTask
import com.tencent.devops.process.pojo.config.TaskCommonSettingConfig
import com.tencent.devops.store.api.atom.ServiceMarketAtomEnvResource
import com.tencent.devops.store.pojo.atom.AtomRunInfo
import com.tencent.devops.store.pojo.atom.enums.JobTypeEnum
import com.tencent.devops.store.pojo.common.StoreVersion
import javax.ws.rs.core.Response

object AtomUtils {

    /**
     * 解析出Container中的市场插件，如果市场插件相应版本找不到就抛出异常
     */
    fun parseContainerMarketAtom(
        container: Container,
        task: PipelineBuildTask,
        client: Client,
        buildLogPrinter: BuildLogPrinter
    ): MutableMap<String, String> {
        val atoms = mutableMapOf<String, String>()
        val serviceMarketAtomEnvResource = client.get(ServiceMarketAtomEnvResource::class)
        val atomVersions = getAtomVersions(container)
        if (atomVersions.isNullOrEmpty()) {
            // 如果job容器内没有新插件，则直接返回
            return atoms
        }
        // 批量获取插件运行时信息
        var flag = true
        val atomRunInfoResult = try {
            serviceMarketAtomEnvResource.batchGetAtomRunInfos(task.projectId, atomVersions)
        } catch (ignored: Exception) {
            flag = false
            null
        }
        if (!flag || atomRunInfoResult?.isNotOk() == true) {
            throw BuildTaskException(
                errorType = ErrorType.USER,
                errorCode = ProcessMessageCode.ERROR_ATOM_NOT_FOUND.toInt(),
                errorMsg = atomRunInfoResult?.message ?: "query tasks error",
                pipelineId = task.pipelineId,
                buildId = task.buildId,
                taskId = task.taskId
            )
        }
        val atomRunInfoMap = atomRunInfoResult?.data
        container.elements.forEach nextOne@{ element ->
            if (isHisAtomElement(element)) {
                return@nextOne
            }
            var version = element.version
            if (version.isBlank()) {
                version = "1.*"
            }
            val atomCode = element.getAtomCode()
            val atomRunInfo = atomRunInfoMap?.get("$atomCode:$version")
            if (atomRunInfo == null) {
                val message = "Can't found task($atomCode:$version):${element.name}."
                throw BuildTaskException(
                    errorType = ErrorType.USER,
                    errorCode = ProcessMessageCode.ERROR_ATOM_NOT_FOUND.toInt(),
                    errorMsg = message,
                    pipelineId = task.pipelineId,
                    buildId = task.buildId,
                    taskId = task.taskId
                )
            }
            // 判断插件是否有权限在该job环境下运行(需判断无编译环境插件是否可以在有编译环境下运行)
            val jobRunFlag = when (atomRunInfo.jobType) {
                // 无编译环境插件： 本身就在无编译环境下运行，或者允许无编译插件在编译环境下运行
                JobTypeEnum.AGENT_LESS -> (container is NormalContainer || atomRunInfo.buildLessRunFlag == true)
                // 编译环境插件：需要在编译环境下运行
                JobTypeEnum.AGENT -> container is VMBuildContainer
                else -> false
            }
            if (!jobRunFlag) {
                throw BuildTaskException(
                    errorType = ErrorType.USER,
                    errorCode = ProcessMessageCode.ERROR_ATOM_RUN_BUILD_ENV_INVALID.toInt(),
                    errorMsg = MessageCodeUtil.getCodeMessage(
                        ProcessMessageCode.ERROR_ATOM_RUN_BUILD_ENV_INVALID,
                        arrayOf(element.name)
                    ) ?: ProcessMessageCode.ERROR_ATOM_RUN_BUILD_ENV_INVALID,
                    pipelineId = task.pipelineId,
                    buildId = task.buildId,
                    taskId = task.taskId
                )
            }

            buildLogPrinter.addLine(
                buildId = task.buildId,
                message = "Prepare ${element.name}(${atomRunInfo.atomName})",
                tag = task.taskId,
                jobId = task.containerHashId,
                executeCount = task.executeCount ?: 1
            )
            atoms[atomCode] = atomRunInfo.initProjectCode
        }
        return atoms
    }

    private fun getAtomVersions(container: Container): MutableSet<StoreVersion> {
        val atomVersions = mutableSetOf<StoreVersion>()
        container.elements.forEach nextOne@{ element ->
            if (isHisAtomElement(element)) {
                return@nextOne
            }
            var version = element.version
            if (version.isBlank()) {
                version = "1.*"
            }
            val atomCode = element.getAtomCode()
            atomVersions.add(StoreVersion(
                storeCode = atomCode,
                storeName = element.name,
                version = version,
                historyFlag = false
            ))
        }
        return atomVersions
    }

    fun isHisAtomElement(element: Element) =
        element !is MarketBuildAtomElement && element !is MarketBuildLessAtomElement

    fun getInputTypeConfigMap(taskCommonSettingConfig: TaskCommonSettingConfig): Map<String, Int> {
        return mapOf(
            KEY_INPUT to taskCommonSettingConfig.maxInputComponentSize,
            KEY_TEXTAREA to taskCommonSettingConfig.maxTextareaComponentSize,
            KEY_CODE_EDITOR to taskCommonSettingConfig.maxCodeEditorComponentSize,
            KEY_DEFAULT to taskCommonSettingConfig.maxDefaultInputComponentSize
        )
    }

    fun checkModelAtoms(
        projectCode: String,
        atomVersions: Set<StoreVersion>,
        atomInputParamMap: Map<String, Any>?,
        inputTypeConfigMap: Map<String, Int>,
        client: Client
    ): Boolean {
        if (atomVersions.isEmpty()) {
            return true
        }
        // 批量获取插件运行时信息
        val atomRunInfoResult = try {
            client.get(ServiceMarketAtomEnvResource::class).batchGetAtomRunInfos(projectCode, atomVersions)
        } catch (ignored: Exception) {
            throw ErrorCodeException(
                statusCode = Response.Status.INTERNAL_SERVER_ERROR.statusCode,
                errorCode = CommonMessageCode.SYSTEM_ERROR
            )
        }
        val atomRunInfoMap = atomRunInfoResult.data
        atomVersions.forEach { atomVersion ->
            val atomCode = atomVersion.storeCode
            val version = atomVersion.version
            val atomName = atomVersion.storeName
            val atomRunInfo = atomRunInfoMap?.get("$atomCode:$version")
                ?: throw ErrorCodeException(
                    errorCode = ProcessMessageCode.ERROR_ATOM_RUN_BUILD_ENV_INVALID,
                    params = arrayOf(atomName)
                )
            validateAtomParam(
                atomInputParamMap = atomInputParamMap,
                atomRunInfo = atomRunInfo,
                inputTypeConfigMap = inputTypeConfigMap,
                atomName = atomName
            )
        }
        return true
    }

    @Suppress("UNCHECKED_CAST")
    private fun validateAtomParam(
        atomInputParamMap: Map<String, Any>?,
        atomRunInfo: AtomRunInfo,
        inputTypeConfigMap: Map<String, Int>,
        atomName: String
    ) {
        val atomCode = atomRunInfo.atomCode
        val atomParamDataMap = atomInputParamMap?.get(atomCode) as? Map<String, Any>
        if (atomParamDataMap?.isNotEmpty() == true) {
            val inputTypeInfos = atomRunInfo.inputTypeInfos
            atomParamDataMap.forEach { (paramName, paramValue) ->
                val inputType = inputTypeInfos?.get(paramName)
                val maxInputTypeSize = inputTypeConfigMap[inputType] ?: inputTypeConfigMap[KEY_DEFAULT]
                if (paramValue.toString().length > maxInputTypeSize!!) {
                    throw ErrorCodeException(
                        errorCode = ProcessMessageCode.ERROR_ATOM_PARAM_VALUE_TOO_LARGE,
                        params = arrayOf(atomName, paramName, maxInputTypeSize.toString())
                    )
                }
            }
        }
    }
}
