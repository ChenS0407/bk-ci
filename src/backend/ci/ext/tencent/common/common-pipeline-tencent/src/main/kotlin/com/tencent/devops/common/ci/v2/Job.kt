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

package com.tencent.devops.common.ci.v2

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * WARN: 请谨慎修改这个类 , 不要随意添加或者删除变量 , 否则可能导致依赖yaml的功能(gitci,prebuild等)异常
 */
data class Job(
    // val job: JobDetail,
    val id: String? = "",
    val name: String?,
    @JsonProperty("runs-on")
    val runsOn: RunsOn = RunsOn(),
    // val container: Container?,
    val services: List<Service>? = null,
    @JsonProperty("if")
    val ifField: String? = null,
    val steps: List<Step>?,
    @JsonProperty("timeout-minutes")
    val timeoutMinutes: Int? = 480,
    val env: Map<String, String>? = emptyMap(),
    @JsonProperty("continue-on-error")
    val continueOnError: Boolean? = false,
    val strategy: Strategy? = null,
    @JsonProperty("depend-on")
    val dependOn: List<String>? = emptyList()
)

data class Container(
    val image: String,
    val credentials: Credentials?
)

data class Container2(
    val image: String,
    val credentials: String?
)

data class Credentials(
    val username: String,
    val password: String
)

data class Service(
    val serviceId: String? = "",
    val image: String,
    val with: ServiceWith
)

data class ServiceWith(
    val password: String? = ""
)
data class Strategy(
    val matrix: Any?,
    @JsonProperty("fast-kill")
    val fastKill: Boolean? = false,
    @JsonProperty("max-parallel")
    val maxParallel: String?
)

data class RunsOn(
    @JsonProperty("self-hosted")
    val selfHosted: Boolean? = false,
    @JsonProperty("pool-name")
    val poolName: String = JobRunsOnType.DOCKER.type,
    val container: Any? = null,
    @JsonProperty("agent-selector")
    val agentSelector: List<String>? = null,
    val workspace: String? = null,
    val needs: Map<String, String>? = null
)

enum class JobRunsOnType(val type: String) {
    DOCKER("docker"),
    AGENT_LESS("agentless"),
    DEV_CLOUD("docker-on-devcloud"),
    LOCAL("local")
}
