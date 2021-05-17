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

import com.tencent.devops.common.pipeline.container.Container
import com.tencent.devops.common.pipeline.container.NormalContainer
import com.tencent.devops.common.pipeline.container.VMBuildContainer
import com.tencent.devops.common.pipeline.enums.BuildStatus
import com.tencent.devops.common.pipeline.enums.VMBaseOS
import com.tencent.devops.common.pipeline.pojo.element.Element
import com.tencent.devops.common.pipeline.type.BuildType
import com.tencent.devops.process.engine.service.PipelineBuildDetailService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Suppress("ALL")
@Service
class PipelineContextService@Autowired constructor(
    private val pipelineBuildDetailService: PipelineBuildDetailService
) {
    fun buildContext(buildId: String, containerId: String?, buildVar: Map<String, String>): Map<String, String> {
        val modelDetail = pipelineBuildDetailService.get(buildId) ?: return emptyMap()
        val varMap = mutableMapOf<String, String>()
        modelDetail.model.stages.forEach { stage ->
            stage.containers.forEach { c ->
                // current job
                if (c.containerId == containerId) {
                    varMap["job.id"] = containerId ?: ""
                    varMap["job.name"] = c.name
                    varMap["job.status"] = getJobStatus(c)
                    varMap["job.outcome"] = c.status ?: ""
                    varMap["job.os"] = getOs(c)
                    varMap["job.container.network"] = getNetWork(c)
                    varMap["job.stage_id"] = stage.id ?: ""
                    varMap["job.stage_name"] = stage.name ?: ""
                }

                // other job
                varMap["jobs.${c.id}.id"] = c.id ?: ""
                varMap["jobs.${c.id}.name"] = c.name
                varMap["jobs.${c.id}.status"] = getJobStatus(c)
                varMap["jobs.${c.id}.outcome"] = c.status ?: ""
                varMap["jobs.${c.id}.os"] = getOs(c)
                varMap["jobs.${c.id}.container.network"] = getNetWork(c)
                varMap["jobs.${c.id}.stage_id"] = stage.id ?: ""
                varMap["jobs.${c.id}.stage_name"] = stage.name ?: ""

                // steps
                c.elements.forEach { e ->
                    varMap["jobs.${c.id}.steps.${e.id}.name"] = e.name
                    varMap["jobs.${c.id}.steps.${e.id}.id"] = e.id ?: ""
                    varMap["jobs.${c.id}.steps.${e.id}.status"] = getStepStatus(e)
                    varMap["jobs.${c.id}.steps.${e.id}.outcome"] = e.status ?: ""
                    varMap.putAll(getStepOutput(c, e, buildVar))
                }
            }
        }

        return varMap
    }

    private fun getStepOutput(c: Container, e: Element, buildVar: Map<String, String>): Map<out String, String> {
        val outputMap = mutableMapOf<String, String>()
        buildVar.filterKeys { it.startsWith("steps.${e.id ?: ""}.outputs.") }.forEach { (t, u) ->
            outputMap["jobs.${c.id}.$t"] = u
        }
        return outputMap
    }

    private fun getNetWork(c: Container) = when (c) {
        is VMBuildContainer -> {
            if (c.dispatchType?.buildType() != BuildType.THIRD_PARTY_AGENT_ID &&
                c.dispatchType?.buildType() != BuildType.THIRD_PARTY_AGENT_ENV
            ) {
                "DEVNET"
            } else {
                "IDC"
            }
        }
        is NormalContainer -> {
            "IDC"
        }
        else -> {
            ""
        }
    }

    private fun getOs(c: Container) = when (c) {
        is VMBuildContainer -> {
            c.baseOS.name
        }
        is NormalContainer -> {
            VMBaseOS.LINUX.name
        }
        else -> {
            ""
        }
    }

    private fun getJobStatus(c: Container): String {
        return if (c is VMBuildContainer && c.status == BuildStatus.FAILED.name) {
            if (c.jobControlOption?.continueWhenFailed == true) {
                BuildStatus.SUCCEED.name
            } else {
                BuildStatus.FAILED.name
            }
        } else if (c is NormalContainer && c.status == BuildStatus.FAILED.name) {
            if (c.jobControlOption?.continueWhenFailed == true) {
                BuildStatus.SUCCEED.name
            } else {
                BuildStatus.FAILED.name
            }
        } else {
            c.status ?: ""
        }
    }

    private fun getStepStatus(e: Element): String {
        return if (e.status == BuildStatus.FAILED.name) {
            if (e.additionalOptions?.continueWhenFailed == true) {
                BuildStatus.SUCCEED.name
            } else {
                BuildStatus.FAILED.name
            }
        }else {
            e.status ?: ""
        }
    }
}
