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

package com.tencent.devops.scm.services

import com.fasterxml.jackson.core.type.TypeReference
import com.google.gson.JsonParser
import com.tencent.devops.common.api.constant.CommonMessageCode
import com.tencent.devops.common.api.pojo.Result
import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.common.api.util.OkhttpUtils
import com.tencent.devops.common.service.utils.MessageCodeUtil
import com.tencent.devops.repository.pojo.git.GitMember
import com.tencent.devops.scm.pojo.GitCIProjectInfo
import com.tencent.devops.scm.pojo.GitCodeBranchesOrder
import com.tencent.devops.scm.pojo.GitCodeBranchesSort
import okhttp3.Request
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.net.URLEncoder

@Service
class GitCiService {

    companion object {
        private val logger = LoggerFactory.getLogger(GitCiService::class.java)
    }

    @Value("\${gitCI.clientId}")
    private lateinit var gitCIClientId: String

    @Value("\${gitCI.clientSecret}")
    private lateinit var gitCIClientSecret: String

    @Value("\${gitCI.url}")
    private lateinit var gitCIUrl: String

    @Value("\${gitCI.oauthUrl}")
    private lateinit var gitCIOauthUrl: String

    fun getGitCIMembers(
        token: String,
        gitProjectId: String,
        page: Int,
        pageSize: Int,
        search: String?
    ): List<GitMember> {
        val url = "$gitCIUrl/api/v3/projects/${URLEncoder.encode(gitProjectId, "UTF8")}/members" +
                "?access_token=$token" +
                if (search != null) {
                    "&query=$search"
                } else {
                    ""
                } +
                "&page=$page" + "&per_page=$pageSize"
        logger.info("request url: $url")
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        OkhttpUtils.doHttp(request).use {
            val data = it.body()!!.string()
            if (!it.isSuccessful) throw RuntimeException("fail to get the git projects members with: $url($data)")
            return JsonUtil.to(data, object : TypeReference<List<GitMember>>() {})
        }
    }

    fun getBranch(
        token: String,
        gitProjectId: String,
        page: Int,
        pageSize: Int,
        search: String?,
        orderBy: GitCodeBranchesOrder?,
        sort: GitCodeBranchesSort?
    ): List<String> {
        val url = "$gitCIUrl/api/v3/projects/${URLEncoder.encode(gitProjectId, "utf-8")}" +
                "/repository/branches?access_token=$token&page=$page&per_page=$pageSize" +
                if (search != null) {
                    "&search=$search"
                } else {
                    ""
                } +
                if (orderBy != null) {
                    "&order_by=${orderBy.value}"
                } else {
                    ""
                } +
                if (sort != null) {
                    "&sort=${sort.value}"
                } else {
                    ""
                }
        val res = mutableListOf<String>()
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        OkhttpUtils.doHttp(request).use { response ->
            val data = response.body()?.string() ?: return@use
            val branList = JsonParser.parseString(data).asJsonArray
            if (!branList.isJsonNull) {
                branList.forEach {
                    val branch = it.asJsonObject
                    if (branch.isJsonNull) {
                        return@forEach
                    }
                    res.add(if (branch["name"].isJsonNull) "" else branch["name"].asString)
                }
            }
        }
        return res
    }

    fun getGitCIFileContent(
        gitProjectId: Long,
        filePath: String,
        token: String,
        ref: String,
        useAccessToken: Boolean
    ): String {
        logger.info("[$gitProjectId|$filePath|$ref] Start to get the git file content")
        val startEpoch = System.currentTimeMillis()
        try {
            val url = "$gitCIUrl/api/v3/projects/$gitProjectId/repository/blobs/" +
                    "${URLEncoder.encode(ref, "UTF-8")}?filepath=${URLEncoder.encode(filePath, "UTF-8")}" +
                    if (useAccessToken) {
                        "&access_token=$token"
                    } else {
                        "&private_token=$token"
                    }
            logger.info("request url: $url")
            val request = Request.Builder()
                .url(url)
                .get()
                .build()
            OkhttpUtils.doHttp(request).use {
                val data = it.body()!!.string()
                if (!it.isSuccessful) throw RuntimeException("fail to get git file content with: $url($data)")
                return data
            }
        } finally {
            logger.info("It took ${System.currentTimeMillis() - startEpoch}ms to get the git file content")
        }
    }

    fun getGitCIProjectInfo(
        gitProjectId: String,
        token: String,
        useAccessToken: Boolean = true
    ): Result<GitCIProjectInfo?> {
        logger.info("[gitProjectId=$gitProjectId]|getGitCIProjectInfo")
        val encodeId = URLEncoder.encode(gitProjectId, "utf-8") // 如果id为NAMESPACE_PATH则需要encode
        val str = "$gitCIUrl/api/v3/projects/$encodeId?" + if (useAccessToken) {
            "access_token=$token"
        } else {
            "private_token=$token"
        }
        val url = StringBuilder(str)
        val request = Request.Builder()
            .url(url.toString())
            .get()
            .build()
        OkhttpUtils.doHttp(request).use {
            val response = it.body()!!.string()
            logger.info("[url=$url]|getGitCIProjectInfo with response=$response")
            if (!it.isSuccessful) return MessageCodeUtil.generateResponseDataObject(CommonMessageCode.SYSTEM_ERROR)
            return Result(JsonUtil.to(response, GitCIProjectInfo::class.java))
        }
    }
}
