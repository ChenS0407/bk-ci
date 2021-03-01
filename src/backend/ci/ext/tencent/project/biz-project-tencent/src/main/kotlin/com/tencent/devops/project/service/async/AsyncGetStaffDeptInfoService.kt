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

package com.tencent.devops.project.service.async

import com.tencent.devops.common.service.utils.SpringContextUtil
import com.tencent.devops.project.pojo.user.UserDeptDetail
import com.tencent.devops.project.service.tof.TOFService
import org.slf4j.LoggerFactory
/**
 * 异步获取人员机构信息
 * author: carlyin
 * since: 2018-12-09
 */
class AsyncGetStaffDeptInfoService(private val userId: String, private val bk_ticket: String) {
    private val logger = LoggerFactory.getLogger(AsyncGetStaffDeptInfoService::class.java)
    val content: UserDeptDetail?
        get() {
            val tofService = SpringContextUtil.getBean(TOFService::class.java)
            try {
                return tofService.getUserDeptDetail(userId, bk_ticket) // 远程调用tof获取人员机构信息
            } catch (e: Exception) {
                logger.error("async staff deptInfo fail,the error is :{}", e)
            }
            return null
        }
}
