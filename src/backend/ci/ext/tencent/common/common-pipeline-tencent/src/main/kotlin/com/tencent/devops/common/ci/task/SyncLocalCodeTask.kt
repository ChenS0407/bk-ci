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

package com.tencent.devops.common.ci.task

import com.tencent.devops.common.ci.CiBuildConfig
import com.tencent.devops.common.ci.yaml.Condition
import com.tencent.devops.common.pipeline.pojo.element.market.MarketBuildAtomElement
import io.swagger.annotations.ApiModel
import io.swagger.annotations.ApiModelProperty

/**
 * SyncLocalCodeTask
 */
@ApiModel("同步本地代码")
data class SyncLocalCodeTask(
    @ApiModelProperty("displayName", required = false)
    override var displayName: String?,
    @ApiModelProperty("入参", required = true)
    override val inputs: SyncLocalCodeInput,
    @ApiModelProperty("执行条件", required = true)
    override val condition: Condition?
) : AbstractTask(displayName, inputs, condition) {
    companion object {
        const val taskType = "syncLocalCode"
        const val taskVersion = "@latest"
    }

    override fun covertToElement(config: CiBuildConfig): MarketBuildAtomElement {
        val data = mapOf(
            "input" to mapOf(
                "agentId" to inputs.agentId,
                "workspace" to inputs.workspace
            )
        )

        return MarketBuildAtomElement(
            displayName ?: "同步本地代码",
            null,
            null,
            "syncAgentCode",
            inputs.version,
            data
        )
    }
}

@ApiModel("同步本地代码入参")
data class SyncLocalCodeInput(
    @ApiModelProperty("版本", required = false)
    var version: String = "1.*",
    @ApiModelProperty("agentId", required = true)
    var agentId: String,
    @ApiModelProperty("工作目录", required = true)
    var workspace: String
) : AbstractInput()
