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

package com.tencent.devops.quality.cron

import com.tencent.devops.common.redis.RedisLock
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.quality.dao.v2.QualityHisMetadataDao
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class QualityHisMetadataJob @Autowired constructor(
    private val qualityHisMetadataDao: QualityHisMetadataDao,
    private val redisOperation: RedisOperation,
    private val dslContext: DSLContext
) {

    private val logger = LoggerFactory.getLogger(QualityHisMetadataJob::class.java)

    @Value("\${quality.metadata.clean.timeGap:12}")
    var cleanTimeGapHour: Long = 12

    @Scheduled(cron = "0 0 6 * * ?")
    fun clean() {
        val key = this::class.java.name + "#" + Thread.currentThread().stackTrace[1].methodName
        val lock = RedisLock(redisOperation, key, 3600L)
        try {
            if (!lock.tryLock()) {
                logger.info("get lock failed, skip: $key")
                return
            }

            val deleteTime = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(cleanTimeGapHour)

            logger.info("start to delete quality his meta data before: $deleteTime")

            val detailCount = qualityHisMetadataDao.deleteHisMetadataByCreateTime(dslContext, deleteTime)
            val originCount = qualityHisMetadataDao.deleteHisOriginMetadataByCreateTime(dslContext, deleteTime)

            logger.info("finish to delete quality his meta data before: $deleteTime, $detailCount, $originCount")
        } finally {
            lock.unlock()
        }
    }
}
