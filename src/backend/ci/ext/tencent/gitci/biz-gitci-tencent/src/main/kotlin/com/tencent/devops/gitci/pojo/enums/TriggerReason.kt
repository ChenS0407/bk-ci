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

package com.tencent.devops.gitci.pojo.enums

enum class TriggerReason(val detail: String) {
    TRIGGER_SUCCESS("trigger success"),
    GIT_CI_DISABLE("git ci config is disabled"),
    BUILD_PUSHED_BRANCHES_DISABLE("build pushed branches is disabled"),
    BUILD_PUSHED_PULL_REQUEST_DISABLE("build pushed pull request is disabled"),
    GIT_CI_YAML_NOT_FOUND("git ci yaml file not found"),
    GIT_CI_YAML_INVALID("git ci yaml is invalid"),
    GIT_CI_YAML_VERSION_BEHIND("git ci source branch yaml file version behind target branch"),
    GIT_CI_MERGE_CHECK_CONFLICT("git ci merge request check conflict, please wait"),
    GIT_CI_MERGE_CHECK_CONFLICT_TIMEOUT("git ci merge request check conflict timeout"),
    GIT_CI_MERGE_HAS_CONFLICT("git ci merge request has conflict"),
    TRIGGER_NOT_MATCH("yaml trigger is not match"),
    PIPELINE_RUN_ERROR("pipeline run with error"),
    PIPELINE_DISABLE("pipeline is not enabled"),
    GIT_CI_YAML_TEMPLATE_ERROR("git ci yaml template replace error");

    companion object {

        fun getTriggerReason(name: String): TriggerReason? {
            values().forEach { enumObj ->
                if (enumObj.name == name) {
                    return enumObj
                }
            }
            return null
        }
    }
}
