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

<<<<<<< HEAD:src/backend/ci/ext/tencent/scm/biz-scm/src/main/kotlin/com/tencent/devops/scm/resources/BuildGitCiResourceImpl.kt
package com.tencent.devops.scm.resources

import com.tencent.devops.common.api.pojo.Result
import com.tencent.devops.common.web.RestResource
import com.tencent.devops.repository.pojo.oauth.GitToken
import com.tencent.devops.scm.api.BuildGitCiResource
import com.tencent.devops.scm.services.GitService
import org.springframework.beans.factory.annotation.Autowired

@RestResource
class BuildGitCiResourceImpl @Autowired constructor(
    private val gitService: GitService
) : BuildGitCiResource {

    override fun getToken(gitProjectId: String): Result<GitToken> {
        return Result(gitService.getToken(gitProjectId))
    }

    override fun clearToken(token: String): Result<Boolean> {
        return Result(gitService.clearToken(token))
    }
=======
package com.tencent.devops.project.service

import com.tencent.devops.project.pojo.ShardingRoutingRule

interface ShardingRoutingRuleService {

    fun addShardingRoutingRule(userId: String, shardingRoutingRule: ShardingRoutingRule): Boolean

    fun deleteShardingRoutingRule(userId: String, id: String): Boolean

    fun updateShardingRoutingRule(userId: String, id: String, shardingRoutingRule: ShardingRoutingRule): Boolean

    fun getShardingRoutingRuleById(id: String): ShardingRoutingRule?

    fun getShardingRoutingRuleByName(routingName: String): ShardingRoutingRule?
>>>>>>> carl/issue_5267_sub_db:src/backend/ci/core/project/biz-project/src/main/kotlin/com/tencent/devops/project/service/ShardingRoutingRuleService.kt
}
