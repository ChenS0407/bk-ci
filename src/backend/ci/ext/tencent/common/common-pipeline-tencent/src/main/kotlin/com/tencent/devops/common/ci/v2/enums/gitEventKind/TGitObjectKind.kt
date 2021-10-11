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

package com.tencent.devops.common.ci.v2.enums.gitEventKind

// TODO:  后续开源中应该将其抽象汇总为Stream的触发方式
enum class TGitObjectKind(val value: String) {
    PUSH("push"),
    TAG_PUSH("tag_push"),
    MERGE_REQUEST("merge_request"),
    MANUAL("manual"),
    SCHEDULE("schedule");

    // 方便Json初始化使用常量保存，需要同步维护
    companion object {
        const val OBJECT_KIND_MANUAL = "manual"
        const val OBJECT_KIND_PUSH = "push"
        const val OBJECT_KIND_TAG_PUSH = "tag_push"
        const val OBJECT_KIND_MERGE_REQUEST = "merge_request"
        // 定时触发在Stream中本质是定时器使用PUSH触发，所以不在上面维护
        const val OBJECT_KIND_SCHEDULE = "schedule"
    }
}
