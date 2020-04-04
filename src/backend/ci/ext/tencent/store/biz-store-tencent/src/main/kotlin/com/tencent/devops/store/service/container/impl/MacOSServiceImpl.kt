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

package com.tencent.devops.store.service.container.impl

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.common.service.gray.MacOSGray
import com.tencent.devops.store.service.container.MacOSService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cloud.context.config.annotation.RefreshScope
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

@Service
@RefreshScope
class MacOSServiceImpl @Autowired constructor(
    private val redisOperation: RedisOperation,
    private val macOSGray: MacOSGray
) : MacOSService {

    private val projectEnableCache = CacheBuilder.newBuilder()
        .maximumSize(10000)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build<String/*projectId*/, Boolean/*Enable*/>(
            object : CacheLoader<String, Boolean>() {
                override fun load(projectId: String): Boolean {
                    try {
                        val members = redisOperation.getSetMembers(macOSGray.getRepoGrayRedisKey())
                        if (members != null && members.isNotEmpty()) {
                            val enable = members.contains(projectId)
                            if (enable) {
                                logger.info("[$projectId] The project is already enable in macos gray redis")
                                return enable
                            }
                        }
                    } catch (t: Throwable) {
                        logger.warn("Fail to get the project detail - ($projectId)", t)
                    }
                    return false
                }
            }
        )

    override fun enableProject(projectId: String) {
        logger.info("[$projectId] Enable the macos project in redis.")
        redisOperation.addSetValue(macOSGray.getRepoGrayRedisKey(), projectId)
    }

    override fun disableProject(projectId: String) {
        logger.info("[$projectId] Disable the macos project in redis.")
        redisOperation.removeSetMember(macOSGray.getRepoGrayRedisKey(), projectId)
    }

    override fun isEnable(projectId: String): Boolean {
        return try {
            projectEnableCache.get(projectId)
        } catch (t: Throwable) {
            logger.warn("[$projectId]Fail to get the macos project enable")
            false
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(MacOSServiceImpl::class.java)
    }
}