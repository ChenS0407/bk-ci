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

package com.tencent.devops.gitci.utils

import com.tencent.devops.common.api.util.JsonUtil

object GitCIPipelineUtils {

    fun genGitProjectCode(gitProjectId: Long) = "git_$gitProjectId"

    fun genBKPipelineName(gitProjectId: Long) = "git_" + gitProjectId + "_" + System.currentTimeMillis()

    fun genGitCIV2BuildUrl(homePage: String, projectName: String, pipelineId: String, buildId: String) =
        "$homePage/pipeline/$pipelineId/detail/$buildId/#$projectName"

    fun existBranchesStrToList(existBranches: String?): List<String> {
        if (existBranches == null) {
            return emptyList()
        }
        return existBranches.split(",")
    }

    fun existBranchesListToStr(existBranchesList: List<String>): String? {
        if (existBranchesList.isEmpty()) {
            return null
        }
        return existBranchesList.joinToString()
    }

    fun getExistBranchList(existBranches: String?): List<String>? {
        if (existBranches == null) {
            return null
        }
        return JsonUtil.getObjectMapper().readValue(existBranches, List::class.java) as List<String>
    }

    // 判断当前分支是否为保存的最后一个分支，是的话就可以删除当前流水线
    fun isPipelineDeleteByExistBranches(existBranches: List<String>?, branchName: String): Boolean {
        if (existBranches == null) {
            return false
        }
        val branchList = existBranches.filter { it.isNotBlank() }
        if (existBranches.isEmpty()) {
            return true
        }
        if (branchList.size == 1 && branchList.first() == branchName) {
            return true
        }
        return false
    }

    fun updateExistBranches(existBranches: List<String>?, isNew: Boolean, branchName: String): List<String>? {
        if (isNew) {
            return if (existBranches == null) {
                null
            } else {
                val branchList = existBranches.toMutableList()
                // 原本不存在再新增
                if (branchName !in branchList) {
                    branchList.add(branchName)
                }
                return branchList
            }
        } else {
            return if (existBranches == null) {
                null
            } else {
                val branchList = existBranches.toMutableList()
                branchList.remove(branchName)
                return branchList
            }
        }
    }
}
